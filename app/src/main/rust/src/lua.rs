use std::collections::HashMap;
use std::ffi::{CString, c_char, c_int, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

use libloading::Library;
use libloading::os::unix::{Library as UnixLibrary, RTLD_GLOBAL, RTLD_NOW};

const LUA_GLOBALSINDEX_COMPAT: c_int = -10002;
const LUA_UPVALUEINDEX_1: c_int = LUA_GLOBALSINDEX_COMPAT - 1;
const BOOTSTRAP: &str = include_str!("bootstrap.lua");

#[repr(C)]
pub struct LuaState {
    _private: [u8; 0],
}

type LuaCFunction = unsafe extern "C" fn(*mut LuaState) -> c_int;

struct LuaApi {
    _library: Library,
    new_state: unsafe extern "C" fn() -> *mut LuaState,
    open_libs: unsafe extern "C" fn(*mut LuaState),
    load_string: unsafe extern "C" fn(*mut LuaState, *const c_char) -> c_int,
    pcall: unsafe extern "C" fn(*mut LuaState, c_int, c_int, c_int) -> c_int,
    close: unsafe extern "C" fn(*mut LuaState),
    get_field: unsafe extern "C" fn(*mut LuaState, c_int, *const c_char),
    _push_string: unsafe extern "C" fn(*mut LuaState, *const c_char),
    push_lstring: unsafe extern "C" fn(*mut LuaState, *const c_char, usize),
    push_number: unsafe extern "C" fn(*mut LuaState, f64),
    push_nil: unsafe extern "C" fn(*mut LuaState),
    push_light_userdata: unsafe extern "C" fn(*mut LuaState, *mut c_void),
    push_cclosure: unsafe extern "C" fn(*mut LuaState, LuaCFunction, c_int),
    set_field: unsafe extern "C" fn(*mut LuaState, c_int, *const c_char),
    to_lstring: unsafe extern "C" fn(*mut LuaState, c_int, *mut usize) -> *const c_char,
    to_userdata: unsafe extern "C" fn(*mut LuaState, c_int) -> *mut c_void,
    set_top: unsafe extern "C" fn(*mut LuaState, c_int),
}

static LUA_API: OnceLock<Mutex<Option<Arc<LuaApi>>>> = OnceLock::new();

enum LuaLoadError {
    Open,
    Symbol,
}

fn lock_unpoisoned<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

unsafe fn load_symbol<T: Copy>(library: &Library, name: &[u8]) -> Result<T, LuaLoadError> {
    unsafe { library.get::<T>(name) }
        .map(|symbol| *symbol)
        .map_err(|_| LuaLoadError::Symbol)
}

impl LuaApi {
    unsafe fn load_library(name: &str) -> Result<Self, LuaLoadError> {
        let library: Library = unsafe { UnixLibrary::open(Some(name), RTLD_NOW | RTLD_GLOBAL) }
            .map_err(|_| LuaLoadError::Open)?
            .into();
        unsafe {
            Ok(Self {
                new_state: load_symbol(&library, b"luaL_newstate\0")?,
                open_libs: load_symbol(&library, b"luaL_openlibs\0")?,
                load_string: load_symbol(&library, b"luaL_loadstring\0")?,
                pcall: load_symbol(&library, b"lua_pcall\0")?,
                close: load_symbol(&library, b"lua_close\0")?,
                get_field: load_symbol(&library, b"lua_getfield\0")?,
                _push_string: load_symbol(&library, b"lua_pushstring\0")?,
                push_lstring: load_symbol(&library, b"lua_pushlstring\0")?,
                push_number: load_symbol(&library, b"lua_pushnumber\0")?,
                push_nil: load_symbol(&library, b"lua_pushnil\0")?,
                push_light_userdata: load_symbol(&library, b"lua_pushlightuserdata\0")?,
                push_cclosure: load_symbol(&library, b"lua_pushcclosure\0")?,
                set_field: load_symbol(&library, b"lua_setfield\0")?,
                to_lstring: load_symbol(&library, b"lua_tolstring\0")?,
                to_userdata: load_symbol(&library, b"lua_touserdata\0")?,
                set_top: load_symbol(&library, b"lua_settop\0")?,
                _library: library,
            })
        }
    }
}

fn shared_api() -> Result<Arc<LuaApi>, String> {
    let cell = LUA_API.get_or_init(|| Mutex::new(None));
    let mut loaded = lock_unpoisoned(cell);
    if let Some(api) = loaded.as_ref() {
        return Ok(Arc::clone(api));
    }

    let mut found_library = false;
    for name in ["libluajit.so", "libluajit-5.1.so", "liblua.so"] {
        match unsafe { LuaApi::load_library(name) } {
            Ok(api) => {
                let api = Arc::new(api);
                *loaded = Some(Arc::clone(&api));
                return Ok(api);
            }
            Err(LuaLoadError::Symbol) => found_library = true,
            Err(LuaLoadError::Open) => {}
        }
    }

    if found_library {
        Err("LuaJIT 导出符号不完整".to_owned())
    } else {
        Err("无法加载 libluajit.so，请将 LuaJIT 放入 app/src/main/jniLibs/<abi>/".to_owned())
    }
}

pub fn normalize_resource_path(path: &str) -> String {
    let mut normalized = path.replace('\\', "/");
    while normalized.starts_with("./") {
        normalized.drain(..2);
    }
    normalized
}

pub struct LuaRuntime {
    api: Arc<LuaApi>,
    state: *mut LuaState,
    resources: HashMap<String, Vec<u8>>,
}

impl LuaRuntime {
    pub fn new(runtime_root: &str) -> Result<Box<Self>, String> {
        let api = shared_api()?;
        let state = unsafe { (api.new_state)() };
        if state.is_null() {
            return Err("LuaJIT state 创建失败".to_owned());
        }

        let mut runtime = Box::new(Self {
            api,
            state,
            resources: HashMap::new(),
        });
        runtime.initialize(runtime_root)?;
        Ok(runtime)
    }

    fn initialize(&mut self, runtime_root: &str) -> Result<(), String> {
        unsafe {
            (self.api.open_libs)(self.state);
        }
        self.push_bytes(runtime_root.as_bytes());
        self.set_global(c"__bp_runtime_root");

        let userdata = self as *mut Self as *mut c_void;
        unsafe {
            (self.api.push_light_userdata)(self.state, userdata);
            (self.api.push_cclosure)(self.state, read_resource_lua, 1);
        }
        self.set_global(c"__bp_read_resource");
        self.run_code(BOOTSTRAP)
    }

    pub fn set_resources(&mut self, resources: HashMap<String, Vec<u8>>) {
        self.resources = resources;
    }

    pub fn get_global(&mut self, name: &'static std::ffi::CStr) {
        unsafe {
            (self.api.get_field)(self.state, LUA_GLOBALSINDEX_COMPAT, name.as_ptr());
        }
    }

    pub fn push_bytes(&mut self, value: &[u8]) {
        unsafe {
            (self.api.push_lstring)(self.state, value.as_ptr().cast(), value.len());
        }
    }

    pub fn push_number(&mut self, value: f64) {
        unsafe {
            (self.api.push_number)(self.state, value);
        }
    }

    pub fn call(&mut self, function_name: &str, argument_count: c_int) -> Result<(), String> {
        let status = unsafe { (self.api.pcall)(self.state, argument_count, 0, 0) };
        if status == 0 {
            Ok(())
        } else {
            let error = format!("{function_name}: {}", self.error_text());
            self.pop_one();
            Err(error)
        }
    }

    fn run_code(&mut self, code: &str) -> Result<(), String> {
        let code = CString::new(code).map_err(|_| "Lua bootstrap 包含 NUL 字节".to_owned())?;
        if unsafe { (self.api.load_string)(self.state, code.as_ptr()) } != 0 {
            let error = self.error_text();
            self.pop_one();
            return Err(error);
        }
        if unsafe { (self.api.pcall)(self.state, 0, 0, 0) } != 0 {
            let error = self.error_text();
            self.pop_one();
            return Err(error);
        }
        Ok(())
    }

    fn set_global(&mut self, name: &'static std::ffi::CStr) {
        unsafe {
            (self.api.set_field)(self.state, LUA_GLOBALSINDEX_COMPAT, name.as_ptr());
        }
    }

    fn error_text(&self) -> String {
        let mut length = 0_usize;
        let text = unsafe { (self.api.to_lstring)(self.state, -1, &mut length) };
        if text.is_null() {
            "unknown Lua error".to_owned()
        } else {
            let bytes = unsafe { slice::from_raw_parts(text.cast::<u8>(), length) };
            String::from_utf8_lossy(bytes).into_owned()
        }
    }

    fn pop_one(&mut self) {
        unsafe {
            (self.api.set_top)(self.state, -2);
        }
    }
}

impl Drop for LuaRuntime {
    fn drop(&mut self) {
        if !self.state.is_null() {
            unsafe {
                (self.api.close)(self.state);
            }
            self.state = std::ptr::null_mut();
        }
    }
}

unsafe extern "C" fn read_resource_lua(state: *mut LuaState) -> c_int {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        read_resource_lua_inner(state)
    }))
    .unwrap_or(0)
}

unsafe fn read_resource_lua_inner(state: *mut LuaState) -> c_int {
    if state.is_null() {
        return 0;
    }

    let api = match shared_api() {
        Ok(api) => api,
        Err(_) => return 0,
    };
    let runtime = unsafe { (api.to_userdata)(state, LUA_UPVALUEINDEX_1) } as *mut LuaRuntime;
    if runtime.is_null() {
        unsafe { (api.push_nil)(state) };
        return 1;
    }

    let mut path_length = 0_usize;
    let path = unsafe { (api.to_lstring)(state, 1, &mut path_length) };
    if path.is_null() {
        unsafe { (api.push_nil)(state) };
        return 1;
    }

    let path_bytes = unsafe { slice::from_raw_parts(path.cast::<u8>(), path_length) };
    let path = normalize_resource_path(&String::from_utf8_lossy(path_bytes));
    let runtime = unsafe { &mut *runtime };
    if let Some(bytes) = runtime.resources.get(&path) {
        unsafe {
            (api.push_lstring)(state, bytes.as_ptr().cast(), bytes.len());
        }
    } else {
        unsafe { (api.push_nil)(state) };
    }
    1
}
