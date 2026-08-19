use std::ffi::CString;
use std::os::raw::{c_char, c_int, c_uint, c_void};
use std::ptr;

use jni::sys::{JNIEnv, jobject};

pub enum ANativeWindow {}

pub type EGLBoolean = c_uint;
pub type EGLConfig = *mut c_void;
pub type EGLContext = *mut c_void;
pub type EGLDisplay = *mut c_void;
pub type EGLSurface = *mut c_void;
pub type EGLint = c_int;

pub type GLboolean = u8;
pub type GLenum = c_uint;
pub type GLfloat = f32;
pub type GLint = c_int;
pub type GLsizei = c_int;
pub type GLsizeiptr = isize;
pub type GLuint = c_uint;

pub const EGL_FALSE: EGLBoolean = 0;
pub const EGL_TRUE: EGLBoolean = 1;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_STENCIL_SIZE: EGLint = 0x3026;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_OPENGL_ES2_BIT: EGLint = 0x0004;
pub const EGL_OPENGL_ES_API: EGLint = 0x30A0;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;

pub const GL_FALSE: GLint = 0;
pub const GL_TRUE: GLint = 1;
pub const GL_FLOAT: GLenum = 0x1406;
pub const GL_UNSIGNED_BYTE: GLenum = 0x1401;
pub const GL_TRIANGLES: GLenum = 0x0004;
pub const GL_TRIANGLE_STRIP: GLenum = 0x0005;
pub const GL_DEPTH_TEST: GLenum = 0x0B71;
pub const GL_BLEND: GLenum = 0x0BE2;
pub const GL_CULL_FACE: GLenum = 0x0B44;
pub const GL_SRC_ALPHA: GLenum = 0x0302;
pub const GL_ONE_MINUS_SRC_ALPHA: GLenum = 0x0303;
pub const GL_TEXTURE_2D: GLenum = 0x0DE1;
pub const GL_TEXTURE0: GLenum = 0x84C0;
pub const GL_TEXTURE_MAG_FILTER: GLenum = 0x2800;
pub const GL_TEXTURE_MIN_FILTER: GLenum = 0x2801;
pub const GL_TEXTURE_WRAP_S: GLenum = 0x2802;
pub const GL_TEXTURE_WRAP_T: GLenum = 0x2803;
pub const GL_LINEAR: GLint = 0x2601;
pub const GL_CLAMP_TO_EDGE: GLint = 0x812F;
pub const GL_RGBA: GLenum = 0x1908;
pub const GL_ARRAY_BUFFER: GLenum = 0x8892;
pub const GL_DYNAMIC_DRAW: GLenum = 0x88E8;
pub const GL_VERTEX_SHADER: GLenum = 0x8B31;
pub const GL_FRAGMENT_SHADER: GLenum = 0x8B30;
pub const GL_COMPILE_STATUS: GLenum = 0x8B81;
pub const GL_LINK_STATUS: GLenum = 0x8B82;

const ANDROID_LOG_ERROR: c_int = 6;
const WINDOW_FORMAT_RGBA_8888: c_int = 1;

#[link(name = "android")]
unsafe extern "C" {
    fn ANativeWindow_fromSurface(env: *mut JNIEnv, surface: jobject) -> *mut ANativeWindow;
    fn ANativeWindow_release(window: *mut ANativeWindow);
    fn ANativeWindow_setBuffersGeometry(
        window: *mut ANativeWindow,
        width: c_int,
        height: c_int,
        format: c_int,
    ) -> c_int;
}

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

