use std::ffi::CString;
use std::os::raw::c_void;
use std::ptr;

use crate::ffi;

const BACKGROUND_VERTEX_SHADER: &str = r#"
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"#;

const BACKGROUND_FRAGMENT_SHADER: &str = r#"
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTexCoord;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    gl_FragColor = color.bgra;
}
"#;

const FPS_VERTEX_SHADER: &str = r#"
attribute vec2 aPosition;
uniform vec2 uOffset;
void main() {
    gl_Position = vec4(aPosition + uOffset, 0.0, 1.0);
}
"#;

const FPS_FRAGMENT_SHADER: &str = r#"
precision mediump float;
uniform vec4 uColor;
void main() {
    gl_FragColor = uColor;
}
"#;

fn shader_log(shader: ffi::GLuint) -> String {
    let mut log = [0; 512];
    unsafe {
        ffi::glGetShaderInfoLog(
            shader,
            log.len() as ffi::GLsizei,
            ptr::null_mut(),
            log.as_mut_ptr(),
        );
    }
    let length = log.iter().position(|byte| *byte == 0).unwrap_or(log.len());
    let bytes = unsafe { std::slice::from_raw_parts(log.as_ptr().cast::<u8>(), length) };
    String::from_utf8_lossy(bytes).into_owned()
}

fn program_log(program: ffi::GLuint) -> String {
    let mut log = [0; 512];
    unsafe {
        ffi::glGetProgramInfoLog(
            program,
            log.len() as ffi::GLsizei,
            ptr::null_mut(),
            log.as_mut_ptr(),
        );
    }
    let length = log.iter().position(|byte| *byte == 0).unwrap_or(log.len());
    let bytes = unsafe { std::slice::from_raw_parts(log.as_ptr().cast::<u8>(), length) };
    String::from_utf8_lossy(bytes).into_owned()
}

fn compile_shader(
    shader_type: ffi::GLenum,
    source: &str,
    label: &str,
) -> Result<ffi::GLuint, String> {
    let source = CString::new(source).map_err(|_| format!("{label} shader 包含 NUL 字节"))?;
    let shader = unsafe { ffi::glCreateShader(shader_type) };
    if shader == 0 {
        return Err(format!("{label} shader 创建失败"));
    }

    let source_pointer = source.as_ptr();
    unsafe {
        ffi::glShaderSource(shader, 1, &source_pointer, ptr::null());
        ffi::glCompileShader(shader);
    }
    let mut compiled = ffi::GL_FALSE;
    unsafe { ffi::glGetShaderiv(shader, ffi::GL_COMPILE_STATUS, &mut compiled) };
    if compiled != ffi::GL_TRUE {
        let error = format!("{label} shader 编译失败: {}", shader_log(shader));
        unsafe { ffi::glDeleteShader(shader) };
        return Err(error);
    }
    Ok(shader)
}

fn link_program(
    vertex_source: &str,
    fragment_source: &str,
    label: &str,
) -> Result<ffi::GLuint, String> {
    let vertex = compile_shader(ffi::GL_VERTEX_SHADER, vertex_source, label)?;
    let fragment = match compile_shader(ffi::GL_FRAGMENT_SHADER, fragment_source, label) {
        Ok(shader) => shader,
        Err(error) => {
            unsafe { ffi::glDeleteShader(vertex) };
            return Err(error);
        }
    };

    let program = unsafe { ffi::glCreateProgram() };
    unsafe {
        ffi::glAttachShader(program, vertex);
        ffi::glAttachShader(program, fragment);
        ffi::glLinkProgram(program);
        ffi::glDeleteShader(vertex);
        ffi::glDeleteShader(fragment);
    }

    let mut linked = ffi::GL_FALSE;
    unsafe { ffi::glGetProgramiv(program, ffi::GL_LINK_STATUS, &mut linked) };
    if linked != ffi::GL_TRUE {
        let error = format!("{label} shader 链接失败: {}", program_log(program));
        unsafe { ffi::glDeleteProgram(program) };
        return Err(error);
    }
    Ok(program)
}

#[derive(Default)]
pub struct BackgroundRenderer {
    texture: ffi::GLuint,
    program: ffi::GLuint,
    buffer: ffi::GLuint,
    position_attribute: ffi::GLint,
    texcoord_attribute: ffi::GLint,
    sampler_uniform: ffi::GLint,
    width: i32,
    height: i32,
    enabled: bool,
}

