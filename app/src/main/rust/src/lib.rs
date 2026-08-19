#![allow(non_snake_case)]

mod ffi;
mod lua;
mod overlays;
mod renderer;

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

use jni::JNIEnv;
use jni::objects::{JByteArray, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jfloat, jint, jlong, jstring};

use crate::lua::normalize_resource_path;
use crate::renderer::RendererHandle;

static RENDERERS: OnceLock<Mutex<HashMap<jlong, Arc<RendererHandle>>>> = OnceLock::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn lock_unpoisoned<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn registry() -> &'static Mutex<HashMap<jlong, Arc<RendererHandle>>> {
    RENDERERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn renderer(handle: jlong) -> Option<Arc<RendererHandle>> {
    if handle <= 0 {
        return None;
    }
    lock_unpoisoned(registry()).get(&handle).cloned()
}

fn insert_renderer(renderer: Arc<RendererHandle>) -> jlong {
    loop {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            NEXT_HANDLE.store(1, Ordering::Relaxed);
            continue;
        }
        let mut renderers = lock_unpoisoned(registry());
        if let std::collections::hash_map::Entry::Vacant(entry) = renderers.entry(handle) {
            entry.insert(renderer);
            return handle;
        }
    }
}

fn remove_renderer(handle: jlong) -> Option<Arc<RendererHandle>> {
    lock_unpoisoned(registry()).remove(&handle)
}

fn jni_catch<T>(default: T, action: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(action)) {
        Ok(value) => value,
        Err(_) => {
            ffi::log_error("Rust panic 已在 JNI 边界被拦截");
            default
        }
    }
}

fn java_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    // Every call site is backed by a Kotlin String/JString declaration. Avoid the checked
    // variant here because it allocates class local references for every archived resource.
    unsafe { env.get_string_unchecked(value) }
        .map(Into::into)
        .map_err(|error| format!("读取 Java 字符串失败: {error}"))
}

