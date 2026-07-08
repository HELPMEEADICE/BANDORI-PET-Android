# Bandori Pet Android

这是按当前目录生成的 Kotlin Android 工程骨架，UI 使用 Material Design 3 / Material Components 风格，包含 Live2D 显示区、LLM 输入框和底部“主页/模型/设置”导航栏。

## 资源来源

- `icon.png`：构建时复制为 Android launcher icon。
- `outfit.json`：构建时复制到 APK assets，并在启动后解析角色与服装。
- `models/`：构建时复制到 APK assets，首次启动时释放到 app 私有目录供 Lua `io.open` 使用。
- `third_party/Live2D-v2-Lua`：构建时复制到 APK assets，首次启动时释放到 app 私有目录。

## LuaJIT 依赖

JNI 使用 `dlopen("libluajit.so")` 动态加载 LuaJIT。请在 Linux/NDK 环境准备 Android ABI 对应库：

```text
app/src/main/jniLibs/arm64-v8a/libluajit.so
app/src/main/jniLibs/armeabi-v7a/libluajit.so
app/src/main/jniLibs/x86_64/libluajit.so
```

## 构建

工程依赖 `com.google.android.material:material:1.12.0`，服务器需可访问 Google Maven / Maven Central。

```bash
./gradlew :app:assembleDebug
```

如果没有 Gradle Wrapper，可在服务器安装 Gradle 8.x 后运行：

```bash
gradle :app:assembleDebug
```

## Live2D 适配点

- Kotlin：`Live2DGLView` 使用 `GLSurfaceView` 绑定 GLES 生命周期。
- JNI/C++：`app/src/main/cpp/live2d_jni.cpp` 负责 GLES 初始化、viewport、透明清屏、LuaJIT VM 和 Lua 函数调用。
- Lua：`third_party/Live2D-v2-Lua/live2d/gl_loader.lua` 已补 Android `libGLESv2.so` 加载分支。
- Cubism 2：加载 `model.json`，内部引用 `.moc`。
- Cubism 3/4：加载 `.model3.json`，内部引用 `.moc3`。

LLM 目前是 Mock，入口在 `MainActivity.sendMessage()`，发送后会触发动作与口型参数接口。