impl BackgroundRenderer {
    fn ensure_program(&mut self) -> Result<(), String> {
        if self.program != 0 {
            return Ok(());
        }

        let program = link_program(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER, "背景")?;
        let position = unsafe { ffi::glGetAttribLocation(program, c"aPosition".as_ptr()) };
        let texcoord = unsafe { ffi::glGetAttribLocation(program, c"aTexCoord".as_ptr()) };
        let sampler = unsafe { ffi::glGetUniformLocation(program, c"uTexture".as_ptr()) };
        let mut buffer = 0;
        unsafe { ffi::glGenBuffers(1, &mut buffer) };
        if position < 0 || texcoord < 0 || sampler < 0 || buffer == 0 {
            if buffer != 0 {
                unsafe { ffi::glDeleteBuffers(1, &buffer) };
            }
            unsafe { ffi::glDeleteProgram(program) };
            return Err("背景 shader 属性初始化失败".to_owned());
        }

        self.program = program;
        self.position_attribute = position;
        self.texcoord_attribute = texcoord;
        self.sampler_uniform = sampler;
        self.buffer = buffer;
        Ok(())
    }

    pub fn upload(&mut self, pixels: &[u32], width: i32, height: i32) {
        if pixels.is_empty() || width <= 0 || height <= 0 {
            if self.texture != 0 {
                unsafe { ffi::glDeleteTextures(1, &self.texture) };
                self.texture = 0;
            }
            self.enabled = false;
            self.width = 0;
            self.height = 0;
            return;
        }

        if self.texture == 0 {
            unsafe { ffi::glGenTextures(1, &mut self.texture) };
        }
        if self.texture == 0 {
            return;
        }

        unsafe {
            ffi::glBindTexture(ffi::GL_TEXTURE_2D, self.texture);
            ffi::glTexParameteri(
                ffi::GL_TEXTURE_2D,
                ffi::GL_TEXTURE_MIN_FILTER,
                ffi::GL_LINEAR,
            );
            ffi::glTexParameteri(
                ffi::GL_TEXTURE_2D,
                ffi::GL_TEXTURE_MAG_FILTER,
                ffi::GL_LINEAR,
            );
            ffi::glTexParameteri(
                ffi::GL_TEXTURE_2D,
                ffi::GL_TEXTURE_WRAP_S,
                ffi::GL_CLAMP_TO_EDGE,
            );
            ffi::glTexParameteri(
                ffi::GL_TEXTURE_2D,
                ffi::GL_TEXTURE_WRAP_T,
                ffi::GL_CLAMP_TO_EDGE,
            );
            ffi::glTexImage2D(
                ffi::GL_TEXTURE_2D,
                0,
                ffi::GL_RGBA as ffi::GLint,
                width,
                height,
                0,
                ffi::GL_RGBA,
                ffi::GL_UNSIGNED_BYTE,
                pixels.as_ptr().cast(),
            );
            ffi::glBindTexture(ffi::GL_TEXTURE_2D, 0);
        }
        self.enabled = true;
        self.width = width;
        self.height = height;
    }

    pub fn draw(&mut self, surface_width: i32, surface_height: i32) -> Result<(), String> {
        if !self.enabled || self.texture == 0 {
            return Ok(());
        }
        self.ensure_program()?;

        let surface_aspect = surface_width.max(1) as f32 / surface_height.max(1) as f32;
        let image_aspect = self.width.max(1) as f32 / self.height.max(1) as f32;
        let (mut u0, mut u1, mut v0, mut v1) = (0.0_f32, 1.0_f32, 0.0_f32, 1.0_f32);
        if image_aspect > surface_aspect {
            let visible_width = surface_aspect / image_aspect;
            let inset = (1.0 - visible_width) * 0.5;
            u0 = inset;
            u1 = 1.0 - inset;
        } else if image_aspect < surface_aspect {
            let visible_height = image_aspect / surface_aspect;
            let inset = (1.0 - visible_height) * 0.5;
            v0 = inset;
            v1 = 1.0 - inset;
        }

        let vertices = [
            -1.0_f32, -1.0, u0, v1, 1.0, -1.0, u1, v1, -1.0, 1.0, u0, v0, 1.0, 1.0, u1, v0,
        ];
        unsafe {
            ffi::glDisable(ffi::GL_DEPTH_TEST);
            ffi::glDisable(ffi::GL_CULL_FACE);
            ffi::glDisable(ffi::GL_BLEND);
            ffi::glUseProgram(self.program);
            ffi::glActiveTexture(ffi::GL_TEXTURE0);
            ffi::glBindTexture(ffi::GL_TEXTURE_2D, self.texture);
            ffi::glUniform1i(self.sampler_uniform, 0);
            ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, self.buffer);
            ffi::glBufferData(
                ffi::GL_ARRAY_BUFFER,
                std::mem::size_of_val(&vertices) as ffi::GLsizeiptr,
                vertices.as_ptr().cast(),
                ffi::GL_DYNAMIC_DRAW,
            );
            ffi::glEnableVertexAttribArray(self.position_attribute as ffi::GLuint);
            ffi::glEnableVertexAttribArray(self.texcoord_attribute as ffi::GLuint);
            ffi::glVertexAttribPointer(
                self.position_attribute as ffi::GLuint,
                2,
                ffi::GL_FLOAT,
                ffi::GL_FALSE as ffi::GLboolean,
                (4 * std::mem::size_of::<f32>()) as ffi::GLsizei,
                ptr::null(),
            );
            ffi::glVertexAttribPointer(
                self.texcoord_attribute as ffi::GLuint,
                2,
                ffi::GL_FLOAT,
                ffi::GL_FALSE as ffi::GLboolean,
                (4 * std::mem::size_of::<f32>()) as ffi::GLsizei,
                (2 * std::mem::size_of::<f32>()) as *const c_void,
            );
            ffi::glDrawArrays(ffi::GL_TRIANGLE_STRIP, 0, 4);
            ffi::glDisableVertexAttribArray(self.position_attribute as ffi::GLuint);
            ffi::glDisableVertexAttribArray(self.texcoord_attribute as ffi::GLuint);
            ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, 0);
            ffi::glBindTexture(ffi::GL_TEXTURE_2D, 0);
        }
        Ok(())
    }
}

