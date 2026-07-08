#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstddef>
#include <mutex>
#include <string>
#include <thread>

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

struct LuaApi {
    void* handle = nullptr;
    lua_State* (*newState)() = nullptr;
    void (*openLibs)(lua_State*) = nullptr;
    int (*loadString)(lua_State*, const char*) = nullptr;
    int (*pcall)(lua_State*, int, int, int) = nullptr;
    void (*close)(lua_State*) = nullptr;
    void (*getField)(lua_State*, int, const char*) = nullptr;
    void (*pushString)(lua_State*, const char*) = nullptr;
    void (*pushNumber)(lua_State*, double) = nullptr;
    void (*setField)(lua_State*, int, const char*) = nullptr;
    const char* (*toString)(lua_State*, int, size_t*) = nullptr;
    void (*setTop)(lua_State*, int) = nullptr;
};

static constexpr int LUA_GLOBALSINDEX_COMPAT = -10002;

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
    std::string lastError;
    std::mutex mutex;
    std::thread renderThread;
    std::atomic<bool> running{true};
    std::atomic<bool> pendingResize{false};
    std::atomic<bool> pendingTouch{false};
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
        && loadSymbol(api.handle, "lua_pushnumber", api.pushNumber)
        && loadSymbol(api.handle, "lua_setfield", api.setField)
        && loadSymbol(api.handle, "lua_tolstring", api.toString)
        && loadSymbol(api.handle, "lua_settop", api.setTop);
    if (!ok) {
        setError(renderer, "LuaJIT 导出符号不完整");
        return false;
    }
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
    eglSwapInterval(renderer->display, 1);
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

    static const char* bootstrap = R"lua(
package.path = __bp_runtime_root .. "/?.lua;" .. __bp_runtime_root .. "/?/init.lua;" .. package.path
package.cpath = __bp_runtime_root .. "/?.so;" .. package.cpath
local gl = require("live2d.gl_loader")
if gl.ensureExtensions then gl.ensureExtensions() end
local renderer = nil
local width = 1
local height = 1
local groups = {}
local group_index = 1

local function ends_with(value, suffix)
    return value:sub(-#suffix) == suffix
end

local function collect_moc3_groups()
    groups = {}
    local motions = {}
    if renderer.model_info then
        motions = renderer:model_info().motions or {}
    elseif renderer.get_model_data then
        local refs = renderer:get_model_data() and renderer:get_model_data().file_references
        motions = (refs and refs.motions) or {}
    end
    for name, _ in pairs(motions) do
        if not string.find(string.lower(name), "idle") then groups[#groups + 1] = name end
    end
end

local function collect_moc_groups()
    groups = {"tap_body", "tap_head", "angry01", "bye01", "kime01", "smile01", "nf01", "nnf01"}
end

function __bp_load(path, w, h)
    width = w
    height = h
    if ends_with(path, ".model3.json") then
        local embed = require("live2d_moc3_pet_embed")
        renderer = embed.new(width, height)
        renderer:load_model(path, width, height)
        collect_moc3_groups()
    else
        local embed = require("live2d_embed")
        renderer = embed.new(width, height)
        renderer:load_model(path, width, height, { center = false })
        collect_moc_groups()
    end
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
        local ok = pcall(function() renderer:start_motion(group, 0) end)
        if ok then return end
    end
end

function __bp_draw(time_msec)
    if not renderer then return end
    gl.glViewport(0, 0, width, height)
    renderer:draw({ r = 0.0, g = 0.0, b = 0.0, a = 0.0, time_msec = time_msec })
end
)lua";
    return runLua(renderer, bootstrap);
}

static void renderLoop(Renderer* renderer) {
    if (!initEgl(renderer) || !initLua(renderer)) return;

    while (renderer->running.load()) {
        std::string model;
        bool shouldTouch = renderer->pendingTouch.exchange(false);
        bool shouldResize = renderer->pendingResize.exchange(false);
        int width = renderer->width.load();
        int height = renderer->height.load();
        {
            std::lock_guard<std::mutex> lock(renderer->mutex);
            model.swap(renderer->pendingModel);
        }

        if (shouldResize) {
            getGlobal(renderer, "__bp_resize");
            renderer->luaApi.pushNumber(renderer->lua, width);
            renderer->luaApi.pushNumber(renderer->lua, height);
            callLua(renderer, "__bp_resize", 2);
        }
        if (!model.empty()) {
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

        getGlobal(renderer, "__bp_draw");
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        const auto timeMs = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
        renderer->luaApi.pushNumber(renderer->lua, static_cast<double>(timeMs));
        callLua(renderer, "__bp_draw", 1);
        eglSwapBuffers(renderer->display, renderer->surface);
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
Java_com_bandori_pet_live2d_NativeLive2D_create(JNIEnv* env, jobject, jobject surface, jstring runtimeRoot, jint width, jint height) {
    auto* renderer = new Renderer();
    const char* runtimeChars = env->GetStringUTFChars(runtimeRoot, nullptr);
    renderer->runtimeRoot = runtimeChars != nullptr ? runtimeChars : "";
    env->ReleaseStringUTFChars(runtimeRoot, runtimeChars);
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
Java_com_bandori_pet_live2d_NativeLive2D_loadModel(JNIEnv* env, jobject, jlong handle, jstring modelPath) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return JNI_FALSE;
    const char* modelChars = env->GetStringUTFChars(modelPath, nullptr);
    {
        std::lock_guard<std::mutex> lock(renderer->mutex);
        renderer->pendingModel = modelChars != nullptr ? modelChars : "";
        renderer->lastError.clear();
    }
    env->ReleaseStringUTFChars(modelPath, modelChars);
    return JNI_TRUE;
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
