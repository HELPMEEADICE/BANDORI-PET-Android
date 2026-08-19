use std::any::Any;
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::ffi;
use crate::lua::LuaRuntime;
use crate::overlays::{BackgroundRenderer, FpsRenderer};

const GAZE_FOLLOW_EASING_PER_SECOND: f32 = 8.0;

fn lock_unpoisoned<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn render_size(surface_width: i32, surface_height: i32, scale: f32) -> (i32, i32) {
    let width = surface_width.max(1);
    let height = surface_height.max(1);
    let scale = scale.clamp(0.5, 2.0);
    (
        ((width as f32 * scale).round() as i32).max(1),
        ((height as f32 * scale).round() as i32).max(1),
    )
}

struct ModelRequest {
    path: String,
    resources: HashMap<String, Vec<u8>>,
}

struct BackgroundRequest {
    pixels: Vec<u32>,
    width: i32,
    height: i32,
}

struct ControlState {
    running: bool,
    paused: bool,
    surface_width: i32,
    surface_height: i32,
    width: i32,
    height: i32,
    render_scale: f32,
    fps_limit: i32,
    vsync_enabled: bool,
    fps_display_enabled: bool,
    pending_resize: bool,
    pending_render_options: bool,
    pending_model: Option<ModelRequest>,
    pending_action: Option<String>,
    pending_background: Option<BackgroundRequest>,
    pending_touch: Option<(f32, f32)>,
    pending_look_at: bool,
    look_at: (f32, f32),
    pending_transform: bool,
    transform: (f32, f32, f32),
    mouth_open: f32,
    mouth_form: f32,
    last_error: String,
}

impl ControlState {
    fn new(
        surface_width: i32,
        surface_height: i32,
        fps_limit: i32,
        vsync_enabled: bool,
        render_scale: f32,
    ) -> Self {
        let surface_width = surface_width.max(1);
        let surface_height = surface_height.max(1);
        let render_scale = render_scale.clamp(0.5, 2.0);
        let (width, height) = render_size(surface_width, surface_height, render_scale);
        Self {
            running: true,
            paused: false,
            surface_width,
            surface_height,
            width,
            height,
            render_scale,
            fps_limit: if fps_limit > 0 { fps_limit } else { 60 },
            vsync_enabled,
            fps_display_enabled: false,
            pending_resize: false,
            pending_render_options: false,
            pending_model: None,
            pending_action: None,
            pending_background: None,
            pending_touch: None,
            pending_look_at: false,
            look_at: (0.5, 0.5),
            pending_transform: false,
            transform: (0.0, 0.0, 1.0),
            mouth_open: 0.0,
            mouth_form: 0.0,
            last_error: String::new(),
        }
    }

    fn take_frame_commands(&mut self) -> FrameCommands {
        let commands = FrameCommands {
            width: self.width,
            height: self.height,
            should_resize: self.pending_resize,
            should_update_render_options: self.pending_render_options,
            vsync_enabled: self.vsync_enabled,
            fps_display_enabled: self.fps_display_enabled,
            model: self.pending_model.take(),
            action: self.pending_action.take(),
            background: self.pending_background.take(),
            touch: self.pending_touch.take(),
            should_look_at: self.pending_look_at,
            look_at: self.look_at,
            transform: self.pending_transform.then_some(self.transform),
            mouth_open: self.mouth_open,
            mouth_form: self.mouth_form,
        };
        self.pending_resize = false;
        self.pending_render_options = false;
        self.pending_look_at = false;
        self.pending_transform = false;
        commands
    }
}

struct FrameCommands {
    width: i32,
    height: i32,
    should_resize: bool,
    should_update_render_options: bool,
    vsync_enabled: bool,
    fps_display_enabled: bool,
    model: Option<ModelRequest>,
    action: Option<String>,
    background: Option<BackgroundRequest>,
    touch: Option<(f32, f32)>,
    should_look_at: bool,
    look_at: (f32, f32),
    transform: Option<(f32, f32, f32)>,
    mouth_open: f32,
    mouth_form: f32,
}