impl Drop for BackgroundRenderer {
    fn drop(&mut self) {
        unsafe {
            if self.texture != 0 {
                ffi::glDeleteTextures(1, &self.texture);
            }
            if self.buffer != 0 {
                ffi::glDeleteBuffers(1, &self.buffer);
            }
            if self.program != 0 {
                ffi::glDeleteProgram(self.program);
            }
        }
    }
}

#[derive(Default)]
pub struct FpsRenderer {
    program: ffi::GLuint,
    buffer: ffi::GLuint,
    position_attribute: ffi::GLint,
    color_uniform: ffi::GLint,
    offset_uniform: ffi::GLint,
}

impl FpsRenderer {
    fn ensure_program(&mut self) -> Result<(), String> {
        if self.program != 0 {
            return Ok(());
        }
        let program = link_program(FPS_VERTEX_SHADER, FPS_FRAGMENT_SHADER, "FPS")?;
        let position = unsafe { ffi::glGetAttribLocation(program, c"aPosition".as_ptr()) };
        let color = unsafe { ffi::glGetUniformLocation(program, c"uColor".as_ptr()) };
        let offset = unsafe { ffi::glGetUniformLocation(program, c"uOffset".as_ptr()) };
        let mut buffer = 0;
        unsafe { ffi::glGenBuffers(1, &mut buffer) };
        if position < 0 || color < 0 || offset < 0 || buffer == 0 {
            if buffer != 0 {
                unsafe { ffi::glDeleteBuffers(1, &buffer) };
            }
            unsafe { ffi::glDeleteProgram(program) };
            return Err("FPS shader 属性初始化失败".to_owned());
        }

        self.program = program;
        self.position_attribute = position;
        self.color_uniform = color;
        self.offset_uniform = offset;
        self.buffer = buffer;
        Ok(())
    }

    pub fn draw(
        &mut self,
        enabled: bool,
        fps: i32,
        surface_width: i32,
        surface_height: i32,
    ) -> Result<(), String> {
        if !enabled {
            return Ok(());
        }
        self.ensure_program()?;

        let width = surface_width.max(1);
        let height = surface_height.max(1);
        let cell_size = (width as f32 / 46.0)
            .min(height as f32 / 32.0)
            .clamp(3.0, 7.0);
        let text = format!("FPS {}", fps.clamp(0, 999));
        let mut vertices = Vec::with_capacity(text.len() * 7 * 5 * 12);
        append_fps_text(&mut vertices, &text, width, height, cell_size);
        if vertices.is_empty() {
            return Ok(());
        }

        unsafe {
            ffi::glDisable(ffi::GL_DEPTH_TEST);
            ffi::glDisable(ffi::GL_CULL_FACE);
            ffi::glEnable(ffi::GL_BLEND);
            ffi::glBlendFunc(ffi::GL_SRC_ALPHA, ffi::GL_ONE_MINUS_SRC_ALPHA);
            ffi::glUseProgram(self.program);
            ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, self.buffer);
            ffi::glBufferData(
                ffi::GL_ARRAY_BUFFER,
                (vertices.len() * std::mem::size_of::<f32>()) as ffi::GLsizeiptr,
                vertices.as_ptr().cast(),
                ffi::GL_DYNAMIC_DRAW,
            );
            ffi::glEnableVertexAttribArray(self.position_attribute as ffi::GLuint);
            ffi::glVertexAttribPointer(
                self.position_attribute as ffi::GLuint,
                2,
                ffi::GL_FLOAT,
                ffi::GL_FALSE as ffi::GLboolean,
                (2 * std::mem::size_of::<f32>()) as ffi::GLsizei,
                ptr::null(),
            );
            ffi::glUniform2f(
                self.offset_uniform,
                3.0 / width as f32,
                -3.0 / height as f32,
            );
            ffi::glUniform4f(self.color_uniform, 0.0, 0.0, 0.0, 0.8);
            ffi::glDrawArrays(ffi::GL_TRIANGLES, 0, (vertices.len() / 2) as i32);
            ffi::glUniform2f(self.offset_uniform, 0.0, 0.0);
            ffi::glUniform4f(self.color_uniform, 1.0, 1.0, 1.0, 0.95);
            ffi::glDrawArrays(ffi::GL_TRIANGLES, 0, (vertices.len() / 2) as i32);
            ffi::glDisableVertexAttribArray(self.position_attribute as ffi::GLuint);
            ffi::glBindBuffer(ffi::GL_ARRAY_BUFFER, 0);
        }
        Ok(())
    }
}

