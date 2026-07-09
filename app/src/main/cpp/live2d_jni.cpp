#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cstddef>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>

#ifndef EGL_OPENGL_ES_API
#define EGL_OPENGL_ES_API 0x30A0
#endif

#ifndef EGL_OPENGL_ES2_BIT
#define EGL_OPENGL_ES2_BIT 0x0004
#endif

#ifndef EGL_CONTEXT_CLIENT_VERSION
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#endif

#ifndef EGL_CONTEXT_MAJOR_VERSION_KHR
#define EGL_CONTEXT_MAJOR_VERSION_KHR 0x3098
#endif

#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BandoriPet", __VA_ARGS__)

static constexpr int MAX_RENDER_EDGE = 1280;

struct lua_State;
typedef int (*lua_CFunction)(lua_State* L);

struct LuaApi {
    void* handle = nullptr;
    lua_State* (*newState)() = nullptr;
    void (*openLibs)(lua_State*) = nullptr;
    int (*loadString)(lua_State*, const char*) = nullptr;
    int (*pcall)(lua_State*, int, int, int) = nullptr;
    void (*close)(lua_State*) = nullptr;
    void (*getField)(lua_State*, int, const char*) = nullptr;
    void (*pushString)(lua_State*, const char*) = nullptr;
    void (*pushLString)(lua_State*, const char*, size_t) = nullptr;
    void (*pushNumber)(lua_State*, double) = nullptr;
    void (*pushNil)(lua_State*) = nullptr;
    void (*pushLightUserData)(lua_State*, void*) = nullptr;
    void (*pushCClosure)(lua_State*, lua_CFunction, int) = nullptr;
    void (*setField)(lua_State*, int, const char*) = nullptr;
    const char* (*toString)(lua_State*, int, size_t*) = nullptr;
    void* (*toUserData)(lua_State*, int) = nullptr;
    void (*setTop)(lua_State*, int) = nullptr;
};

static constexpr int LUA_GLOBALSINDEX_COMPAT = -10002;
static constexpr int LUA_UPVALUEINDEX_1 = LUA_GLOBALSINDEX_COMPAT - 1;

static const char* (*gLuaToString)(lua_State*, int, size_t*) = nullptr;
static void* (*gLuaToUserData)(lua_State*, int) = nullptr;
static void (*gLuaPushLString)(lua_State*, const char*, size_t) = nullptr;
static void (*gLuaPushNil)(lua_State*) = nullptr;

struct Renderer {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    std::atomic<int> width{1};
    std::atomic<int> height{1};

    LuaApi luaApi;
    lua_State* lua = nullptr;
    std::string runtimeRoot;
    std::string pendingModel;
    std::unordered_map<std::string, std::string> pendingResources;
    std::unordered_map<std::string, std::string> resources;
    std::string lastError;
    std::mutex mutex;
    std::thread renderThread;
    std::atomic<bool> running{true};
    std::atomic<bool> pendingResize{false};
    std::atomic<bool> pendingTouch{false};
    std::atomic<bool> pendingTransform{false};
    std::atomic<bool> pendingRenderOptions{false};
    std::atomic<int> fpsLimit{60};
    std::atomic<bool> vsyncEnabled{true};
    std::atomic<float> transformOffsetX{0.0f};
    std::atomic<float> transformOffsetY{0.0f};
    std::atomic<float> transformScale{1.0f};
};

static int positiveSize(int value) {
    return value > 0 ? value : 1;
}

