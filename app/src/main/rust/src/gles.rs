//! OpenGL ES 2 backend for Neocari's shared drawables. EGL stays on Android's
//! existing render thread; soft/inverted clipping uses an RGBA framebuffer.
use std::os::raw::c_void;
use std::ptr;

use neocari::assets::DecodedTexture;
use neocari::moc3::{Moc3DrawableBlendMode, Moc3DrawableMesh};
use neocari::render::common::{DrawableInfo, draw_order_indices};

use crate::{ffi, overlays};

const VERTEX: &str = r#"
attribute vec2 aPosition;
attribute vec2 aUv;
uniform vec4 uTransform;
varying vec2 vUv;
varying vec2 vMaskUv;
void main() {
    vec2 p = aPosition * uTransform.xy + uTransform.zw;
    gl_Position = vec4(p, 0.0, 1.0);
    vUv = aUv;
    vMaskUv = p * 0.5 + 0.5;
}
"#;

const FRAGMENT: &str = r#"
precision mediump float;
uniform sampler2D uTexture;
uniform sampler2D uMask;
uniform float uOpacity;
uniform vec3 uMultiply;
uniform vec3 uScreen;
uniform int uMode;
varying vec2 vUv;
varying vec2 vMaskUv;
void main() {
    vec4 color = texture2D(uTexture, vUv);
    if (uMode == 3) {
        gl_FragColor = vec4(color.a);
    } else {
        vec3 rgb = color.rgb * uMultiply;
        rgb = rgb + uScreen - rgb * uScreen;
        float alpha = color.a * uOpacity;
        if (uMode == 1 || uMode == 2) {
            float mask = texture2D(uMask, vMaskUv).a;
            alpha *= uMode == 2 ? 1.0 - mask : mask;
        }
        gl_FragColor = vec4(rgb * alpha, alpha);
    }
}
"#;

#[derive(Default)]
struct MeshBuffers {
    vertex: u32,
    index: u32,
    count: i32,
}

impl Drop for MeshBuffers {
    fn drop(&mut self) {
        unsafe {
            ffi::glDeleteBuffers(1, &self.vertex);
            ffi::glDeleteBuffers(1, &self.index);
        }
    }
}

pub struct ModelRenderer {
    program: u32,
    textures: Vec<u32>,
    buffers: Vec<MeshBuffers>,
    position: i32,
    uv: i32,
    transform: i32,
    opacity: i32,
    multiply: i32,
    screen: i32,
    mode: i32,
    framebuffer: u32,
    mask: u32,
    white: u32,
    mask_size: (i32, i32),
    max_texture_size: i32,
    vertices: Vec<f32>,
}