impl Drop for FpsRenderer {
    fn drop(&mut self) {
        unsafe {
            if self.buffer != 0 {
                ffi::glDeleteBuffers(1, &self.buffer);
            }
            if self.program != 0 {
                ffi::glDeleteProgram(self.program);
            }
        }
    }
}

fn fps_glyph(character: char) -> Option<&'static [u8; 7]> {
    const F: [u8; 7] = [0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10];
    const P: [u8; 7] = [0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10];
    const S: [u8; 7] = [0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E];
    const ZERO: [u8; 7] = [0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E];
    const ONE: [u8; 7] = [0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E];
    const TWO: [u8; 7] = [0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F];
    const THREE: [u8; 7] = [0x1E, 0x01, 0x01, 0x0E, 0x01, 0x01, 0x1E];
    const FOUR: [u8; 7] = [0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02];
    const FIVE: [u8; 7] = [0x1F, 0x10, 0x10, 0x1E, 0x01, 0x01, 0x1E];
    const SIX: [u8; 7] = [0x0E, 0x10, 0x10, 0x1E, 0x11, 0x11, 0x0E];
    const SEVEN: [u8; 7] = [0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08];
    const EIGHT: [u8; 7] = [0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E];
    const NINE: [u8; 7] = [0x0E, 0x11, 0x11, 0x0F, 0x01, 0x01, 0x0E];

    match character {
        'F' => Some(&F),
        'P' => Some(&P),
        'S' => Some(&S),
        '0' => Some(&ZERO),
        '1' => Some(&ONE),
        '2' => Some(&TWO),
        '3' => Some(&THREE),
        '4' => Some(&FOUR),
        '5' => Some(&FIVE),
        '6' => Some(&SIX),
        '7' => Some(&SEVEN),
        '8' => Some(&EIGHT),
        '9' => Some(&NINE),
        _ => None,
    }
}

fn append_fps_text(
    vertices: &mut Vec<f32>,
    text: &str,
    surface_width: i32,
    surface_height: i32,
    cell_size: f32,
) {
    let pixel_to_ndc_x = 2.0 / surface_width.max(1) as f32;
    let pixel_to_ndc_y = 2.0 / surface_height.max(1) as f32;
    let margin = cell_size * 1.25;
    let gap = cell_size * 0.12;
    let mut origin_x = margin;
    let origin_y = margin;

    for character in text.chars() {
        if let Some(glyph) = fps_glyph(character) {
            for (row, bits) in glyph.iter().copied().enumerate() {
                for column in 0..5 {
                    if bits & (1 << (4 - column)) == 0 {
                        continue;
                    }
                    let x0 = -1.0 + (origin_x + column as f32 * cell_size) * pixel_to_ndc_x;
                    let x1 =
                        -1.0 + (origin_x + (column + 1) as f32 * cell_size - gap) * pixel_to_ndc_x;
                    let y0 = 1.0 - (origin_y + row as f32 * cell_size) * pixel_to_ndc_y;
                    let y1 = 1.0 - (origin_y + (row + 1) as f32 * cell_size - gap) * pixel_to_ndc_y;
                    vertices.extend_from_slice(&[x0, y0, x1, y0, x0, y1, x1, y0, x1, y1, x0, y1]);
                }
            }
        }
        origin_x += cell_size * 6.0;
    }
}