static void configureRenderSize(Renderer* renderer, int surfaceWidth, int surfaceHeight) {
    const int srcWidth = positiveSize(surfaceWidth);
    const int srcHeight = positiveSize(surfaceHeight);
    const int maxEdge = srcWidth > srcHeight ? srcWidth : srcHeight;
    int renderWidth = srcWidth;
    int renderHeight = srcHeight;
    if (maxEdge > MAX_RENDER_EDGE) {
        renderWidth = (srcWidth * MAX_RENDER_EDGE + maxEdge / 2) / maxEdge;
        renderHeight = (srcHeight * MAX_RENDER_EDGE + maxEdge / 2) / maxEdge;
        if (renderWidth < 1) renderWidth = 1;
        if (renderHeight < 1) renderHeight = 1;
    }
    renderer->width.store(renderWidth);
    renderer->height.store(renderHeight);
    if (renderer->window != nullptr) {
        ANativeWindow_setBuffersGeometry(renderer->window, renderWidth, renderHeight, WINDOW_FORMAT_RGBA_8888);
    }
}

static void setError(Renderer* renderer, const std::string& error) {
    std::lock_guard<std::mutex> lock(renderer->mutex);
    renderer->lastError = error;
    LOGE("%s", error.c_str());
}

template <typename T>
static bool loadSymbol(void* handle, const char* name, T& out) {
    out = reinterpret_cast<T>(dlsym(handle, name));
    return out != nullptr;
}

static bool loadLua(Renderer* renderer) {
    const char* names[] = {"libluajit.so", "libluajit-5.1.so", "liblua.so"};
    for (const char* name : names) {
        renderer->luaApi.handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
        if (renderer->luaApi.handle != nullptr) break;
    }
    if (renderer->luaApi.handle == nullptr) {
        setError(renderer, "无法加载 libluajit.so，请将 LuaJIT 放入 app/src/main/jniLibs/<abi>/");
        return false;
    }

    auto& api = renderer->luaApi;
    bool ok = loadSymbol(api.handle, "luaL_newstate", api.newState)
        && loadSymbol(api.handle, "luaL_openlibs", api.openLibs)
        && loadSymbol(api.handle, "luaL_loadstring", api.loadString)
        && loadSymbol(api.handle, "lua_pcall", api.pcall)
        && loadSymbol(api.handle, "lua_close", api.close)
        && loadSymbol(api.handle, "lua_getfield", api.getField)
        && loadSymbol(api.handle, "lua_pushstring", api.pushString)
        && loadSymbol(api.handle, "lua_pushlstring", api.pushLString)
        && loadSymbol(api.handle, "lua_pushnumber", api.pushNumber)
        && loadSymbol(api.handle, "lua_pushnil", api.pushNil)
        && loadSymbol(api.handle, "lua_pushlightuserdata", api.pushLightUserData)
        && loadSymbol(api.handle, "lua_pushcclosure", api.pushCClosure)
        && loadSymbol(api.handle, "lua_setfield", api.setField)
        && loadSymbol(api.handle, "lua_tolstring", api.toString)
        && loadSymbol(api.handle, "lua_touserdata", api.toUserData)
        && loadSymbol(api.handle, "lua_settop", api.setTop);
    if (!ok) {
        setError(renderer, "LuaJIT 导出符号不完整");
        return false;
    }
    gLuaToString = api.toString;
    gLuaToUserData = api.toUserData;
    gLuaPushLString = api.pushLString;
    gLuaPushNil = api.pushNil;
    return true;
}

static const char* luaError(Renderer* renderer) {
    const char* error = renderer->luaApi.toString(renderer->lua, -1, nullptr);
    return error != nullptr ? error : "unknown Lua error";
}

static void getGlobal(Renderer* renderer, const char* name) {
    renderer->luaApi.getField(renderer->lua, LUA_GLOBALSINDEX_COMPAT, name);
}

static void setGlobal(Renderer* renderer, const char* name) {
    renderer->luaApi.setField(renderer->lua, LUA_GLOBALSINDEX_COMPAT, name);
}

static std::string normalizeResourcePath(const char* path) {
    std::string value = path != nullptr ? path : "";
    std::replace(value.begin(), value.end(), '\\', '/');
    while (value.rfind("./", 0) == 0) value.erase(0, 2);
    return value;
}

