#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#ifndef EGL_OPENGL_ES_API
#define EGL_OPENGL_ES_API 0x30A0
#endif

#ifndef EGL_OPENGL_ES2_BIT
#define EGL_OPENGL_ES2_BIT 0x0004
#endif

#ifndef EGL_CONTEXT_CLIENT_VERSION
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BandoriPet", __VA_ARGS__)

static constexpr float GAZE_FOLLOW_EASING_PER_SECOND = 8.0f;

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
    std::atomic<int> surfaceWidth{1};
    std::atomic<int> surfaceHeight{1};
    std::atomic<int> width{1};
    std::atomic<int> height{1};
    std::atomic<float> renderScale{1.0f};

    LuaApi luaApi;
    lua_State* lua = nullptr;
    std::string runtimeRoot;
    std::string pendingModel;
    std::string pendingAction;
    std::unordered_map<std::string, std::string> pendingResources;
    bool pendingBackground = false;
    int pendingBackgroundWidth = 0;
    int pendingBackgroundHeight = 0;
    std::vector<uint32_t> pendingBackgroundPixels;
    std::unordered_map<std::string, std::string> resources;
    std::string lastError;
    std::mutex mutex;
    std::thread renderThread;
    std::atomic<bool> running{true};
    std::atomic<bool> pendingResize{false};
    std::atomic<bool> pendingTouch{false};
    std::atomic<float> touchXRatio{0.5f};
    std::atomic<float> touchYRatio{0.5f};
    std::atomic<bool> pendingLookAt{false};
    std::atomic<float> lookAtXRatio{0.5f};
    std::atomic<float> lookAtYRatio{0.5f};
    bool lookAtActive = false;
    float smoothedLookAtXRatio = 0.5f;
    float smoothedLookAtYRatio = 0.5f;
    std::atomic<bool> pendingTransform{false};
    std::atomic<bool> pendingRenderOptions{false};
    std::atomic<int> fpsLimit{60};
    std::atomic<bool> vsyncEnabled{true};
    std::atomic<bool> fpsDisplayEnabled{false};
    std::atomic<float> transformOffsetX{0.0f};
    std::atomic<float> transformOffsetY{0.0f};
    std::atomic<float> transformScale{1.0f};
    GLuint backgroundTexture = 0;
    GLuint backgroundProgram = 0;
    GLuint backgroundBuffer = 0;
    GLint backgroundPositionAttrib = -1;
    GLint backgroundTexCoordAttrib = -1;
    GLint backgroundSamplerUniform = -1;
    int backgroundWidth = 0;
    int backgroundHeight = 0;
    bool backgroundEnabled = false;
    GLuint fpsProgram = 0;
    GLuint fpsBuffer = 0;
    GLint fpsPositionAttrib = -1;
    GLint fpsColorUniform = -1;
    GLint fpsOffsetUniform = -1;
};

static int positiveSize(int value) {
    return value > 0 ? value : 1;
}

static void configureRenderSize(Renderer* renderer, int surfaceWidth, int surfaceHeight) {
    const int srcWidth = positiveSize(surfaceWidth);
    const int srcHeight = positiveSize(surfaceHeight);
    const float scale = std::clamp(renderer->renderScale.load(), 0.5f, 2.0f);
    const int renderWidth = std::max(1, static_cast<int>(std::lround(srcWidth * scale)));
    const int renderHeight = std::max(1, static_cast<int>(std::lround(srcHeight * scale)));
    renderer->surfaceWidth.store(srcWidth);
    renderer->surfaceHeight.store(srcHeight);
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

static GLuint compileBackgroundShader(Renderer* renderer, GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512] = {0};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        setError(renderer, std::string("背景 shader 编译失败: ") + log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static bool ensureBackgroundProgram(Renderer* renderer) {
    if (renderer->backgroundProgram != 0) return true;

    static const char* vertexSource = R"glsl(
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";
    static const char* fragmentSource = R"glsl(
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTexCoord;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    gl_FragColor = color.bgra;
}
)glsl";

    GLuint vertexShader = compileBackgroundShader(renderer, GL_VERTEX_SHADER, vertexSource);
    if (vertexShader == 0) return false;
    GLuint fragmentShader = compileBackgroundShader(renderer, GL_FRAGMENT_SHADER, fragmentSource);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return false;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {0};
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        setError(renderer, std::string("背景 shader 链接失败: ") + log);
        glDeleteProgram(program);
        return false;
    }

    renderer->backgroundProgram = program;
    renderer->backgroundPositionAttrib = glGetAttribLocation(program, "aPosition");
    renderer->backgroundTexCoordAttrib = glGetAttribLocation(program, "aTexCoord");
    renderer->backgroundSamplerUniform = glGetUniformLocation(program, "uTexture");
    glGenBuffers(1, &renderer->backgroundBuffer);

    const GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
    };
    glBindBuffer(GL_ARRAY_BUFFER, renderer->backgroundBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return renderer->backgroundPositionAttrib >= 0
        && renderer->backgroundTexCoordAttrib >= 0
        && renderer->backgroundSamplerUniform >= 0
        && renderer->backgroundBuffer != 0;
}