struct SharedState {
    control: Mutex<ControlState>,
    wake: Condvar,
}

impl SharedState {
    fn set_error(&self, error: impl Into<String>) {
        let error = error.into();
        lock_unpoisoned(&self.control).last_error = error.clone();
        ffi::log_error(&error);
    }

    fn mark_stopped(&self) {
        lock_unpoisoned(&self.control).running = false;
        self.wake.notify_all();
    }

    fn next_frame(&self) -> Option<(FrameCommands, bool)> {
        let mut state = lock_unpoisoned(&self.control);
        let mut resumed = false;
        while state.running && state.paused {
            resumed = true;
            state = self
                .wake
                .wait(state)
                .unwrap_or_else(|poisoned| poisoned.into_inner());
        }
        if state.running {
            Some((state.take_frame_commands(), resumed))
        } else {
            None
        }
    }

    fn wait_for_frame(&self, frame_started: Instant) {
        let state = lock_unpoisoned(&self.control);
        let fps_limit = state.fps_limit;
        if !state.running || state.paused || fps_limit <= 0 {
            return;
        }

        let target = Duration::from_nanos(1_000_000_000_u64 / fps_limit as u64);
        let remaining = target.saturating_sub(frame_started.elapsed());
        if remaining.is_zero() {
            return;
        }
        drop(
            self.wake
                .wait_timeout_while(state, remaining, |state| state.running && !state.paused)
                .unwrap_or_else(|poisoned| poisoned.into_inner()),
        );
    }
}

pub struct RendererHandle {
    shared: Arc<SharedState>,
    window: Arc<ffi::NativeWindow>,
    render_thread: Mutex<Option<JoinHandle<()>>>,
}

impl RendererHandle {
    pub fn spawn(
        window: ffi::NativeWindow,
        runtime_root: String,
        width: i32,
        height: i32,
        fps_limit: i32,
        vsync_enabled: bool,
        render_scale: f32,
    ) -> Result<Self, String> {
        let control = ControlState::new(width, height, fps_limit, vsync_enabled, render_scale);
        let window = Arc::new(window);
        window.set_buffers_geometry(control.width, control.height);
        let shared = Arc::new(SharedState {
            control: Mutex::new(control),
            wake: Condvar::new(),
        });
        let thread_shared = Arc::clone(&shared);
        let thread_window = Arc::clone(&window);
        let render_thread = thread::Builder::new()
            .name("BandoriPet-Live2D".to_owned())
            .spawn(move || {
                let result = catch_unwind(AssertUnwindSafe(|| {
                    render_loop(&thread_shared, thread_window.as_ref(), &runtime_root)
                }));
                if let Err(panic) = result {
                    thread_shared
                        .set_error(format!("Live2D 渲染线程异常: {}", panic_message(panic)));
                }
                thread_shared.mark_stopped();
            })
            .map_err(|error| format!("无法启动 Live2D 渲染线程: {error}"))?;

        Ok(Self {
            shared,
            window,
            render_thread: Mutex::new(Some(render_thread)),
        })
    }

    pub fn resize(&self, width: i32, height: i32) {
        let (render_width, render_height) = {
            let mut state = lock_unpoisoned(&self.shared.control);
            state.surface_width = width.max(1);
            state.surface_height = height.max(1);
            (state.width, state.height) = render_size(
                state.surface_width,
                state.surface_height,
                state.render_scale,
            );
            state.pending_resize = true;
            (state.width, state.height)
        };
        self.window
            .set_buffers_geometry(render_width, render_height);
    }

    pub fn load_model(&self, path: String, resources: HashMap<String, Vec<u8>>) {
        let mut state = lock_unpoisoned(&self.shared.control);
        state.pending_model = Some(ModelRequest { path, resources });
        state.last_error.clear();
    }

    pub fn set_render_options(&self, fps_limit: i32, vsync_enabled: bool) {
        let mut state = lock_unpoisoned(&self.shared.control);
        state.fps_limit = if fps_limit > 0 { fps_limit } else { 60 };
        state.vsync_enabled = vsync_enabled;
        state.pending_render_options = true;
    }