impl ModelRenderer {
    pub fn new(textures: Vec<DecodedTexture>, meshes: &[Moc3DrawableMesh]) -> Result<Self, String> {
        let program = overlays::link_program(VERTEX, FRAGMENT, "Neocari")?;
        let uniform =
            |name: &std::ffi::CStr| unsafe { ffi::glGetUniformLocation(program, name.as_ptr()) };
        let mut renderer = Self {
            program,
            textures: Vec::new(),
            buffers: Vec::new(),
            position: unsafe { ffi::glGetAttribLocation(program, c"aPosition".as_ptr()) },
            uv: unsafe { ffi::glGetAttribLocation(program, c"aUv".as_ptr()) },
            transform: uniform(c"uTransform"),
            opacity: uniform(c"uOpacity"),
            multiply: uniform(c"uMultiply"),
            screen: uniform(c"uScreen"),
            mode: uniform(c"uMode"),
            framebuffer: 0,
            mask: 0,
            white: 0,
            mask_size: (0, 0),
            max_texture_size: 0,
            vertices: Vec::new(),
        };
        if [
            renderer.position,
            renderer.uv,
            renderer.transform,
            renderer.opacity,
            renderer.multiply,
            renderer.screen,
            renderer.mode,
        ]
        .iter()
        .any(|v| *v < 0)
        {
            return Err("Neocari shader 属性初始化失败".into());
        }
        unsafe {
            ffi::glGetIntegerv(ffi::GL_MAX_TEXTURE_SIZE, &mut renderer.max_texture_size);
            ffi::glUseProgram(program);
            ffi::glUniform1i(uniform(c"uTexture"), 0);
            ffi::glUniform1i(uniform(c"uMask"), 1);
            ffi::glActiveTexture(ffi::GL_TEXTURE0);
        }
        for texture in textures {
            if texture.width() > renderer.max_texture_size as u32
                || texture.height() > renderer.max_texture_size as u32
            {
                return Err("模型贴图超过设备 OpenGL ES 尺寸限制".into());
            }
            let mut id = 0;
            unsafe {
                ffi::glGenTextures(1, &mut id);
            }
            renderer.textures.push(id);
            upload_texture(
                id,
                texture.width() as i32,
                texture.height() as i32,
                texture.rgba().as_ptr().cast(),
            );
        }
        unsafe {
            ffi::glGenTextures(1, &mut renderer.white);
        }
        upload_texture(renderer.white, 1, 1, [255_u8; 4].as_ptr().cast());
        for mesh in meshes {
            if mesh.texture_index() < 0 || mesh.texture_index() as usize >= renderer.textures.len()
            {
                return Err(format!(
                    "模型网格引用了不存在的贴图 {}",
                    mesh.texture_index()
                ));
            }
            if mesh
                .indices()
                .iter()
                .any(|index| *index as usize >= mesh.vertices().len())
            {
                return Err("模型网格包含越界顶点索引".into());
            }
            let mut buffers = MeshBuffers {
                count: mesh.indices().len() as i32,
                ..Default::default()
            };
            unsafe {
                ffi::glGenBuffers(1, &mut buffers.vertex);
                ffi::glGenBuffers(1, &mut buffers.index);
                ffi::glBindBuffer(ffi::GL_ELEMENT_ARRAY_BUFFER, buffers.index);
                ffi::glBufferData(
                    ffi::GL_ELEMENT_ARRAY_BUFFER,
                    std::mem::size_of_val(mesh.indices()) as isize,
                    mesh.indices().as_ptr().cast(),
                    ffi::GL_STATIC_DRAW,
                );
            }
            renderer.buffers.push(buffers);
        }
        check_error("上传 Neocari 模型")?;
        Ok(renderer)
    }

    fn ensure_mask(&mut self, width: i32, height: i32) -> Result<(), String> {
        let limit = self.max_texture_size.clamp(1, 2048) as f32;
        let scale = (limit / width.max(height) as f32).min(1.0);
        let size = (
            ((width as f32 * scale) as i32).max(1),
            ((height as f32 * scale) as i32).max(1),
        );
        if self.mask_size == size {
            return Ok(());
        }
        unsafe {
            ffi::glActiveTexture(ffi::GL_TEXTURE0);
            if self.mask == 0 {
                ffi::glGenTextures(1, &mut self.mask);
            }
            if self.framebuffer == 0 {
                ffi::glGenFramebuffers(1, &mut self.framebuffer);
            }
        }
        upload_texture(self.mask, size.0, size.1, ptr::null());
        unsafe {
            ffi::glBindFramebuffer(ffi::GL_FRAMEBUFFER, self.framebuffer);
            ffi::glFramebufferTexture2D(
                ffi::GL_FRAMEBUFFER,
                ffi::GL_COLOR_ATTACHMENT0,
                ffi::GL_TEXTURE_2D,
                self.mask,
                0,
            );
            let status = ffi::glCheckFramebufferStatus(ffi::GL_FRAMEBUFFER);
            ffi::glBindFramebuffer(ffi::GL_FRAMEBUFFER, 0);
            if status != ffi::GL_FRAMEBUFFER_COMPLETE {
                return Err(format!("Neocari 裁剪缓冲创建失败: {status:#x}"));
            }
        }
        self.mask_size = size;
        Ok(())
    }