static void destroyBackgroundResources(Renderer* renderer) {
    if (renderer->backgroundTexture != 0) {
        glDeleteTextures(1, &renderer->backgroundTexture);
        renderer->backgroundTexture = 0;
    }
    if (renderer->backgroundBuffer != 0) {
        glDeleteBuffers(1, &renderer->backgroundBuffer);
        renderer->backgroundBuffer = 0;
    }
    if (renderer->backgroundProgram != 0) {
        glDeleteProgram(renderer->backgroundProgram);
        renderer->backgroundProgram = 0;
    }
    renderer->backgroundEnabled = false;
    renderer->backgroundWidth = 0;
    renderer->backgroundHeight = 0;
}

static void uploadBackground(Renderer* renderer, const std::vector<uint32_t>& pixels, int width, int height) {
    if (pixels.empty() || width <= 0 || height <= 0) {
        if (renderer->backgroundTexture != 0) {
            glDeleteTextures(1, &renderer->backgroundTexture);
            renderer->backgroundTexture = 0;
        }
        renderer->backgroundEnabled = false;
        renderer->backgroundWidth = 0;
        renderer->backgroundHeight = 0;
        return;
    }

    if (renderer->backgroundTexture == 0) glGenTextures(1, &renderer->backgroundTexture);
    if (renderer->backgroundTexture == 0) return;

    glBindTexture(GL_TEXTURE_2D, renderer->backgroundTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA,
        width,
        height,
        0,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixels.data()
    );
    glBindTexture(GL_TEXTURE_2D, 0);
    renderer->backgroundEnabled = true;
    renderer->backgroundWidth = width;
    renderer->backgroundHeight = height;
}