    pub fn set_paused(&self, paused: bool) {
        lock_unpoisoned(&self.shared.control).paused = paused;
        self.shared.wake.notify_all();
    }

    pub fn set_render_scale(&self, scale: f32) {
        let (render_width, render_height) = {
            let mut state = lock_unpoisoned(&self.shared.control);
            state.render_scale = scale.clamp(0.5, 2.0);
            (state.width, state.height) = render_size(
                state.surface_width,
                state.surface_height,
                state.render_scale,
            );
            state.pending_resize = true;
            (state.width, state.height)
        };
        self.window
            .set_buffers_geometry(render_width, render_height);
    }

    pub fn set_fps_display_enabled(&self, enabled: bool) {
        lock_unpoisoned(&self.shared.control).fps_display_enabled = enabled;
    }

    pub fn set_transform(&self, offset_x: f32, offset_y: f32, scale: f32) {
        let mut state = lock_unpoisoned(&self.shared.control);
        state.transform = (offset_x, offset_y, scale);
        state.pending_transform = true;
    }

    pub fn set_background(&self, pixels: Vec<u32>, width: i32, height: i32) {
        lock_unpoisoned(&self.shared.control).pending_background = Some(BackgroundRequest {
            pixels,
            width,
            height,
        });
    }

    pub fn touch(&self, x_ratio: f32, y_ratio: f32) {
        lock_unpoisoned(&self.shared.control).pending_touch =
            Some((x_ratio.clamp(0.0, 1.0), y_ratio.clamp(0.0, 1.0)));
    }

    pub fn look_at(&self, x_ratio: f32, y_ratio: f32) {
        let mut state = lock_unpoisoned(&self.shared.control);
        state.look_at = (x_ratio.clamp(0.0, 1.0), y_ratio.clamp(0.0, 1.0));
        state.pending_look_at = true;
    }

    pub fn play_action(&self, tag: String) {
        lock_unpoisoned(&self.shared.control).pending_action = Some(tag);
    }

    pub fn set_lip_sync(&self, open: f32, form: f32) {
        let mut state = lock_unpoisoned(&self.shared.control);
        state.mouth_open = open.clamp(0.0, 1.0);
        state.mouth_form = form.clamp(-1.0, 1.0);
    }

    pub fn last_error(&self) -> String {
        lock_unpoisoned(&self.shared.control).last_error.clone()
    }

    pub fn set_error(&self, error: impl Into<String>) {
        self.shared.set_error(error);
    }

    pub fn shutdown(&self) {
        {
            let mut state = lock_unpoisoned(&self.shared.control);
            state.running = false;
        }
        self.shared.wake.notify_all();
        if let Some(thread) = lock_unpoisoned(&self.render_thread).take() {
            if thread.thread().id() != thread::current().id() {
                let _ = thread.join();
            }
        }
    }
}

impl Drop for RendererHandle {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn panic_message(panic: Box<dyn Any + Send>) -> String {
    if let Some(message) = panic.downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = panic.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown panic".to_owned()
    }
}

struct EglSession {
    display: ffi::EGLDisplay,
    surface: ffi::EGLSurface,
    context: ffi::EGLContext,
}