static int readResourceLua(lua_State* lua) {
    if (gLuaToUserData == nullptr || gLuaToString == nullptr || gLuaPushLString == nullptr || gLuaPushNil == nullptr) {
        return 0;
    }
    auto* renderer = reinterpret_cast<Renderer*>(gLuaToUserData(lua, LUA_UPVALUEINDEX_1));
    const char* pathChars = gLuaToString(lua, 1, nullptr);
    if (renderer == nullptr || pathChars == nullptr) {
        gLuaPushNil(lua);
        return 1;
    }
    const std::string path = normalizeResourcePath(pathChars);
    auto found = renderer->resources.find(path);
    if (found == renderer->resources.end()) {
        gLuaPushNil(lua);
        return 1;
    }
    gLuaPushLString(lua, found->second.data(), found->second.size());
    return 1;
}

static bool runLua(Renderer* renderer, const char* code) {
    auto& api = renderer->luaApi;
    if (api.loadString(renderer->lua, code) != 0) {
        setError(renderer, luaError(renderer));
        api.setTop(renderer->lua, -2);
        return false;
    }
    if (api.pcall(renderer->lua, 0, 0, 0) != 0) {
        setError(renderer, luaError(renderer));
        api.setTop(renderer->lua, -2);
        return false;
    }
    return true;
}

static bool callLua(Renderer* renderer, const char* functionName, int args) {
    auto& api = renderer->luaApi;
    if (api.pcall(renderer->lua, args, 0, 0) != 0) {
        setError(renderer, std::string(functionName) + ": " + luaError(renderer));
        api.setTop(renderer->lua, -2);
        return false;
    }
    return true;
}

static bool initEgl(Renderer* renderer) {
    renderer->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (renderer->display == EGL_NO_DISPLAY || eglInitialize(renderer->display, nullptr, nullptr) != EGL_TRUE) {
        setError(renderer, "EGL 初始化失败");
        return false;
    }
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        setError(renderer, "当前 EGL 不支持 OpenGL ES API");
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(renderer->display, configAttribs, &config, 1, &configCount) != EGL_TRUE || configCount == 0) {
        setError(renderer, "找不到支持 OpenGL ES 2.0 的 EGLConfig");
        return false;
    }

    renderer->surface = eglCreateWindowSurface(renderer->display, config, renderer->window, nullptr);
    if (renderer->surface == EGL_NO_SURFACE) {
        setError(renderer, "EGL 窗口 Surface 创建失败");
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    renderer->context = eglCreateContext(renderer->display, config, EGL_NO_CONTEXT, contextAttribs);
    if (renderer->context == EGL_NO_CONTEXT || eglMakeCurrent(renderer->display, renderer->surface, renderer->surface, renderer->context) != EGL_TRUE) {
        setError(renderer, "OpenGL ES 上下文创建失败");
        return false;
    }
    eglSwapInterval(renderer->display, renderer->vsyncEnabled.load() ? 1 : 0);
    return true;
}