static void drawBackground(Renderer* renderer) {
    if (!renderer->backgroundEnabled || renderer->backgroundTexture == 0) return;
    if (!ensureBackgroundProgram(renderer)) return;

    const float surfaceAspect = static_cast<float>(renderer->width.load()) / static_cast<float>(positiveSize(renderer->height.load()));
    const float imageAspect = static_cast<float>(positiveSize(renderer->backgroundWidth)) / static_cast<float>(positiveSize(renderer->backgroundHeight));
    float u0 = 0.0f;
    float u1 = 1.0f;
    float v0 = 0.0f;
    float v1 = 1.0f;
    if (imageAspect > surfaceAspect) {
        const float visibleWidth = surfaceAspect / imageAspect;
        const float inset = (1.0f - visibleWidth) * 0.5f;
        u0 = inset;
        u1 = 1.0f - inset;
    } else if (imageAspect < surfaceAspect) {
        const float visibleHeight = imageAspect / surfaceAspect;
        const float inset = (1.0f - visibleHeight) * 0.5f;
        v0 = inset;
        v1 = 1.0f - inset;
    }

    const GLfloat vertices[] = {
        -1.0f, -1.0f, u0, v1,
         1.0f, -1.0f, u1, v1,
        -1.0f,  1.0f, u0, v0,
         1.0f,  1.0f, u1, v0,
    };

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glUseProgram(renderer->backgroundProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, renderer->backgroundTexture);
    glUniform1i(renderer->backgroundSamplerUniform, 0);
    glBindBuffer(GL_ARRAY_BUFFER, renderer->backgroundBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(renderer->backgroundPositionAttrib);
    glEnableVertexAttribArray(renderer->backgroundTexCoordAttrib);
    glVertexAttribPointer(renderer->backgroundPositionAttrib, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
    glVertexAttribPointer(renderer->backgroundTexCoordAttrib, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(renderer->backgroundPositionAttrib);
    glDisableVertexAttribArray(renderer->backgroundTexCoordAttrib);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
}

static GLuint compileFpsShader(Renderer* renderer, GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512] = {0};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        setError(renderer, std::string("FPS shader 编译失败: ") + log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static bool ensureFpsProgram(Renderer* renderer) {
    if (renderer->fpsProgram != 0) return true;

    static const char* vertexSource = R"glsl(
attribute vec2 aPosition;
uniform vec2 uOffset;
void main() {
    gl_Position = vec4(aPosition + uOffset, 0.0, 1.0);
}
)glsl";
    static const char* fragmentSource = R"glsl(
precision mediump float;
uniform vec4 uColor;
void main() {
    gl_FragColor = uColor;
}
)glsl";

    GLuint vertexShader = compileFpsShader(renderer, GL_VERTEX_SHADER, vertexSource);
    if (vertexShader == 0) return false;
    GLuint fragmentShader = compileFpsShader(renderer, GL_FRAGMENT_SHADER, fragmentSource);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return false;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {0};
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        setError(renderer, std::string("FPS shader 链接失败: ") + log);
        glDeleteProgram(program);
        return false;
    }

    renderer->fpsProgram = program;
    renderer->fpsPositionAttrib = glGetAttribLocation(program, "aPosition");
    renderer->fpsColorUniform = glGetUniformLocation(program, "uColor");
    renderer->fpsOffsetUniform = glGetUniformLocation(program, "uOffset");
    glGenBuffers(1, &renderer->fpsBuffer);
    return renderer->fpsPositionAttrib >= 0
        && renderer->fpsColorUniform >= 0
        && renderer->fpsOffsetUniform >= 0
        && renderer->fpsBuffer != 0;
}

static void destroyFpsResources(Renderer* renderer) {
    if (renderer->fpsBuffer != 0) {
        glDeleteBuffers(1, &renderer->fpsBuffer);
        renderer->fpsBuffer = 0;
    }
    if (renderer->fpsProgram != 0) {
        glDeleteProgram(renderer->fpsProgram);
        renderer->fpsProgram = 0;
    }
}

static const uint8_t* fpsGlyph(char character) {
    static constexpr uint8_t F[7] = {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10};
    static constexpr uint8_t P[7] = {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10};
    static constexpr uint8_t S[7] = {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E};
    static constexpr uint8_t ZERO[7] = {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E};
    static constexpr uint8_t ONE[7] = {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E};
    static constexpr uint8_t TWO[7] = {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F};
    static constexpr uint8_t THREE[7] = {0x1E, 0x01, 0x01, 0x0E, 0x01, 0x01, 0x1E};
    static constexpr uint8_t FOUR[7] = {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02};
    static constexpr uint8_t FIVE[7] = {0x1F, 0x10, 0x10, 0x1E, 0x01, 0x01, 0x1E};
    static constexpr uint8_t SIX[7] = {0x0E, 0x10, 0x10, 0x1E, 0x11, 0x11, 0x0E};
    static constexpr uint8_t SEVEN[7] = {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08};
    static constexpr uint8_t EIGHT[7] = {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E};
    static constexpr uint8_t NINE[7] = {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x01, 0x0E};
    switch (character) {
        case 'F': return F;
        case 'P': return P;
        case 'S': return S;
        case '0': return ZERO;
        case '1': return ONE;
        case '2': return TWO;
        case '3': return THREE;
        case '4': return FOUR;
        case '5': return FIVE;
        case '6': return SIX;
        case '7': return SEVEN;
        case '8': return EIGHT;
        case '9': return NINE;
        default: return nullptr;
    }
}

static void appendFpsText(
    std::vector<GLfloat>& vertices,
    const std::string& text,
    int surfaceWidth,
    int surfaceHeight,
    float cellSize
) {
    const float pixelToNdcX = 2.0f / static_cast<float>(positiveSize(surfaceWidth));
    const float pixelToNdcY = 2.0f / static_cast<float>(positiveSize(surfaceHeight));
    const float margin = cellSize * 1.25f;
    const float gap = cellSize * 0.12f;
    float originX = margin;
    const float originY = margin;

    for (const char character : text) {
        const uint8_t* glyph = fpsGlyph(character);
        if (glyph != nullptr) {
            for (int row = 0; row < 7; ++row) {
                for (int column = 0; column < 5; ++column) {
                    if ((glyph[row] & (1U << (4 - column))) == 0) continue;
                    const float x0 = -1.0f + (originX + column * cellSize) * pixelToNdcX;
                    const float x1 = -1.0f + (originX + (column + 1) * cellSize - gap) * pixelToNdcX;
                    const float y0 = 1.0f - (originY + row * cellSize) * pixelToNdcY;
                    const float y1 = 1.0f - (originY + (row + 1) * cellSize - gap) * pixelToNdcY;
                    vertices.insert(vertices.end(), {
                        x0, y0, x1, y0, x0, y1,
                        x1, y0, x1, y1, x0, y1,
                    });
                }
            }
        }
        originX += cellSize * 6.0f;
    }
}

static void drawFps(Renderer* renderer, int fps) {
    if (!renderer->fpsDisplayEnabled.load() || !ensureFpsProgram(renderer)) return;

    const int surfaceWidth = positiveSize(renderer->width.load());
    const int surfaceHeight = positiveSize(renderer->height.load());
    const float cellSize = std::clamp(
        std::min(surfaceWidth / 46.0f, surfaceHeight / 32.0f),
        3.0f,
        7.0f
    );
    std::vector<GLfloat> vertices;
    const std::string text = "FPS " + std::to_string(std::clamp(fps, 0, 999));
    vertices.reserve(text.size() * 7 * 5 * 12);
    appendFpsText(vertices, text, surfaceWidth, surfaceHeight, cellSize);
    if (vertices.empty()) return;

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(renderer->fpsProgram);
    glBindBuffer(GL_ARRAY_BUFFER, renderer->fpsBuffer);
    glBufferData(
        GL_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(vertices.size() * sizeof(GLfloat)),
        vertices.data(),
        GL_DYNAMIC_DRAW
    );
    glEnableVertexAttribArray(renderer->fpsPositionAttrib);
    glVertexAttribPointer(
        renderer->fpsPositionAttrib,
        2,
        GL_FLOAT,
        GL_FALSE,
        2 * sizeof(GLfloat),
        reinterpret_cast<void*>(0)
    );
    glUniform2f(renderer->fpsOffsetUniform, 3.0f / surfaceWidth, -3.0f / surfaceHeight);
    glUniform4f(renderer->fpsColorUniform, 0.0f, 0.0f, 0.0f, 0.8f);
    glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices.size() / 2));
    glUniform2f(renderer->fpsOffsetUniform, 0.0f, 0.0f);
    glUniform4f(renderer->fpsColorUniform, 1.0f, 1.0f, 1.0f, 0.95f);
    glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices.size() / 2));
    glDisableVertexAttribArray(renderer->fpsPositionAttrib);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
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
local touch_bucket_indices = {}
local model_is_moc3 = false
local offset_x = 0
local offset_y = 0
local scale = 1
local start_motion = nil