impl EglSession {
    fn create(window: &ffi::NativeWindow, vsync_enabled: bool) -> Result<Self, String> {
        let mut session = Self {
            display: ptr::null_mut(),
            surface: ptr::null_mut(),
            context: ptr::null_mut(),
        };
        session.display = unsafe { ffi::eglGetDisplay(ptr::null_mut()) };
        if session.display.is_null()
            || unsafe {
                ffi::eglInitialize(session.display, ptr::null_mut(), ptr::null_mut())
                    != ffi::EGL_TRUE
            }
        {
            return Err("EGL 初始化失败".to_owned());
        }
        if unsafe { ffi::eglBindAPI(ffi::EGL_OPENGL_ES_API) } != ffi::EGL_TRUE {
            return Err("当前 EGL 不支持 OpenGL ES API".to_owned());
        }

        let attributes = [
            ffi::EGL_RENDERABLE_TYPE,
            ffi::EGL_OPENGL_ES2_BIT,
            ffi::EGL_SURFACE_TYPE,
            ffi::EGL_WINDOW_BIT,
            ffi::EGL_RED_SIZE,
            8,
            ffi::EGL_GREEN_SIZE,
            8,
            ffi::EGL_BLUE_SIZE,
            8,
            ffi::EGL_ALPHA_SIZE,
            8,
            ffi::EGL_STENCIL_SIZE,
            8,
            ffi::EGL_NONE,
        ];
        let mut config = ptr::null_mut();
        let mut config_count = 0;
        if unsafe {
            ffi::eglChooseConfig(
                session.display,
                attributes.as_ptr(),
                &mut config,
                1,
                &mut config_count,
            )
        } != ffi::EGL_TRUE
            || config_count == 0
        {
            return Err("找不到支持 OpenGL ES 2.0 的 EGLConfig".to_owned());
        }

        session.surface = unsafe {
            ffi::eglCreateWindowSurface(session.display, config, window.as_ptr(), ptr::null())
        };
        if session.surface.is_null() {
            return Err("EGL 窗口 Surface 创建失败".to_owned());
        }

        let context_attributes = [ffi::EGL_CONTEXT_CLIENT_VERSION, 2, ffi::EGL_NONE];
        session.context = unsafe {
            ffi::eglCreateContext(
                session.display,
                config,
                ptr::null_mut(),
                context_attributes.as_ptr(),
            )
        };
        if session.context.is_null()
            || unsafe {
                ffi::eglMakeCurrent(
                    session.display,
                    session.surface,
                    session.surface,
                    session.context,
                ) != ffi::EGL_TRUE
            }
        {
            return Err("OpenGL ES 上下文创建失败".to_owned());
        }
        session.set_vsync(vsync_enabled);
        Ok(session)
    }

    fn set_vsync(&self, enabled: bool) {
        unsafe {
            ffi::eglSwapInterval(self.display, if enabled { 1 } else { 0 });
        }
    }

    fn swap_buffers(&self) -> bool {
        unsafe { ffi::eglSwapBuffers(self.display, self.surface) == ffi::EGL_TRUE }
    }
}

impl Drop for EglSession {
    fn drop(&mut self) {
        if !self.display.is_null() {
            unsafe {
                ffi::eglMakeCurrent(
                    self.display,
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                );
                if !self.context.is_null() {
                    ffi::eglDestroyContext(self.display, self.context);
                }
                if !self.surface.is_null() {
                    ffi::eglDestroySurface(self.display, self.surface);
                }
                ffi::eglTerminate(self.display);
            }
        }
    }
}

fn report_result(shared: &SharedState, result: Result<(), String>) {
    if let Err(error) = result {
        shared.set_error(error);
    }
}