static bool initLua(Renderer* renderer) {
    if (!loadLua(renderer)) return false;
    renderer->lua = renderer->luaApi.newState();
    if (renderer->lua == nullptr) {
        setError(renderer, "LuaJIT state 创建失败");
        return false;
    }
    renderer->luaApi.openLibs(renderer->lua);
    renderer->luaApi.pushString(renderer->lua, renderer->runtimeRoot.c_str());
    setGlobal(renderer, "__bp_runtime_root");
    renderer->luaApi.pushLightUserData(renderer->lua, renderer);
    renderer->luaApi.pushCClosure(renderer->lua, readResourceLua, 1);
    setGlobal(renderer, "__bp_read_resource");

    static const char* bootstrap = R"lua(
package.path = __bp_runtime_root .. "/?.lua;" .. __bp_runtime_root .. "/?/init.lua;" .. package.path
package.cpath = __bp_runtime_root .. "/?.so;" .. package.cpath
local gl = require("live2d.gl_loader")
if gl.ensureExtensions then gl.ensureExtensions() end
local raw_io_open = io.open
local renderer = nil
local width = 1
local height = 1
local groups = {}
local group_index = 1
local default_group = nil
local active_motion_kind = nil
local model_is_moc3 = false
local offset_x = 0
local offset_y = 0
local scale = 1

local ACTION_GROUP_ORDER = {
    "tap_body", "tap_head", "angry01", "bye01", "kime01", "smile01", "nf01", "nnf01",
}

local function ends_with(value, suffix)
    return value:sub(-#suffix) == suffix
end

local function normalize_path(value)
    return tostring(value or ""):gsub("\\", "/"):gsub("^%./", "")
end

local function memory_file(data)
    local pos = 1
    local file = {}
    function file:read(fmt)
        fmt = fmt or "*l"
        if fmt == "*all" or fmt == "*a" then
            local out = data:sub(pos)
            pos = #data + 1
            return out
        end
        if type(fmt) == "number" then
            if fmt <= 0 then return "" end
            local out = data:sub(pos, pos + fmt - 1)
            pos = pos + #out
            return #out > 0 and out or nil
        end
        if fmt == "*l" then
            if pos > #data then return nil end
            local next_line = data:find("\n", pos, true)
            local out
            if next_line then
                out = data:sub(pos, next_line - 1):gsub("\r$", "")
                pos = next_line + 1
            else
                out = data:sub(pos)
                pos = #data + 1
            end
            return out
        end
        return nil
    end
    function file:close() return true end
    function file:seek(whence, offset)
        offset = tonumber(offset) or 0
        if whence == nil or whence == "cur" then
            pos = pos + offset
        elseif whence == "set" then
            pos = offset + 1
        elseif whence == "end" then
            pos = #data + offset + 1
        else
            return nil, "invalid whence"
        end
        if pos < 1 then pos = 1 end
        if pos > #data + 1 then pos = #data + 1 end
        return pos - 1
    end
    return file
end

local function archive_loader(path)
    if __bp_read_resource == nil then return nil end
    return __bp_read_resource(normalize_path(path))
end

local function is_archive_path(path)
    return tostring(path or ""):sub(1, 10) == "archive://"
end

io.open = function(path, mode)
    mode = mode or "r"
    if type(path) == "string" and not mode:find("[wa+]", 1, false) then
        local data = archive_loader(path)
        if data ~= nil then return memory_file(data) end
    end
    return raw_io_open(path, mode)
end

local function resource_options(extra)
    extra = extra or {}
    extra.resource_streams = extra.resource_streams or extra.resourceStreams or { __loader = archive_loader }
    extra.resource_streams.__loader = extra.resource_streams.__loader or archive_loader
    return extra
end

local function is_idle_group(name)
    return string.find(string.lower(tostring(name or "")), "idle", 1, true) ~= nil
end

local function choose_default_group(names)
    local fallback = nil
    for _, name in ipairs(names) do
        local lower = string.lower(tostring(name))
        if lower == "idle" or lower == "idle01" then return name end
        if fallback == nil and is_idle_group(name) then fallback = name end
    end
    return fallback
end

local function append_action_group(target, name, seen)
    if name == nil or is_idle_group(name) or seen[name] then return end
    seen[name] = true
    target[#target + 1] = name
end

local function build_action_groups(names)
    local by_name = {}
    for _, name in ipairs(names) do by_name[name] = true end

    local result = {}
    local seen = {}
    for _, preferred in ipairs(ACTION_GROUP_ORDER) do
        if by_name[preferred] then append_action_group(result, preferred, seen) end
    end

    table.sort(names, function(a, b) return tostring(a) < tostring(b) end)
    for _, name in ipairs(names) do append_action_group(result, name, seen) end
    return result
end

local function collect_moc3_groups()
    groups = {}
    default_group = nil
    local motions = {}
    if renderer.model_info then
        motions = renderer:model_info().motions or {}
    elseif renderer.get_model_data then
        local refs = renderer:get_model_data() and renderer:get_model_data().file_references
        motions = (refs and refs.motions) or {}
    end
    local names = {}
    for name, _ in pairs(motions) do
        names[#names + 1] = name
    end
    default_group = choose_default_group(names)
    groups = build_action_groups(names)
end

local function collect_moc_groups()
    groups = {}
    default_group = nil
    local names = {}
    local model = renderer.get_model and renderer:get_model() or nil
    local model_setting = model and model.modelSetting or nil
    if model_setting and model_setting.getMotionNames then
        names = model_setting:getMotionNames() or {}
    end
    default_group = choose_default_group(names)
    groups = build_action_groups(names)
    if #groups == 0 then
        groups = {"tap_body", "tap_head", "angry01", "bye01", "kime01", "smile01", "nf01", "nnf01"}
    end
end

local function is_motion_finished()
    if not renderer then return true end
    if renderer.is_motion_finished then return renderer:is_motion_finished() end
    local model = renderer.get_model and renderer:get_model() or nil
    local manager = model and model.mainMotionManager or nil
    return manager == nil or manager:isFinished()
end

local function start_motion(group, loop, priority)
    if not renderer or group == nil then return false end
    if model_is_moc3 then
        renderer:start_motion(group, 0, priority, loop)
        return true
    end

    local model = renderer.get_model and renderer:get_model() or nil
    if model and model.modelSetting and model.modelSetting.getMotionFile then
        local file = model.modelSetting:getMotionFile(group, 0)
        if file == nil or file == "" then return false end
        model:StartMotion(group, 0, priority or 3)
        local motion = model.motions and model.motions[tostring(group) .. "#0"] or nil
        if motion ~= nil then motion.loop = loop == true end
        return true
    end

    renderer:start_motion(group, 0, priority)
    return true
end

local function start_default_motion()
    if default_group == nil then
        if renderer and renderer.clear_motions then renderer:clear_motions() end
        active_motion_kind = nil
        return
    end
    if start_motion(default_group, true, 1) then
        active_motion_kind = "default"
    end
end

function __bp_load(path, w, h)
    width = w
    height = h
    active_motion_kind = nil
    if ends_with(path, ".model3.json") then
        model_is_moc3 = true
        local embed = require("live2d_moc3_pet_embed")
        renderer = embed.new(width, height)
        renderer:load_model(path, width, height, is_archive_path(path) and resource_options() or nil)
        collect_moc3_groups()
    else
        model_is_moc3 = false
        local embed = require("live2d_embed")
        renderer = embed.new(width, height)
        local opts = { center = false }
        if is_archive_path(path) then opts = resource_options(opts) end
        renderer:load_model(path, width, height, opts)
        collect_moc_groups()
    end
    if renderer.set_offset then renderer:set_offset(offset_x, offset_y) end
    if renderer.set_scale then renderer:set_scale(scale) end
    start_default_motion()
    return true
end

function __bp_resize(w, h)
    width = w
    height = h
    if renderer and renderer.resize then renderer:resize(width, height) end
end

function __bp_touch()
    if not renderer or #groups == 0 then return end
    for _ = 1, #groups do
        local group = groups[group_index]
        group_index = group_index % #groups + 1
        local ok, started = pcall(function() return start_motion(group, false, 3) end)
        if ok and started then
            active_motion_kind = "action"
            return
        end
    end
end

function __bp_transform(x, y, s)
    offset_x = tonumber(x) or 0
    offset_y = tonumber(y) or 0
    scale = tonumber(s) or 1
    if not renderer then return end
    if renderer.set_offset then renderer:set_offset(offset_x, offset_y) end
    if renderer.set_scale then renderer:set_scale(scale) end
end

function __bp_draw(time_msec)
    if not renderer then return end
    gl.glViewport(0, 0, width, height)
    renderer:draw({ r = 0.0, g = 0.0, b = 0.0, a = 0.0, time_msec = time_msec })
    if active_motion_kind == "action" and is_motion_finished() then
        start_default_motion()
    end
end
)lua";
    return runLua(renderer, bootstrap);
}

static void renderLoop(Renderer* renderer) {
    if (!initEgl(renderer) || !initLua(renderer)) return;

    while (renderer->running.load()) {
        const auto frameStart = std::chrono::steady_clock::now();
        std::string model;
        std::unordered_map<std::string, std::string> resources;
        bool shouldTouch = renderer->pendingTouch.exchange(false);
        bool shouldResize = renderer->pendingResize.exchange(false);
        bool shouldTransform = renderer->pendingTransform.exchange(false);
        bool shouldUpdateRenderOptions = renderer->pendingRenderOptions.exchange(false);
        int width = renderer->width.load();
        int height = renderer->height.load();
        {
            std::lock_guard<std::mutex> lock(renderer->mutex);
            model.swap(renderer->pendingModel);
            resources.swap(renderer->pendingResources);
        }

        if (shouldUpdateRenderOptions) {
            eglSwapInterval(renderer->display, renderer->vsyncEnabled.load() ? 1 : 0);
        }
        if (shouldResize) {
            getGlobal(renderer, "__bp_resize");
            renderer->luaApi.pushNumber(renderer->lua, width);
            renderer->luaApi.pushNumber(renderer->lua, height);
            callLua(renderer, "__bp_resize", 2);
        }
        if (!model.empty()) {
            renderer->resources = std::move(resources);
            getGlobal(renderer, "__bp_load");
            renderer->luaApi.pushString(renderer->lua, model.c_str());
            renderer->luaApi.pushNumber(renderer->lua, width);
            renderer->luaApi.pushNumber(renderer->lua, height);
            callLua(renderer, "__bp_load", 3);
        }
        if (shouldTouch) {
            getGlobal(renderer, "__bp_touch");
            callLua(renderer, "__bp_touch", 0);
        }
        if (shouldTransform) {
            getGlobal(renderer, "__bp_transform");
            renderer->luaApi.pushNumber(renderer->lua, renderer->transformOffsetX.load());
            renderer->luaApi.pushNumber(renderer->lua, renderer->transformOffsetY.load());
            renderer->luaApi.pushNumber(renderer->lua, renderer->transformScale.load());
            callLua(renderer, "__bp_transform", 3);
        }

        getGlobal(renderer, "__bp_draw");
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        const auto timeMs = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
        renderer->luaApi.pushNumber(renderer->lua, static_cast<double>(timeMs));
        callLua(renderer, "__bp_draw", 1);
        eglSwapBuffers(renderer->display, renderer->surface);

        const int fpsLimit = renderer->fpsLimit.load();
        if (fpsLimit > 0) {
            const auto frameDuration = std::chrono::nanoseconds(1000000000LL / fpsLimit);
            std::this_thread::sleep_until(frameStart + frameDuration);
        }
    }
}

static void cleanup(Renderer* renderer) {
    if (renderer->lua != nullptr) renderer->luaApi.close(renderer->lua);
    if (renderer->luaApi.handle != nullptr) dlclose(renderer->luaApi.handle);
    if (renderer->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(renderer->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (renderer->context != EGL_NO_CONTEXT) eglDestroyContext(renderer->display, renderer->context);
        if (renderer->surface != EGL_NO_SURFACE) eglDestroySurface(renderer->display, renderer->surface);
        eglTerminate(renderer->display);
    }
    if (renderer->window != nullptr) ANativeWindow_release(renderer->window);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_create(
    JNIEnv* env,
    jobject,
    jobject surface,
    jstring runtimeRoot,
    jint width,
    jint height,
    jint fpsLimit,
    jboolean vsyncEnabled
) {
    auto* renderer = new Renderer();
    renderer->fpsLimit.store(fpsLimit > 0 ? fpsLimit : 60);
    renderer->vsyncEnabled.store(vsyncEnabled == JNI_TRUE);
    const char* runtimeChars = env->GetStringUTFChars(runtimeRoot, nullptr);
    renderer->runtimeRoot = runtimeChars != nullptr ? runtimeChars : "";
    if (runtimeChars != nullptr) env->ReleaseStringUTFChars(runtimeRoot, runtimeChars);
    renderer->window = ANativeWindow_fromSurface(env, surface);
    if (renderer->window == nullptr) {
        delete renderer;
        return 0;
    }
    configureRenderSize(renderer, width, height);
    renderer->renderThread = std::thread(renderLoop, renderer);
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_resize(JNIEnv*, jobject, jlong handle, jint width, jint height) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    configureRenderSize(renderer, width, height);
    renderer->pendingResize.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_loadModel(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring modelPath,
    jobjectArray resourcePaths,
    jobjectArray resourceBytes
) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return JNI_FALSE;
    const char* modelChars = env->GetStringUTFChars(modelPath, nullptr);
    std::unordered_map<std::string, std::string> resources;
    if (resourcePaths != nullptr && resourceBytes != nullptr) {
        const jsize pathCount = env->GetArrayLength(resourcePaths);
        const jsize byteCount = env->GetArrayLength(resourceBytes);
        const jsize count = pathCount < byteCount ? pathCount : byteCount;
        for (jsize i = 0; i < count; ++i) {
            auto pathString = static_cast<jstring>(env->GetObjectArrayElement(resourcePaths, i));
            auto byteArray = static_cast<jbyteArray>(env->GetObjectArrayElement(resourceBytes, i));
            if (pathString != nullptr && byteArray != nullptr) {
                const char* pathChars = env->GetStringUTFChars(pathString, nullptr);
                const jsize length = env->GetArrayLength(byteArray);
                std::string bytes;
                bytes.resize(static_cast<size_t>(length));
                if (length > 0) {
                    env->GetByteArrayRegion(byteArray, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
                }
                resources[normalizeResourcePath(pathChars)] = std::move(bytes);
                if (pathChars != nullptr) env->ReleaseStringUTFChars(pathString, pathChars);
            }
            if (pathString != nullptr) env->DeleteLocalRef(pathString);
            if (byteArray != nullptr) env->DeleteLocalRef(byteArray);
        }
    }
    {
        std::lock_guard<std::mutex> lock(renderer->mutex);
        renderer->pendingModel = modelChars != nullptr ? modelChars : "";
        renderer->pendingResources = std::move(resources);
        renderer->lastError.clear();
    }
    if (modelChars != nullptr) env->ReleaseStringUTFChars(modelPath, modelChars);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_setRenderOptions(JNIEnv*, jobject, jlong handle, jint fpsLimit, jboolean vsyncEnabled) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->fpsLimit.store(fpsLimit > 0 ? fpsLimit : 60);
    renderer->vsyncEnabled.store(vsyncEnabled == JNI_TRUE);
    renderer->pendingRenderOptions.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_setTransform(JNIEnv*, jobject, jlong handle, jfloat offsetX, jfloat offsetY, jfloat scale) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->transformOffsetX.store(offsetX);
    renderer->transformOffsetY.store(offsetY);
    renderer->transformScale.store(scale);
    renderer->pendingTransform.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_touch(JNIEnv*, jobject, jlong handle, jfloat, jfloat) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer != nullptr) renderer->pendingTouch.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_lastError(JNIEnv* env, jobject, jlong handle) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(renderer->mutex);
    return env->NewStringUTF(renderer->lastError.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_destroy(JNIEnv*, jobject, jlong handle) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->running.store(false);
    if (renderer->renderThread.joinable()) renderer->renderThread.join();
    cleanup(renderer);
    delete renderer;
}