    fn draw_mesh(&self, index: usize, mesh: &Moc3DrawableMesh, mask_mode: i32) {
        let buffer = &self.buffers[index];
        let [r, g, b] = mesh.multiply_color();
        let [sr, sg, sb] = mesh.screen_color();
        unsafe {
            ffi::glUniform1i(self.mode, mask_mode);
            ffi::glUniform1f(self.opacity, mesh.opacity());
            ffi::glUniform3f(self.multiply, r, g, b);
            ffi::glUniform3f(self.screen, sr, sg, sb);
            ffi::glActiveTexture(ffi::GL_TEXTURE0);
            ffi::glBindTexture(
                ffi::GL_TEXTURE_2D,
                self.textures[mesh.texture_index() as usize],
            );
            ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, buffer.vertex);
            ffi::glBindBuffer(ffi::GL_ELEMENT_ARRAY_BUFFER, buffer.index);
            ffi::glVertexAttribPointer(self.position as u32, 2, ffi::GL_FLOAT, 0, 16, ptr::null());
            ffi::glVertexAttribPointer(
                self.uv as u32,
                2,
                ffi::GL_FLOAT,
                0,
                16,
                8_usize as *const c_void,
            );
            ffi::glDrawElements(
                ffi::GL_TRIANGLES,
                buffer.count,
                ffi::GL_UNSIGNED_SHORT,
                ptr::null(),
            );
        }
    }

    pub fn draw(
        &mut self,
        meshes: &[Moc3DrawableMesh],
        transform: [f32; 4],
        width: i32,
        height: i32,
    ) -> Result<(), String> {
        self.ensure_mask(width, height)?;
        // Upload animated vertices once; mask and color passes share the buffers.
        for (mesh, buffer) in meshes.iter().zip(&self.buffers) {
            self.vertices.clear();
            for vertex in mesh.vertices() {
                self.vertices.extend_from_slice(&vertex.position());
                self.vertices.extend_from_slice(&vertex.uv());
            }
            unsafe {
                ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, buffer.vertex);
                ffi::glBufferData(
                    ffi::GL_ARRAY_BUFFER,
                    (self.vertices.len() * 4) as isize,
                    self.vertices.as_ptr().cast(),
                    ffi::GL_DYNAMIC_DRAW,
                );
            }
        }
        unsafe {
            ffi::glDisable(ffi::GL_DEPTH_TEST);
            ffi::glDisable(ffi::GL_CULL_FACE);
            ffi::glDisable(ffi::GL_STENCIL_TEST);
            ffi::glEnable(ffi::GL_BLEND);
            ffi::glUseProgram(self.program);
            ffi::glUniform4f(
                self.transform,
                transform[0],
                transform[1],
                transform[2],
                transform[3],
            );
            ffi::glEnableVertexAttribArray(self.position as u32);
            ffi::glEnableVertexAttribArray(self.uv as u32);
            ffi::glActiveTexture(ffi::GL_TEXTURE0 + 1);
            ffi::glBindTexture(ffi::GL_TEXTURE_2D, self.white);
        }
        let infos: Vec<_> = meshes.iter().map(DrawableInfo::from_mesh).collect();
        let mut last_masks = Vec::new();
        for index in draw_order_indices(&infos) {
            let mesh = &meshes[index];
            if !infos[index].is_visible() {
                continue;
            }
            if !mesh.masks().is_empty() && mesh.masks() != last_masks {
                unsafe {
                    // Never bind the texture currently attached for rendering.
                    ffi::glActiveTexture(ffi::GL_TEXTURE0 + 1);
                    ffi::glBindTexture(ffi::GL_TEXTURE_2D, self.white);
                    ffi::glBindFramebuffer(ffi::GL_FRAMEBUFFER, self.framebuffer);
                    ffi::glViewport(0, 0, self.mask_size.0, self.mask_size.1);
                    ffi::glClearColor(0.0, 0.0, 0.0, 0.0);
                    ffi::glClear(ffi::GL_COLOR_BUFFER_BIT);
                    ffi::glBlendFunc(ffi::GL_ONE, ffi::GL_ONE_MINUS_SRC_ALPHA);
                }
                for mask in mesh.masks() {
                    if let Some(mask_mesh) = meshes.get(*mask as usize) {
                        // As in Neocari's mask shader, the mask's texture alpha
                        // defines coverage independently of drawable opacity.
                        self.draw_mesh(*mask as usize, mask_mesh, 3);
                    }
                }
                unsafe {
                    ffi::glBindFramebuffer(ffi::GL_FRAMEBUFFER, 0);
                    ffi::glViewport(0, 0, width, height);
                }
                last_masks.clear();
                last_masks.extend_from_slice(mesh.masks());
            }
            unsafe {
                ffi::glActiveTexture(ffi::GL_TEXTURE0 + 1);
                ffi::glBindTexture(
                    ffi::GL_TEXTURE_2D,
                    if mesh.masks().is_empty() {
                        self.white
                    } else {
                        self.mask
                    },
                );
                let (src, dst, src_alpha, dst_alpha) = match mesh.blend_mode() {
                    Moc3DrawableBlendMode::Normal => (
                        ffi::GL_ONE,
                        ffi::GL_ONE_MINUS_SRC_ALPHA,
                        ffi::GL_ONE,
                        ffi::GL_ONE_MINUS_SRC_ALPHA,
                    ),
                    Moc3DrawableBlendMode::Additive => {
                        (ffi::GL_ONE, ffi::GL_ONE, ffi::GL_ZERO, ffi::GL_ONE)
                    }
                    Moc3DrawableBlendMode::Multiplicative => (
                        ffi::GL_DST_COLOR,
                        ffi::GL_ONE_MINUS_SRC_ALPHA,
                        ffi::GL_ZERO,
                        ffi::GL_ONE,
                    ),
                    Moc3DrawableBlendMode::Screen => (
                        ffi::GL_ONE,
                        ffi::GL_ONE_MINUS_SRC_COLOR,
                        ffi::GL_ZERO,
                        ffi::GL_ONE,
                    ),
                };
                ffi::glBlendFuncSeparate(src, dst, src_alpha, dst_alpha);
            }
            let mode = if mesh.masks().is_empty() {
                0
            } else if mesh.is_inverted_mask() {
                2
            } else {
                1
            };
            self.draw_mesh(index, mesh, mode);
        }
        unsafe {
            ffi::glDisableVertexAttribArray(self.position as u32);
            ffi::glDisableVertexAttribArray(self.uv as u32);
            ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, 0);
            ffi::glBindBuffer(ffi::GL_ELEMENT_ARRAY_BUFFER, 0);
            ffi::glActiveTexture(ffi::GL_TEXTURE0);
        }
        check_error("绘制 Neocari 模型")
    }
}

