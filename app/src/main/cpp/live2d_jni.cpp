#include <GLES2/gl2.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <sstream>
#include <string>

#define LOG_TAG "BandoriLive2D"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int LUA_GLOBALSINDEX = -10002;
constexpr int LUA_TFUNCTION = 6;

struct lua_State;

struct LuaApi {
    void* handle = nullptr;
    lua_State* (*luaL_newstate)() = nullptr;
    void (*luaL_openlibs)(lua_State*) = nullptr;
    void (*lua_close)(lua_State*) = nullptr;
    int (*luaL_loadstring)(lua_State*, const char*) = nullptr;
    int (*lua_pcall)(lua_State*, int, int, int) = nullptr;
    void (*lua_getfield)(lua_State*, int, const char*) = nullptr;
    void (*lua_setfield)(lua_State*, int, const char*) = nullptr;
    void (*lua_pushstring)(lua_State*, const char*) = nullptr;
    void (*lua_pushnumber)(lua_State*, double) = nullptr;
    void (*lua_pushboolean)(lua_State*, int) = nullptr;
    const char* (*lua_tolstring)(lua_State*, int, size_t*) = nullptr;
    int (*lua_type)(lua_State*, int) = nullptr;
    void (*lua_settop)(lua_State*, int) = nullptr;

    bool load(std::string& error) {
        if (handle != nullptr) return true;
        const char* candidates[] = {
            "libluajit.so",
            "libluajit-5.1.so",
            "liblua51.so",
        };
        for (const char* name : candidates) {
            handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
            if (handle != nullptr) break;
        }
        if (handle == nullptr) {
            error = dlerror() ? dlerror() : "dlopen libluajit.so failed";
            return false;
        }

        bool ok = true;
        ok &= bind(luaL_newstate, "luaL_newstate", error);
        ok &= bind(luaL_openlibs, "luaL_openlibs", error);
        ok &= bind(lua_close, "lua_close", error);
        ok &= bind(luaL_loadstring, "luaL_loadstring", error);
        ok &= bind(lua_pcall, "lua_pcall", error);
        ok &= bind(lua_getfield, "lua_getfield", error);
        ok &= bind(lua_setfield, "lua_setfield", error);
        ok &= bind(lua_pushstring, "lua_pushstring", error);
        ok &= bind(lua_pushnumber, "lua_pushnumber", error);
        ok &= bind(lua_pushboolean, "lua_pushboolean", error);
        ok &= bind(lua_tolstring, "lua_tolstring", error);
        ok &= bind(lua_type, "lua_type", error);
        ok &= bind(lua_settop, "lua_settop", error);
        return ok;
    }

    template <typename T>
    bool bind(T& out, const char* symbol, std::string& error) {
        out = reinterpret_cast<T>(dlsym(handle, symbol));
        if (out == nullptr) {
            error = std::string("missing LuaJIT symbol: ") + symbol;
            return false;
        }
        return true;
    }
};

class GlesAdapter {
public:
    void onSurfaceCreated() {
        if (program_ == 0) {
            const char* vertex = "attribute vec4 a_position; void main(){ gl_Position = a_position; }";
            const char* fragment = "precision mediump float; void main(){ gl_FragColor = vec4(0.0); }";
            GLuint vs = compile(GL_VERTEX_SHADER, vertex);
            GLuint fs = compile(GL_FRAGMENT_SHADER, fragment);
            if (vs != 0 && fs != 0) {
                program_ = glCreateProgram();
                glAttachShader(program_, vs);
                glAttachShader(program_, fs);
                glLinkProgram(program_);
                GLint linked = GL_FALSE;
                glGetProgramiv(program_, GL_LINK_STATUS, &linked);
                if (linked != GL_TRUE) {
                    char log[512] = {};
                    glGetProgramInfoLog(program_, sizeof(log), nullptr, log);
                    LOGE("GLES adapter link failed: %s", log);
                    glDeleteProgram(program_);
                    program_ = 0;
                }
            }
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
        }
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    void onSurfaceChanged(int width, int height) {
        width_ = std::max(1, width);
        height_ = std::max(1, height);
        glViewport(0, 0, width_, height_);
    }

    void clear() const {
        glViewport(0, 0, width_, height_);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    }

private:
    GLuint compile(GLenum type, const char* source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled != GL_TRUE) {
            char log[512] = {};
            glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
            LOGE("GLES adapter shader compile failed: %s", log);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    GLuint program_ = 0;
    int width_ = 1;
    int height_ = 1;
};

class LuaHost {
public:
    bool init(const std::string& runtimeRoot) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!api_.load(lastError_)) {
            LOGE("LuaJIT load failed: %s", lastError_.c_str());
            return false;
        }

        if (L_ != nullptr && runtimeRoot_ == runtimeRoot) {
            return callNoArgs("android_surface_created");
        }

        closeLocked();
        L_ = api_.luaL_newstate();
        if (L_ == nullptr) {
            lastError_ = "luaL_newstate failed";
            return false;
        }
        api_.luaL_openlibs(L_);
        runtimeRoot_ = normalize(runtimeRoot);
        api_.lua_pushstring(L_, runtimeRoot_.c_str());
        api_.lua_setfield(L_, LUA_GLOBALSINDEX, "ANDROID_LIVE2D_ROOT");

        if (!runScript(bootstrapScript())) return false;
        return callNoArgs("android_surface_created");
    }