#[link(name = "EGL")]
unsafe extern "C" {
    pub fn eglGetDisplay(native_display: *mut c_void) -> EGLDisplay;
    pub fn eglInitialize(display: EGLDisplay, major: *mut EGLint, minor: *mut EGLint)
    -> EGLBoolean;
    pub fn eglBindAPI(api: EGLint) -> EGLBoolean;
    pub fn eglChooseConfig(
        display: EGLDisplay,
        attributes: *const EGLint,
        configs: *mut EGLConfig,
        config_size: EGLint,
        config_count: *mut EGLint,
    ) -> EGLBoolean;
    pub fn eglCreateWindowSurface(
        display: EGLDisplay,
        config: EGLConfig,
        window: *mut ANativeWindow,
        attributes: *const EGLint,
    ) -> EGLSurface;
    pub fn eglCreateContext(
        display: EGLDisplay,
        config: EGLConfig,
        shared_context: EGLContext,
        attributes: *const EGLint,
    ) -> EGLContext;
    pub fn eglMakeCurrent(
        display: EGLDisplay,
        draw: EGLSurface,
        read: EGLSurface,
        context: EGLContext,
    ) -> EGLBoolean;
    pub fn eglSwapInterval(display: EGLDisplay, interval: EGLint) -> EGLBoolean;
    pub fn eglSwapBuffers(display: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglDestroyContext(display: EGLDisplay, context: EGLContext) -> EGLBoolean;
    pub fn eglDestroySurface(display: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglTerminate(display: EGLDisplay) -> EGLBoolean;
}

#[link(name = "GLESv2")]
unsafe extern "C" {
    pub fn glCreateShader(shader_type: GLenum) -> GLuint;
    pub fn glShaderSource(
        shader: GLuint,
        count: GLsizei,
        source: *const *const c_char,
        length: *const GLint,
    );
    pub fn glCompileShader(shader: GLuint);
    pub fn glGetShaderiv(shader: GLuint, parameter: GLenum, value: *mut GLint);
    pub fn glGetShaderInfoLog(
        shader: GLuint,
        buffer_size: GLsizei,
        length: *mut GLsizei,
        log: *mut c_char,
    );
    pub fn glDeleteShader(shader: GLuint);
    pub fn glCreateProgram() -> GLuint;
    pub fn glAttachShader(program: GLuint, shader: GLuint);
    pub fn glLinkProgram(program: GLuint);
    pub fn glGetProgramiv(program: GLuint, parameter: GLenum, value: *mut GLint);
    pub fn glGetProgramInfoLog(
        program: GLuint,
        buffer_size: GLsizei,
        length: *mut GLsizei,
        log: *mut c_char,
    );
    pub fn glDeleteProgram(program: GLuint);
    pub fn glGetAttribLocation(program: GLuint, name: *const c_char) -> GLint;
    pub fn glGetUniformLocation(program: GLuint, name: *const c_char) -> GLint;
    pub fn glGenBuffers(count: GLsizei, buffers: *mut GLuint);
    pub fn glDeleteBuffers(count: GLsizei, buffers: *const GLuint);
    pub fn glBindBuffer(target: GLenum, buffer: GLuint);
    pub fn glBufferData(target: GLenum, size: GLsizeiptr, data: *const c_void, usage: GLenum);
    pub fn glGenTextures(count: GLsizei, textures: *mut GLuint);
    pub fn glDeleteTextures(count: GLsizei, textures: *const GLuint);
    pub fn glBindTexture(target: GLenum, texture: GLuint);
    pub fn glTexParameteri(target: GLenum, parameter: GLenum, value: GLint);
    pub fn glTexImage2D(
        target: GLenum,
        level: GLint,
        internal_format: GLint,
        width: GLsizei,
        height: GLsizei,
        border: GLint,
        format: GLenum,
        pixel_type: GLenum,
        pixels: *const c_void,
    );
    pub fn glDisable(capability: GLenum);
    pub fn glEnable(capability: GLenum);
    pub fn glUseProgram(program: GLuint);
    pub fn glActiveTexture(texture: GLenum);
    pub fn glUniform1i(location: GLint, value: GLint);
    pub fn glUniform2f(location: GLint, x: GLfloat, y: GLfloat);
    pub fn glUniform4f(location: GLint, x: GLfloat, y: GLfloat, z: GLfloat, w: GLfloat);
    pub fn glEnableVertexAttribArray(index: GLuint);
    pub fn glDisableVertexAttribArray(index: GLuint);
    pub fn glVertexAttribPointer(
        index: GLuint,
        size: GLint,
        value_type: GLenum,
        normalized: GLboolean,
        stride: GLsizei,
        pointer: *const c_void,
    );
    pub fn glDrawArrays(mode: GLenum, first: GLint, count: GLsizei);
    pub fn glBlendFunc(source: GLenum, destination: GLenum);
}

pub struct NativeWindow(*mut ANativeWindow);

// The Android NDK grants the native window its own reference. JNI callers may update its
// buffer geometry while the render thread owns EGL; the last Arc releases it after shutdown.
unsafe impl Send for NativeWindow {}
unsafe impl Sync for NativeWindow {}

impl NativeWindow {
    pub unsafe fn from_surface(env: *mut JNIEnv, surface: jobject) -> Result<Self, String> {
        let window = unsafe { ANativeWindow_fromSurface(env, surface) };
        if window.is_null() {
            Err("无法从 Surface 获取 ANativeWindow".to_owned())
        } else {
            Ok(Self(window))
        }
    }

    pub fn as_ptr(&self) -> *mut ANativeWindow {
        self.0
    }

    pub fn set_buffers_geometry(&self, width: i32, height: i32) {
        unsafe {
            ANativeWindow_setBuffersGeometry(
                self.0,
                width.max(1),
                height.max(1),
                WINDOW_FORMAT_RGBA_8888,
            );
        }
    }
}

impl Drop for NativeWindow {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { ANativeWindow_release(self.0) };
            self.0 = ptr::null_mut();
        }
    }
}

pub fn log_error(message: &str) {
    let tag = c"BandoriPet";
    let cleaned = message.replace('\0', "�");
    if let Ok(text) = CString::new(cleaned) {
        unsafe {
            __android_log_write(ANDROID_LOG_ERROR, tag.as_ptr(), text.as_ptr());
        }
    }
}