fn upload_texture(id: u32, width: i32, height: i32, pixels: *const c_void) {
    unsafe {
        ffi::glBindTexture(ffi::GL_TEXTURE_2D, id);
        for name in [ffi::GL_TEXTURE_MIN_FILTER, ffi::GL_TEXTURE_MAG_FILTER] {
            ffi::glTexParameteri(ffi::GL_TEXTURE_2D, name, ffi::GL_LINEAR);
        }
        for name in [ffi::GL_TEXTURE_WRAP_S, ffi::GL_TEXTURE_WRAP_T] {
            ffi::glTexParameteri(ffi::GL_TEXTURE_2D, name, ffi::GL_CLAMP_TO_EDGE);
        }
        ffi::glTexImage2D(
            ffi::GL_TEXTURE_2D,
            0,
            ffi::GL_RGBA as i32,
            width,
            height,
            0,
            ffi::GL_RGBA,
            ffi::GL_UNSIGNED_BYTE,
            pixels,
        );
    }
}

fn check_error(operation: &str) -> Result<(), String> {
    let error = unsafe { ffi::glGetError() };
    if error == ffi::GL_NO_ERROR {
        Ok(())
    } else {
        Err(format!("{operation} 失败: OpenGL ES {error:#x}"))
    }
}

impl Drop for ModelRenderer {
    fn drop(&mut self) {
        unsafe {
            ffi::glDeleteProgram(self.program);
            ffi::glDeleteTextures(self.textures.len() as i32, self.textures.as_ptr());
            ffi::glDeleteTextures(1, &self.mask);
            ffi::glDeleteTextures(1, &self.white);
            ffi::glDeleteFramebuffers(1, &self.framebuffer);
        }
    }
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use neocari::moc3::Moc3DrawableVertex;
    static EGL_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[link(name = "EGL")]
    unsafe extern "C" {
        fn eglCreatePbufferSurface(
            display: ffi::EGLDisplay,
            config: ffi::EGLConfig,
            attrs: *const i32,
        ) -> ffi::EGLSurface;
    }
    #[link(name = "GLESv2")]
    unsafe extern "C" {
        fn glReadPixels(
            x: i32,
            y: i32,
            width: i32,
            height: i32,
            format: u32,
            ty: u32,
            data: *mut c_void,
        );
    }