    bool onResize(int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_ = std::max(1, width);
        height_ = std::max(1, height);
        if (L_ == nullptr) return true;
        api_.lua_getfield(L_, LUA_GLOBALSINDEX, "android_resize");
        if (api_.lua_type(L_, -1) != LUA_TFUNCTION) {
            pop(1);
            return true;
        }
        api_.lua_pushnumber(L_, width_);
        api_.lua_pushnumber(L_, height_);
        return pcall(2, 0);
    }

    bool loadModel(const std::string& path) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (L_ == nullptr) {
            lastError_ = "Lua VM is not initialized";
            return false;
        }
        api_.lua_getfield(L_, LUA_GLOBALSINDEX, "android_load_model");
        if (api_.lua_type(L_, -1) != LUA_TFUNCTION) {
            pop(1);
            lastError_ = "android_load_model is missing";
            return false;
        }
        const std::string normalized = normalize(path);
        api_.lua_pushstring(L_, normalized.c_str());
        return pcall(1, 0);
    }

    bool renderFrame() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (L_ == nullptr) return false;
        api_.lua_getfield(L_, LUA_GLOBALSINDEX, "android_render_frame");
        if (api_.lua_type(L_, -1) != LUA_TFUNCTION) {
            pop(1);
            return false;
        }
        return pcall(0, 0);
    }

    bool triggerMotion(const std::string& name) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (L_ == nullptr) return false;
        api_.lua_getfield(L_, LUA_GLOBALSINDEX, "android_trigger_motion");
        if (api_.lua_type(L_, -1) != LUA_TFUNCTION) {
            pop(1);
            return false;
        }
        api_.lua_pushstring(L_, name.c_str());
        return pcall(1, 0);
    }

    bool setLipSync(float value) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (L_ == nullptr) return false;
        api_.lua_getfield(L_, LUA_GLOBALSINDEX, "android_set_lip_sync");
        if (api_.lua_type(L_, -1) != LUA_TFUNCTION) {
            pop(1);
            return false;
        }
        api_.lua_pushnumber(L_, std::clamp(value, 0.0f, 1.0f));
        return pcall(1, 0);
    }

    bool dispose() {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();
        return true;
    }

    std::string lastError() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return lastError_;
    }