fn render_loop(shared: &Arc<SharedState>, window: &ffi::NativeWindow, runtime_root: &str) {
    let initial_vsync = lock_unpoisoned(&shared.control).vsync_enabled;
    let egl = match EglSession::create(window, initial_vsync) {
        Ok(egl) => egl,
        Err(error) => {
            shared.set_error(error);
            return;
        }
    };
    let mut lua = match LuaRuntime::new(runtime_root) {
        Ok(lua) => lua,
        Err(error) => {
            shared.set_error(error);
            return;
        }
    };
    let mut background = BackgroundRenderer::default();
    let mut fps_renderer = FpsRenderer::default();

    let monotonic_origin = Instant::now();
    let mut previous_frame_start = monotonic_origin;
    let mut fps_sample_start = monotonic_origin;
    let mut frames_since_sample = 0_i32;
    let mut measured_fps = 0_i32;
    let mut look_at_active = false;
    let mut smoothed_look_at = (0.5_f32, 0.5_f32);

    while let Some((commands, resumed)) = shared.next_frame() {
        if resumed {
            previous_frame_start = Instant::now();
            fps_sample_start = previous_frame_start;
            frames_since_sample = 0;
            measured_fps = 0;
            continue;
        }

        let frame_started = Instant::now();
        let delta_seconds = frame_started
            .duration_since(previous_frame_start)
            .as_secs_f32()
            .clamp(0.0, 0.1);
        previous_frame_start = frame_started;

        if commands.should_update_render_options {
            egl.set_vsync(commands.vsync_enabled);
        }
        if let Some(request) = commands.background {
            background.upload(&request.pixels, request.width, request.height);
        }
        if commands.should_resize {
            lua.get_global(c"__bp_resize");
            lua.push_number(commands.width as f64);
            lua.push_number(commands.height as f64);
            report_result(shared, lua.call("__bp_resize", 2));
        }
        if let Some(model) = commands.model {
            if !model.path.is_empty() {
                lua.set_resources(model.resources);
                look_at_active = false;
                smoothed_look_at = (0.5, 0.5);
                lua.get_global(c"__bp_load");
                lua.push_bytes(model.path.as_bytes());
                lua.push_number(commands.width as f64);
                lua.push_number(commands.height as f64);
                report_result(shared, lua.call("__bp_load", 3));
            }
        }
        if let Some((x, y)) = commands.touch {
            lua.get_global(c"__bp_touch");
            lua.push_number(x as f64);
            lua.push_number(y as f64);
            report_result(shared, lua.call("__bp_touch", 2));
        }
        if let Some(action) = commands.action {
            if !action.is_empty() {
                lua.get_global(c"__bp_action");
                lua.push_bytes(action.as_bytes());
                report_result(shared, lua.call("__bp_action", 1));
            }
        }
        if commands.should_look_at {
            look_at_active = true;
        }
        if look_at_active {
            let easing = (delta_seconds * GAZE_FOLLOW_EASING_PER_SECOND).clamp(0.0, 1.0);
            smoothed_look_at.0 += (commands.look_at.0 - smoothed_look_at.0) * easing;
            smoothed_look_at.1 += (commands.look_at.1 - smoothed_look_at.1) * easing;
            if (commands.look_at.0 - smoothed_look_at.0).abs() < 0.001
                && (commands.look_at.1 - smoothed_look_at.1).abs() < 0.001
            {
                smoothed_look_at = commands.look_at;
                look_at_active = false;
            }
            lua.get_global(c"__bp_look_at");
            lua.push_number(smoothed_look_at.0 as f64);
            lua.push_number(smoothed_look_at.1 as f64);
            report_result(shared, lua.call("__bp_look_at", 2));
        }
        if let Some((offset_x, offset_y, scale)) = commands.transform {
            lua.get_global(c"__bp_transform");
            lua.push_number(offset_x as f64);
            lua.push_number(offset_y as f64);
            lua.push_number(scale as f64);
            report_result(shared, lua.call("__bp_transform", 3));
        }

        lua.get_global(c"__bp_clear");
        report_result(shared, lua.call("__bp_clear", 0));
        report_result(shared, background.draw(commands.width, commands.height));
        lua.get_global(c"__bp_draw");
        lua.push_number(monotonic_origin.elapsed().as_millis() as f64);
        lua.push_number(commands.mouth_open as f64);
        lua.push_number(commands.mouth_form as f64);
        report_result(shared, lua.call("__bp_draw", 3));
        report_result(
            shared,
            fps_renderer.draw(
                commands.fps_display_enabled,
                measured_fps,
                commands.width,
                commands.height,
            ),
        );

        if egl.swap_buffers() {
            frames_since_sample += 1;
            let elapsed = fps_sample_start.elapsed().as_secs_f32();
            if elapsed >= 0.5 {
                measured_fps =
                    ((frames_since_sample as f32 / elapsed).round() as i32).clamp(0, 999);
                frames_since_sample = 0;
                fps_sample_start = Instant::now();
            }
        }
        shared.wait_for_frame(frame_started);
    }
}