    struct Context {
        display: ffi::EGLDisplay,
        surface: ffi::EGLSurface,
        context: ffi::EGLContext,
    }

    impl Context {
        fn new() -> Self {
            unsafe {
                let display = ffi::eglGetDisplay(ptr::null_mut());
                assert_eq!(
                    ffi::eglInitialize(display, ptr::null_mut(), ptr::null_mut()),
                    ffi::EGL_TRUE
                );
                assert_eq!(ffi::eglBindAPI(ffi::EGL_OPENGL_ES_API), ffi::EGL_TRUE);
                let attrs = [
                    ffi::EGL_SURFACE_TYPE,
                    1,
                    ffi::EGL_RENDERABLE_TYPE,
                    ffi::EGL_OPENGL_ES2_BIT,
                    ffi::EGL_RED_SIZE,
                    8,
                    ffi::EGL_GREEN_SIZE,
                    8,
                    ffi::EGL_BLUE_SIZE,
                    8,
                    ffi::EGL_ALPHA_SIZE,
                    8,
                    ffi::EGL_NONE,
                ];
                let mut config = ptr::null_mut();
                let mut count = 0;
                assert_eq!(
                    ffi::eglChooseConfig(display, attrs.as_ptr(), &mut config, 1, &mut count),
                    ffi::EGL_TRUE
                );
                assert_eq!(count, 1);
                let surface = eglCreatePbufferSurface(
                    display,
                    config,
                    [0x3057, 64, 0x3056, 64, ffi::EGL_NONE].as_ptr(),
                );
                let context = ffi::eglCreateContext(
                    display,
                    config,
                    ptr::null_mut(),
                    [ffi::EGL_CONTEXT_CLIENT_VERSION, 2, ffi::EGL_NONE].as_ptr(),
                );
                assert!(!surface.is_null() && !context.is_null());
                assert_eq!(
                    ffi::eglMakeCurrent(display, surface, surface, context),
                    ffi::EGL_TRUE
                );
                Self {
                    display,
                    surface,
                    context,
                }
            }
        }
    }

    impl Drop for Context {
        fn drop(&mut self) {
            unsafe {
                ffi::eglMakeCurrent(
                    self.display,
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                );
                ffi::eglDestroyContext(self.display, self.context);
                ffi::eglDestroySurface(self.display, self.surface);
                ffi::eglTerminate(self.display);
            }
        }
    }

    fn quad(
        texture: i32,
        flags: u8,
        opacity: f32,
        order: f32,
        masks: Vec<i32>,
    ) -> Moc3DrawableMesh {
        let vertices = [
            ([-1.0, -1.0], [0.0, 0.0]),
            ([1.0, -1.0], [1.0, 0.0]),
            ([-1.0, 1.0], [0.0, 1.0]),
            ([1.0, 1.0], [1.0, 1.0]),
        ]
        .map(|(p, uv)| Moc3DrawableVertex::new(p, uv))
        .to_vec();
        Moc3DrawableMesh::from_parts(
            texture,
            flags,
            opacity,
            order,
            vertices,
            vec![0, 1, 2, 2, 1, 3],
            masks,
        )
    }