fn collect_resources(
    env: &mut JNIEnv<'_>,
    resource_paths: &JObjectArray<'_>,
    resource_bytes: &JObjectArray<'_>,
) -> Result<HashMap<String, Vec<u8>>, String> {
    let mut resources = HashMap::new();
    if resource_paths.is_null() || resource_bytes.is_null() {
        return Ok(resources);
    }

    let path_count = env
        .get_array_length(resource_paths)
        .map_err(|error| format!("读取资源路径数组失败: {error}"))?;
    let byte_count = env
        .get_array_length(resource_bytes)
        .map_err(|error| format!("读取资源数据数组失败: {error}"))?;
    for index in 0..path_count.min(byte_count) {
        let path_object = env
            .get_object_array_element(resource_paths, index)
            .map_err(|error| format!("读取资源路径失败: {error}"))?;
        let bytes_object = env
            .get_object_array_element(resource_bytes, index)
            .map_err(|error| format!("读取资源数据失败: {error}"))?;
        if path_object.is_null() || bytes_object.is_null() {
            if !path_object.is_null() {
                let _ = env.delete_local_ref(path_object);
            }
            if !bytes_object.is_null() {
                let _ = env.delete_local_ref(bytes_object);
            }
            continue;
        }

        let path_string = JString::from(path_object);
        let byte_array = JByteArray::from(bytes_object);
        let path = java_string(env, &path_string)?;
        let bytes = env
            .convert_byte_array(&byte_array)
            .map_err(|error| format!("复制资源数据失败: {error}"))?;
        resources.insert(normalize_resource_path(&path), bytes);
        let _ = env.delete_local_ref(JObject::from(path_string));
        let _ = env.delete_local_ref(JObject::from(byte_array));
    }
    Ok(resources)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_create(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    surface: JObject<'_>,
    runtime_root: JString<'_>,
    width: jint,
    height: jint,
    fps_limit: jint,
    vsync_enabled: jboolean,
    render_scale: jfloat,
) -> jlong {
    jni_catch(0, || {
        if surface.is_null() {
            return 0;
        }
        let runtime_root = java_string(&mut env, &runtime_root).unwrap_or_default();
        let window =
            match unsafe { ffi::NativeWindow::from_surface(env.get_raw(), surface.as_raw()) } {
                Ok(window) => window,
                Err(error) => {
                    ffi::log_error(&error);
                    return 0;
                }
            };
        let renderer = match RendererHandle::spawn(
            window,
            runtime_root,
            width,
            height,
            fps_limit,
            vsync_enabled == JNI_TRUE,
            render_scale,
        ) {
            Ok(renderer) => Arc::new(renderer),
            Err(error) => {
                ffi::log_error(&error);
                return 0;
            }
        };
        insert_renderer(renderer)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_resize(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    width: jint,
    height: jint,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.resize(width, height);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_loadModel(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    model_path: JString<'_>,
    resource_paths: JObjectArray<'_>,
    resource_bytes: JObjectArray<'_>,
) -> jboolean {
    jni_catch(JNI_FALSE, || {
        let Some(renderer) = renderer(handle) else {
            return JNI_FALSE;
        };
        let model_path = match java_string(&mut env, &model_path) {
            Ok(path) => path,
            Err(error) => {
                renderer.set_error(error);
                return JNI_FALSE;
            }
        };
        let resources = match collect_resources(&mut env, &resource_paths, &resource_bytes) {
            Ok(resources) => resources,
            Err(error) => {
                renderer.set_error(error);
                return JNI_FALSE;
            }
        };
        renderer.load_model(model_path, resources);
        JNI_TRUE
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setRenderOptions(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    fps_limit: jint,
    vsync_enabled: jboolean,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.set_render_options(fps_limit, vsync_enabled == JNI_TRUE);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setPaused(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    paused: jboolean,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.set_paused(paused == JNI_TRUE);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setRenderScale(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    scale: jfloat,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.set_render_scale(scale);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setFpsDisplayEnabled(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    enabled: jboolean,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.set_fps_display_enabled(enabled == JNI_TRUE);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setTransform(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    offset_x: jfloat,
    offset_y: jfloat,
    scale: jfloat,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.set_transform(offset_x, offset_y, scale);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setBackgroundPixels(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    pixels: JIntArray<'_>,
    width: jint,
    height: jint,
) {
    jni_catch((), || {
        let Some(renderer) = renderer(handle) else {
            return;
        };
        let mut pixel_data = Vec::new();
        let valid_dimensions = width > 0 && height > 0 && width <= 4096 && height <= 4096;
        if !pixels.is_null() && valid_dimensions {
            let required_count = width.saturating_mul(height);
            let available = env.get_array_length(&pixels).unwrap_or(0);
            if required_count > 0 && available >= required_count {
                let mut raw_pixels = vec![0_i32; required_count as usize];
                if env
                    .get_int_array_region(&pixels, 0, &mut raw_pixels)
                    .is_ok()
                {
                    pixel_data = raw_pixels.into_iter().map(|pixel| pixel as u32).collect();
                }
            }
        }
        let (background_width, background_height) = if pixel_data.is_empty() {
            (0, 0)
        } else {
            (width, height)
        };
        renderer.set_background(pixel_data, background_width, background_height);
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_touch(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    x_ratio: jfloat,
    y_ratio: jfloat,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.touch(x_ratio, y_ratio);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_lookAt(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    x_ratio: jfloat,
    y_ratio: jfloat,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.look_at(x_ratio, y_ratio);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_playAction(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    tag: JString<'_>,
) {
    jni_catch((), || {
        let Some(renderer) = renderer(handle) else {
            return;
        };
        if tag.is_null() {
            return;
        }
        match java_string(&mut env, &tag) {
            Ok(tag) => renderer.play_action(tag),
            Err(error) => renderer.set_error(error),
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_setLipSync(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    open: jfloat,
    form: jfloat,
) {
    jni_catch((), || {
        if let Some(renderer) = renderer(handle) {
            renderer.set_lip_sync(open, form);
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_lastError(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    jni_catch(ptr::null_mut(), || {
        let error = renderer(handle)
            .map(|renderer| renderer.last_error())
            .unwrap_or_default();
        env.new_string(error)
            .map(|value| value.into_raw())
            .unwrap_or(ptr::null_mut())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bandori_pet_live2d_NativeLive2D_destroy(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    jni_catch((), || {
        if let Some(renderer) = remove_renderer(handle) {
            renderer.shutdown();
        }
    });
}