local ACTION_GROUP_ORDER = {
    "surprised01", "shame01", "pui01", "smile01", "kandou01", "kime01",
    "nf_left01", "nnf_left01", "nf_right01", "nnf_right01", "nf01", "nnf01",
    "angry01", "sad01", "tap_body", "tap_head", "bye01",
}

local TOUCH_BUCKETS = {
    head = {
        { "surprised", "shame", "pui", "smile" },
        { "kandou", "kime", "nf" },
    },
    upper_body_left = {
        { "nf_left", "nnf_left" },
        { "shame", "surprised", "smile" },
    },
    upper_body_center = {
        { "smile", "kime", "surprised", "shame" },
        { "angry", "pui", "nf" },
    },
    upper_body_right = {
        { "nf_right", "nnf_right" },
        { "shame", "surprised", "smile" },
    },
    lower_body_left = {
        { "nf_left", "nnf_left" },
        { "surprised", "sad", "smile" },
    },
    lower_body_center = {
        { "shame", "surprised", "angry" },
        { "smile", "kime" },
    },
    lower_body_right = {
        { "nf_right", "nnf_right" },
        { "surprised", "sad", "smile" },
    },
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

local function clamp01(value)
    value = tonumber(value) or 0
    if value < 0 then return 0 end
    if value > 1 then return 1 end
    return value
end

local function classify_x_third(x_ratio)
    if x_ratio < 1 / 3 then return "left" end
    if x_ratio < 2 / 3 then return "center" end
    return "right"
end

local function motion_base_and_side(name)
    local text = string.lower(tostring(name or "")):gsub("\\", "/")
    local token = text:match("([^/]+)$") or text
    token = token:gsub("%.motion3?%.json$", "")
    token = token:gsub("^mtn_", "")

    local side = nil
    local suffix = token:match("_([lcr])$")
    if suffix ~= nil then
        if suffix == "l" then side = "left" end
        if suffix == "c" then side = "center" end
        if suffix == "r" then side = "right" end
        token = token:sub(1, -3)
    end

    token = token:gsub("%d+$", "")
    return token, side
end

local function split_directional_tag(tag)
    local text = string.lower(tostring(tag or ""))
    local base, side = text:match("^(.-)_(left)$")
    if base ~= nil then return base, side end
    base, side = text:match("^(.-)_(center)$")
    if base ~= nil then return base, side end
    base, side = text:match("^(.-)_(right)$")
    if base ~= nil then return base, side end
    return text, nil
end

local function motion_matches_tag(name, tag)
    local base, side = motion_base_and_side(name)
    local tag_base, tag_side = split_directional_tag(tag)
    if tag_side ~= nil then
        return base == tag_base .. "_" .. tag_side or (base == tag_base and side == tag_side)
    end
    return base == tag_base
end

local function candidates_for_tags(tags)
    local result = {}
    local seen = {}
    for _, tag in ipairs(tags or {}) do
        for _, group in ipairs(groups) do
            if not seen[group] and motion_matches_tag(group, tag) then
                seen[group] = true
                result[#result + 1] = group
            end
        end
    end
    return result
end

local function any_hit_is_head_or_face(hit_parts)
    if type(hit_parts) ~= "table" then return false end
    for _, part in pairs(hit_parts) do
        local name = string.lower(tostring(part or ""))
        if name:find("head", 1, true) or name:find("face", 1, true) then
            return true
        end
    end
    return false
end

local function classify_touch_region(x_ratio, y_ratio)
    x_ratio = clamp01(x_ratio)
    y_ratio = clamp01(y_ratio)

    local hit_parts = nil
    if renderer and renderer.hit_test then
        local ok, result = pcall(function() return renderer:hit_test(x_ratio * width, y_ratio * height) end)
        if ok then hit_parts = result end
    end
    if any_hit_is_head_or_face(hit_parts) or y_ratio < 0.38 then
        return "head"
    end

    local column = classify_x_third(x_ratio)
    if y_ratio < 0.64 then
        return "upper_body_" .. column
    end
    return "lower_body_" .. column
end

local function try_start_from_candidates(candidates, key)
    if #candidates == 0 then return false end
    local start_index = touch_bucket_indices[key] or 1
    for offset = 0, #candidates - 1 do
        local index = ((start_index + offset - 1) % #candidates) + 1
        local group = candidates[index]
        local ok, started = pcall(function() return start_motion(group, false, 3) end)
        if ok and started then
            touch_bucket_indices[key] = index % #candidates + 1
            active_motion_kind = "action"
            return true
        end
    end
    return false
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

function start_motion(group, loop, priority)
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
    touch_bucket_indices = {}
    group_index = 1
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

function __bp_touch(x_ratio, y_ratio)
    if not renderer or #groups == 0 then return end
    local region = classify_touch_region(x_ratio, y_ratio)
    local buckets = TOUCH_BUCKETS[region] or TOUCH_BUCKETS.head
    for bucket_index, tags in ipairs(buckets) do
        local candidates = candidates_for_tags(tags)
        if try_start_from_candidates(candidates, region .. ":" .. tostring(bucket_index)) then
            return
        end
    end

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

function __bp_action(tag)
    if not renderer then return false end
    tag = string.lower(tostring(tag or "")):gsub("^%[", ""):gsub("%]$", "")
    if tag == "" then return false end
    if ends_with(tag, ".exp") and renderer.set_expression then
        local ok = pcall(function() renderer:set_expression(tag) end)
        if ok then return true end
        local name = tag:sub(1, -5)
        return pcall(function() renderer:set_expression(name) end)
    end
    local candidates = candidates_for_tags({ tag })
    if try_start_from_candidates(candidates, "llm:" .. tag) then return true end
    return false
end

function __bp_look_at(x_ratio, y_ratio)
    if not renderer or renderer.drag == nil then return end
    x_ratio = clamp01(x_ratio)
    y_ratio = clamp01(y_ratio)
    renderer:drag(x_ratio * width, y_ratio * height)
end

function __bp_transform(x, y, s)
    offset_x = tonumber(x) or 0
    offset_y = tonumber(y) or 0
    scale = tonumber(s) or 1
    if not renderer then return end
    if renderer.set_offset then renderer:set_offset(offset_x, offset_y) end
    if renderer.set_scale then renderer:set_scale(scale) end
end

function __bp_clear()
    gl.glViewport(0, 0, width, height)
    gl.glClearColor(0.0, 0.0, 0.0, 0.0)
    gl.glClear(0x4000)
end

function __bp_draw(time_msec)
    if not renderer then return end
    gl.glViewport(0, 0, width, height)
    renderer:draw({ clear = false, time_msec = time_msec })
    if active_motion_kind == "action" and is_motion_finished() then
        start_default_motion()
    end
end
)lua";
    return runLua(renderer, bootstrap);
}

static void renderLoop(Renderer* renderer) {
    if (!initEgl(renderer) || !initLua(renderer)) return;

    auto previousFrameStart = std::chrono::steady_clock::now();
    auto fpsSampleStart = previousFrameStart;
    int framesSinceFpsSample = 0;
    int measuredFps = 0;
    while (renderer->running.load()) {
        const auto frameStart = std::chrono::steady_clock::now();
        const float deltaSeconds = std::clamp(
            std::chrono::duration<float>(frameStart - previousFrameStart).count(),
            0.0f,
            0.1f
        );
        previousFrameStart = frameStart;
        std::string model;
        std::string action;
        std::unordered_map<std::string, std::string> resources;
        std::vector<uint32_t> backgroundPixels;
        int backgroundWidth = 0;
        int backgroundHeight = 0;
        bool shouldUpdateBackground = false;
        bool shouldTouch = renderer->pendingTouch.exchange(false);
        float touchXRatio = renderer->touchXRatio.load();
        float touchYRatio = renderer->touchYRatio.load();
        bool shouldLookAt = renderer->pendingLookAt.exchange(false);
        float lookAtXRatio = renderer->lookAtXRatio.load();
        float lookAtYRatio = renderer->lookAtYRatio.load();
        bool shouldResize = renderer->pendingResize.exchange(false);
        bool shouldTransform = renderer->pendingTransform.exchange(false);
        bool shouldUpdateRenderOptions = renderer->pendingRenderOptions.exchange(false);
        int width = renderer->width.load();
        int height = renderer->height.load();
        {
            std::lock_guard<std::mutex> lock(renderer->mutex);
            model.swap(renderer->pendingModel);
            action.swap(renderer->pendingAction);
            resources.swap(renderer->pendingResources);
            if (renderer->pendingBackground) {
                shouldUpdateBackground = true;
                backgroundWidth = renderer->pendingBackgroundWidth;
                backgroundHeight = renderer->pendingBackgroundHeight;
                backgroundPixels.swap(renderer->pendingBackgroundPixels);
                renderer->pendingBackground = false;
                renderer->pendingBackgroundWidth = 0;
                renderer->pendingBackgroundHeight = 0;
            }
        }

        if (shouldUpdateRenderOptions) {
            eglSwapInterval(renderer->display, renderer->vsyncEnabled.load() ? 1 : 0);
        }
        if (shouldUpdateBackground) {
            uploadBackground(renderer, backgroundPixels, backgroundWidth, backgroundHeight);
        }
        if (shouldResize) {
            getGlobal(renderer, "__bp_resize");
            renderer->luaApi.pushNumber(renderer->lua, width);
            renderer->luaApi.pushNumber(renderer->lua, height);
            callLua(renderer, "__bp_resize", 2);
        }
        if (!model.empty()) {
            renderer->resources = std::move(resources);
            renderer->lookAtActive = false;
            renderer->smoothedLookAtXRatio = 0.5f;
            renderer->smoothedLookAtYRatio = 0.5f;
            getGlobal(renderer, "__bp_load");
            renderer->luaApi.pushString(renderer->lua, model.c_str());
            renderer->luaApi.pushNumber(renderer->lua, width);
            renderer->luaApi.pushNumber(renderer->lua, height);
            callLua(renderer, "__bp_load", 3);
        }
        if (shouldTouch) {
            getGlobal(renderer, "__bp_touch");
            renderer->luaApi.pushNumber(renderer->lua, touchXRatio);
            renderer->luaApi.pushNumber(renderer->lua, touchYRatio);
            callLua(renderer, "__bp_touch", 2);
        }
        if (!action.empty()) {
            getGlobal(renderer, "__bp_action");
            renderer->luaApi.pushString(renderer->lua, action.c_str());
            callLua(renderer, "__bp_action", 1);
        }
        if (shouldLookAt) {
            renderer->lookAtActive = true;
        }
        if (renderer->lookAtActive) {
            const float easing = std::clamp(deltaSeconds * GAZE_FOLLOW_EASING_PER_SECOND, 0.0f, 1.0f);
            renderer->smoothedLookAtXRatio += (lookAtXRatio - renderer->smoothedLookAtXRatio) * easing;
            renderer->smoothedLookAtYRatio += (lookAtYRatio - renderer->smoothedLookAtYRatio) * easing;

            if (std::abs(lookAtXRatio - renderer->smoothedLookAtXRatio) < 0.001f &&
                std::abs(lookAtYRatio - renderer->smoothedLookAtYRatio) < 0.001f) {
                renderer->smoothedLookAtXRatio = lookAtXRatio;
                renderer->smoothedLookAtYRatio = lookAtYRatio;
                renderer->lookAtActive = false;
            }

            getGlobal(renderer, "__bp_look_at");
            renderer->luaApi.pushNumber(renderer->lua, renderer->smoothedLookAtXRatio);
            renderer->luaApi.pushNumber(renderer->lua, renderer->smoothedLookAtYRatio);
            callLua(renderer, "__bp_look_at", 2);
        }
        if (shouldTransform) {
            getGlobal(renderer, "__bp_transform");
            renderer->luaApi.pushNumber(renderer->lua, renderer->transformOffsetX.load());
            renderer->luaApi.pushNumber(renderer->lua, renderer->transformOffsetY.load());
            renderer->luaApi.pushNumber(renderer->lua, renderer->transformScale.load());
            callLua(renderer, "__bp_transform", 3);
        }

        getGlobal(renderer, "__bp_clear");
        callLua(renderer, "__bp_clear", 0);
        drawBackground(renderer);
        getGlobal(renderer, "__bp_draw");
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        const auto timeMs = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
        renderer->luaApi.pushNumber(renderer->lua, static_cast<double>(timeMs));
        callLua(renderer, "__bp_draw", 1);
        drawFps(renderer, measuredFps);
        if (eglSwapBuffers(renderer->display, renderer->surface) == EGL_TRUE) {
            ++framesSinceFpsSample;
            const auto fpsSampleNow = std::chrono::steady_clock::now();
            const float elapsedSeconds = std::chrono::duration<float>(fpsSampleNow - fpsSampleStart).count();
            if (elapsedSeconds >= 0.5f) {
                measuredFps = std::clamp(
                    static_cast<int>(std::lround(framesSinceFpsSample / elapsedSeconds)),
                    0,
                    999
                );
                framesSinceFpsSample = 0;
                fpsSampleStart = fpsSampleNow;
            }
        }

        const int fpsLimit = renderer->fpsLimit.load();
        if (fpsLimit > 0) {
            const auto frameDuration = std::chrono::nanoseconds(1000000000LL / fpsLimit);
            std::this_thread::sleep_until(frameStart + frameDuration);
        }
    }

    destroyBackgroundResources(renderer);
    destroyFpsResources(renderer);
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
    jboolean vsyncEnabled,
    jfloat renderScale
) {
    auto* renderer = new Renderer();
    renderer->fpsLimit.store(fpsLimit > 0 ? fpsLimit : 60);
    renderer->vsyncEnabled.store(vsyncEnabled == JNI_TRUE);
    renderer->renderScale.store(std::clamp(static_cast<float>(renderScale), 0.5f, 2.0f));
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
Java_com_bandori_pet_live2d_NativeLive2D_setRenderScale(JNIEnv*, jobject, jlong handle, jfloat scale) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->renderScale.store(std::clamp(static_cast<float>(scale), 0.5f, 2.0f));
    configureRenderSize(renderer, renderer->surfaceWidth.load(), renderer->surfaceHeight.load());
    renderer->pendingResize.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_setFpsDisplayEnabled(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->fpsDisplayEnabled.store(enabled == JNI_TRUE);
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
Java_com_bandori_pet_live2d_NativeLive2D_setBackgroundPixels(
    JNIEnv* env,
    jobject,
    jlong handle,
    jintArray pixels,
    jint width,
    jint height
) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;

    std::vector<uint32_t> pixelData;
    int backgroundWidth = width;
    int backgroundHeight = height;
    if (pixels != nullptr && width > 0 && height > 0 && width <= 4096 && height <= 4096) {
        const jsize pixelCount = env->GetArrayLength(pixels);
        const int requiredCount = width * height;
        if (pixelCount >= requiredCount) {
            pixelData.resize(static_cast<size_t>(requiredCount));
            env->GetIntArrayRegion(pixels, 0, requiredCount, reinterpret_cast<jint*>(pixelData.data()));
        }
    }
    if (pixelData.empty()) {
        backgroundWidth = 0;
        backgroundHeight = 0;
    }

    std::lock_guard<std::mutex> lock(renderer->mutex);
    renderer->pendingBackgroundPixels = std::move(pixelData);
    renderer->pendingBackgroundWidth = backgroundWidth;
    renderer->pendingBackgroundHeight = backgroundHeight;
    renderer->pendingBackground = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_touch(JNIEnv*, jobject, jlong handle, jfloat xRatio, jfloat yRatio) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->touchXRatio.store(std::clamp(static_cast<float>(xRatio), 0.0f, 1.0f));
    renderer->touchYRatio.store(std::clamp(static_cast<float>(yRatio), 0.0f, 1.0f));
    renderer->pendingTouch.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_lookAt(JNIEnv*, jobject, jlong handle, jfloat xRatio, jfloat yRatio) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr) return;
    renderer->lookAtXRatio.store(std::clamp(static_cast<float>(xRatio), 0.0f, 1.0f));
    renderer->lookAtYRatio.store(std::clamp(static_cast<float>(yRatio), 0.0f, 1.0f));
    renderer->pendingLookAt.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bandori_pet_live2d_NativeLive2D_playAction(JNIEnv* env, jobject, jlong handle, jstring tag) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    if (renderer == nullptr || tag == nullptr) return;
    const char* tagChars = env->GetStringUTFChars(tag, nullptr);
    {
        std::lock_guard<std::mutex> lock(renderer->mutex);
        renderer->pendingAction = tagChars != nullptr ? tagChars : "";
    }
    if (tagChars != nullptr) env->ReleaseStringUTFChars(tag, tagChars);
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