    fn clear(blue: f32) {
        unsafe {
            ffi::glBindFramebuffer(ffi::GL_FRAMEBUFFER, 0);
            ffi::glViewport(0, 0, 64, 64);
            ffi::glClearColor(0.0, 0.0, blue, blue);
            ffi::glClear(ffi::GL_COLOR_BUFFER_BIT);
        }
    }

    fn pixel(x: i32, y: i32) -> [u8; 4] {
        let mut pixel = [0_u8; 4];
        unsafe {
            glReadPixels(
                x,
                y,
                1,
                1,
                ffi::GL_RGBA,
                ffi::GL_UNSIGNED_BYTE,
                pixel.as_mut_ptr().cast(),
            );
        }
        pixel
    }

    fn near(actual: [u8; 4], expected: [u8; 4]) {
        for (a, e) in actual.into_iter().zip(expected) {
            assert!(
                a.abs_diff(e) <= 2,
                "pixel {actual:?}, expected {expected:?}"
            );
        }
    }

    #[test]
    fn gles_pixels_cover_blends_order_soft_masks_inversion_and_resize() {
        let _guard = EGL_TEST_LOCK.lock().unwrap();
        let _context = Context::new();
        // Premultiplied alpha output and all four Neocari blend modes.
        for (flags, blue, expected) in [
            (0, 0.0, [128, 0, 0, 128]),
            (0, 1.0, [128, 0, 127, 255]),
            (1, 1.0, [128, 0, 255, 255]),
            (2, 1.0, [0, 0, 127, 255]),
            (4, 1.0, [128, 0, 255, 255]),
        ] {
            let meshes = [quad(0, flags, 1.0, 0.0, vec![])];
            let mut renderer = ModelRenderer::new(
                vec![DecodedTexture::new(1, 1, vec![255, 0, 0, 128])],
                &meshes,
            )
            .unwrap();
            clear(blue);
            renderer
                .draw(&meshes, [1.0, 1.0, 0.0, 0.0], 64, 64)
                .unwrap();
            near(pixel(32, 32), expected);
        }
        for inverted in [false, true] {
            let meshes = [
                quad(0, 0, 0.0, 0.0, vec![]),
                quad(1, if inverted { 8 } else { 0 }, 1.0, 1.0, vec![0]),
            ];
            let mut renderer = ModelRenderer::new(
                vec![
                    DecodedTexture::new(2, 1, vec![255, 255, 255, 0, 255, 255, 255, 255]),
                    DecodedTexture::new(1, 1, vec![0, 255, 0, 255]),
                ],
                &meshes,
            )
            .unwrap();
            clear(0.0);
            renderer
                .draw(&meshes, [1.0, 1.0, 0.0, 0.0], 64, 64)
                .unwrap();
            let (left, right) = if inverted { (255, 0) } else { (0, 255) };
            near(pixel(8, 32), [0, left, 0, left]);
            near(pixel(56, 32), [0, right, 0, right]);
            let center = pixel(32, 32);
            assert!(center[3] > 100 && center[3] < 160, "soft edge {center:?}");
            // Recreate the mask buffer after a surface resize and render again.
            renderer.ensure_mask(32, 32).unwrap();
            clear(0.0);
            renderer
                .draw(&meshes, [1.0, 1.0, 0.0, 0.0], 64, 64)
                .unwrap();
            near(pixel(56, 32), [0, right, 0, right]);
        }
        let meshes = [quad(0, 0, 1.0, 10.0, vec![]), quad(1, 0, 1.0, 0.0, vec![])];
        let mut renderer = ModelRenderer::new(
            vec![
                DecodedTexture::new(1, 1, vec![255, 0, 0, 255]),
                DecodedTexture::new(1, 1, vec![0, 255, 0, 255]),
            ],
            &meshes,
        )
        .unwrap();
        clear(0.0);
        renderer
            .draw(&meshes, [1.0, 1.0, 0.0, 0.0], 64, 64)
            .unwrap();
        near(pixel(32, 32), [255, 0, 0, 255]);
    }