private:
    bool runScript(const std::string& script) {
        if (api_.luaL_loadstring(L_, script.c_str()) != 0) {
            captureError("luaL_loadstring");
            return false;
        }
        return pcall(0, 0);
    }

    bool callNoArgs(const char* name) {
        if (L_ == nullptr) return false;
        api_.lua_getfield(L_, LUA_GLOBALSINDEX, name);
        if (api_.lua_type(L_, -1) != LUA_TFUNCTION) {
            pop(1);
            return true;
        }
        return pcall(0, 0);
    }

    bool pcall(int nargs, int nresults) {
        if (api_.lua_pcall(L_, nargs, nresults, 0) != 0) {
            captureError("lua_pcall");
            return false;
        }
        if (nresults > 0) pop(nresults);
        lastError_.clear();
        return true;
    }

    void captureError(const char* prefix) {
        size_t len = 0;
        const char* message = api_.lua_tolstring(L_, -1, &len);
        lastError_ = std::string(prefix) + ": " + (message != nullptr ? std::string(message, len) : "unknown Lua error");
        LOGE("%s", lastError_.c_str());
        pop(1);
    }

    void pop(int count) {
        api_.lua_settop(L_, -count - 1);
    }

    void closeLocked() {
        if (L_ != nullptr) {
            api_.lua_close(L_);
            L_ = nullptr;
        }
    }

    static std::string normalize(std::string value) {
        std::replace(value.begin(), value.end(), '\\', '/');
        return value;
    }

    static std::string bootstrapScript() {
        return R"lua(
local root = tostring(ANDROID_LIVE2D_ROOT or ''):gsub('\\', '/')
package.path = table.concat({
  root .. '/live2d-lua/?.lua',
  root .. '/live2d-lua/?/init.lua',
  root .. '/live2d-lua/?/?.lua',
  package.path,
}, ';')

local ffi = require('ffi')
local gl = require('live2d.gl_loader')
local state = { renderer = nil, kind = nil, width = 1, height = 1, last_time = os.clock() }

local function is_moc3(path)
  local lower = tostring(path):lower()
  return lower:match('%.model3%.json$') ~= nil or lower:match('%.moc3$') ~= nil
end

local function clear_screen()
  gl.glViewport(0, 0, state.width, state.height)
  gl.glClearColor(0.0, 0.0, 0.0, 0.0)
  gl.glClear(0x00004000 + 0x00000400)
end

local function projection_for_moc3()
  local canvas = state.renderer:get_runtime().canvas
  local model_w = canvas.width / canvas.pixels_per_unit
  local model_h = canvas.height / canvas.pixels_per_unit
  local scale = math.min(state.width / model_w, state.height / model_h) * 0.86
  return ffi.new('float[16]', {
    scale * 2.0 / state.width, 0, 0, 0,
    0, scale * 2.0 / state.height, 0, 0,
    0, 0, 1, 0,
    0, 0, 0, 1,
  })
end

function android_surface_created()
  gl.ensureExtensions()
  gl.glDisable(0x0B71)
  gl.glEnable(0x0BE2)
  if gl.glBlendFunc then gl.glBlendFunc(0x0302, 0x0303) end
  clear_screen()
  return true
end

function android_resize(width, height)
  state.width = math.max(tonumber(width) or 1, 1)
  state.height = math.max(tonumber(height) or 1, 1)
  gl.glViewport(0, 0, state.width, state.height)
  if state.renderer ~= nil and state.kind == 'moc' then
    state.renderer:resize(state.width, state.height)
  end
  return true
end

function android_load_model(path)
  path = tostring(path):gsub('\\', '/')
  if state.renderer ~= nil and state.renderer.dispose ~= nil then pcall(function() state.renderer:dispose() end) end
  if is_moc3(path) then
    local embed = require('live2d_moc3_embed')
    local renderer = embed.new({ gl = gl })
    renderer:load_model(path, {})
    state.renderer = renderer
    state.kind = 'moc3'
  else
    local embed = require('live2d_embed')
    local renderer = embed.new(state.width, state.height, {})
    renderer:load_model(path, state.width, state.height, { center = true, auto_breath = true, auto_blink = true })
    state.renderer = renderer
    state.kind = 'moc'
  end
  state.last_time = os.clock()
  return true
end

function android_render_frame()
  clear_screen()
  if state.renderer == nil then return false end
  local now = os.clock()
  local delta = math.min(math.max(now - state.last_time, 0.0), 0.05)
  state.last_time = now
  if state.kind == 'moc3' then
    state.renderer:update(delta)
    state.renderer:render(projection_for_moc3())
  else
    state.renderer:draw({ clear = false })
  end
  return true
end

function android_trigger_motion(name)
  if state.renderer == nil then return false end
  name = tostring(name or 'idle01')
  if state.kind == 'moc3' then
    pcall(function() state.renderer:start_motion(name, 0, 1.0, false) end)
    pcall(function() state.renderer:set_expression(name) end)
  else
    pcall(function() state.renderer:start_motion(name, 0) end)
    pcall(function() state.renderer:set_expression(name) end)
  end
  return true
end

function android_set_lip_sync(value)
  if state.renderer == nil then return false end
  value = math.max(0.0, math.min(tonumber(value) or 0.0, 1.0))
  pcall(function() state.renderer:set_parameter('ParamMouthOpenY', value) end)
  pcall(function() state.renderer:set_parameter('PARAM_MOUTH_OPEN_Y', value, 1.0) end)
  return true
end
)lua";
    }

    mutable std::mutex mutex_;
    LuaApi api_;
    lua_State* L_ = nullptr;
    std::string runtimeRoot_;
    std::string lastError_;
    int width_ = 1;
    int height_ = 1;
};

GlesAdapter gles;
LuaHost luaHost;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeInitLive2D(JNIEnv* env, jclass, jstring runtimeRoot) {
    return luaHost.init(toString(env, runtimeRoot)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeOnSurfaceCreated(JNIEnv*, jclass) {
    gles.onSurfaceCreated();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeOnSurfaceChanged(JNIEnv*, jclass, jint width, jint height) {
    gles.onSurfaceChanged(width, height);
    luaHost.onResize(width, height);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeLoadModel(JNIEnv* env, jclass, jstring path) {
    return luaHost.loadModel(toString(env, path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeRenderFrame(JNIEnv*, jclass) {
    if (!luaHost.renderFrame()) {
        gles.clear();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeTriggerMotion(JNIEnv* env, jclass, jstring name) {
    return luaHost.triggerMotion(toString(env, name)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeSetLipSync(JNIEnv*, jclass, jfloat value) {
    return luaHost.setLipSync(value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeDispose(JNIEnv*, jclass) {
    return luaHost.dispose() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bandori_pet_android_NativeLive2D_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(luaHost.lastError().c_str());
}