    #[test]
    #[ignore = "requires Neocari sample assets; exercised by GitHub Actions"]
    fn moc3_archive_matches_filesystem_animation_and_renders_visible_pixels() {
        check_model("NEOCARI_TEST_MODEL", "TapBody", None);
    }

    #[test]
    #[ignore = "requires pinned MOC2 model data; exercised by GitHub Actions"]
    fn moc2_archive_matches_filesystem_motion_expression_and_lip_sync() {
        check_model("NEOCARI_TEST_MODEL2", "smile", Some("angry01.exp"));
    }

    fn check_model(variable: &str, action: &str, expression: Option<&str>) {
        use crate::{framing, model::PetModel};
        use std::{collections::HashMap, path::Path};

        fn collect(dir: &Path, root: &Path, files: &mut HashMap<String, Vec<u8>>) {
            for entry in std::fs::read_dir(dir).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() {
                    collect(&path, root, files);
                } else {
                    let relative = path
                        .strip_prefix(root)
                        .unwrap()
                        .to_string_lossy()
                        .replace('\\', "/");
                    files.insert(
                        format!("archive://测试角色/服装/{relative}"),
                        std::fs::read(path).unwrap(),
                    );
                }
            }
        }
        let _guard = EGL_TEST_LOCK.lock().unwrap();
        let _context = Context::new();
        let path = std::env::var(variable).expect("CI must provide the pinned sample model");
        let path = Path::new(&path);
        let mut files = HashMap::new();
        collect(path.parent().unwrap(), path.parent().unwrap(), &mut files);
        let virtual_path = format!(
            "archive://测试角色/服装/{}",
            path.file_name().unwrap().to_string_lossy()
        );
        let mut archive = PetModel::load(&virtual_path, files).unwrap();
        let mut disk = PetModel::load(path.to_str().unwrap(), HashMap::new()).unwrap();
        assert_eq!(archive.meshes(), disk.meshes());
        assert_eq!(archive.take_textures(), disk.take_textures());
        // Reload for GPU upload; CPU pixels are transferred once.
        let mut disk = PetModel::load(path.to_str().unwrap(), HashMap::new()).unwrap();
        let mut renderer = ModelRenderer::new(disk.take_textures(), disk.meshes()).unwrap();
        for frame in 0..90 {
            if frame == 15 {
                archive.action(&format!("[{action}]")).unwrap();
                disk.action(&format!("[{action}]")).unwrap();
            }
            if frame == 30 {
                if let Some(expression) = expression {
                    archive.action(expression).unwrap();
                    disk.action(expression).unwrap();
                }
            }
            archive.look_at(0.7, 0.3);
            disk.look_at(0.7, 0.3);
            archive.update(1.0 / 60.0, (0.6, -0.2)).unwrap();
            disk.update(1.0 / 60.0, (0.6, -0.2)).unwrap();
            assert_eq!(
                archive.meshes(),
                disk.meshes(),
                "archive playback diverged at frame {frame}"
            );
        }
        let matrix = framing::transform(disk.bounds(), disk.flips_y(), 64, 64, (0.0, 0.0, 1.0));
        clear(0.0);
        renderer.draw(disk.meshes(), matrix, 64, 64).unwrap();
        let mut rgba = vec![0_u8; 64 * 64 * 4];
        unsafe {
            glReadPixels(
                0,
                0,
                64,
                64,
                ffi::GL_RGBA,
                ffi::GL_UNSIGNED_BYTE,
                rgba.as_mut_ptr().cast(),
            );
        }
        assert!(
            rgba.as_chunks::<4>()
                .0
                .iter()
                .filter(|pixel| pixel[3] > 0)
                .count()
                > 100
        );
    }
}
