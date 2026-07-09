# Bandori Pet

> 把心爱的邦邦角色放在手机桌面上，随时戳一戳 ✨

<p align="center">
  <a href="https://github.com/HELPMEEADICE/BANDORI-PET-Android"><img alt="GitHub Repo" src="https://img.shields.io/badge/GitHub-BANDORI--PET--REV-ff69b4?logo=github"></a>
  <a href="https://github.com/HELPMEEADICE/BANDORI-PET-Android/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/github/license/HELPMEEADICE/BANDORI-PET-Android?color=blue"></a>
  <a href="https://github.com/HELPMEEADICE/BANDORI-PET-Android/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/HELPMEEADICE/BANDORI-PET-Android?color=yellow"></a>
  <a href="https://github.com/HELPMEEADICE/BANDORI-PET-Android/network/members"><img alt="Forks" src="https://img.shields.io/github/forks/HELPMEEADICE/BANDORI-PET-Android?color=orange"></a>
  <a href="https://kotlinlang.org/"><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white"></a>
  <a href="https://luajit.org/"><img alt="LuaJIT" src="https://img.shields.io/badge/LuaJIT-2.1+-000080?logo=lua&logoColor=white"></a>
  <a href="https://github.com/EasyLive2D/Live2D-v2-Lua"><img alt="Live2D Runtime" src="https://img.shields.io/badge/Live2D-EasyLive2D_v2_Lua-EE82EE?logo=lua&logoColor=white"></a>
  <a href="https://github.com/HELPMEEADICE/BANDORI-PET-Android"><img alt="Last Commit" src="https://img.shields.io/github/last-commit/HELPMEEADICE/BANDORI-PET-Android?color=green"></a>
</p>

Bandori Pet 是一个 Android 桌面宠物 / 动态壁纸应用，使用 Live2D 模型驱动 BanG Dream! 系列角色的动态演出。基于 Compose + OpenGL ES 2.0 + LuaJIT 构建，支持 MOC2 和 MOC3 格式模型。

---

## 功能速览

- 📱 **桌面宠物模式** —— 角色在你的屏幕上自由活动，戳她会触发动作反馈
- 🖼️ **动态壁纸模式** —— 把角色设为系统动态壁纸，解锁屏幕就能看到她们
- 🎭 **多角色多服装** —— 覆盖 10 个乐队、45+ 角色，每人多套服装随意切换
- ⚙️ **帧率可调** —— 15~120 FPS，开启垂直同步，性能与流畅度自己拿捏
- 🎨 **Material 3 设计** —— 粉紫色调主题，浅色 / 深色自动适配

---

## 前置条件

| 工具 | 版本 / 说明 |
|------|------------|
| Android Studio | 2024+ 推荐（自带了 Gradle 和 SDK） |
| Android SDK | compileSdk 35, minSdk 26 |
| Android NDK | 27+（用于编译 C++ JNI 库和 LuaJIT） |
| JDK | 17 |
| Gradle | 由项目 wrapper 管理，无需单独安装 |

> **注意**：本项目默认只构建 `arm64-v8a` 架构。模拟器请使用 arm64 镜像，真机没问题。

---

## 项目结构

```
Bandori-Pet-Android/
├── app/                          # Android 应用模块
│   ├── build.gradle.kts          # 应用构建配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt    # CMake 原生构建
│       │   └── live2d_jni.cpp   # JNI 层：EGL + LuaJIT + 渲染循环
│       ├── java/com/bandori/pet/ # Kotlin 源码
│       └── res/                  # 资源文件
├── band.json                     # 乐队 & 角色定义
├── outfit.json                   # 角色服装映射
├── band_logo/                    # 乐队 Logo 图片
├── icon.png                      # 应用图标
├── docs/                         # 补充文档
├── third_party/                  # [需自行提供] Live2D Lua 运行时
├── models/                       # [需自行提供] 角色模型文件
└── jniLibs/                      # [需自行提供] 预编译 .so 文件
```

`third_party/`、`models/`、`jniLibs/` 均被 `.gitignore` 排除，需要自行准备。

---

## 获取 & 编译原生库

核心思路：先准备好所有 .so 文件放到 `app/src/main/jniLibs/arm64-v8a/`，然后 Android Studio / Gradle 会自动打包到 APK 中。

### 1. 编译 LuaJIT (libluajit.so)

应用通过 `dlopen` 动态加载 LuaJIT，因此需要一个 Android arm64 版本的 `libluajit.so`。

```bash
# 克隆 LuaJIT
git clone https://github.com/LuaJIT/LuaJIT.git
cd LuaJIT

# 设置 NDK 路径（按你的实际路径修改）
NDK=$HOME/Android/sdk/ndk/27.0.12077973
TOOL=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin

# 编译
make clean
make -j$(nproc) \
  HOST_CC="gcc" \
  CROSS="$TOOL/aarch64-linux-android23-" \
  TARGET_SYS=Linux \
  XCFLAGS="-DLUAJIT_ENABLE_GC64"

# 复制产物
cp src/libluajit.so <项目根目录>/app/src/main/jniLibs/arm64-v8a/libluajit.so
```

> **注意**：必须开启 `-DLUAJIT_ENABLE_GC64`，64 位 Android 设备上否则 `luaL_newstate()` 会返回 null。千万不要加 `-DLUAJIT_USE_SYSMALLOC`，会导致初始化失败。

> **Windows 用户**：建议在 WSL2 或远程 Linux 机器上编译，然后把 .so 拉回 Windows。

### 2. 获取 libzstd-jni.so

Kotlin 层的 Zstd 解压依赖 `com.github.luben:zstd-jni`，但其 JAR 不含 Android 原生库，需手动从 AAR 提取。

```bash
# 下载 AAR
curl -sL "https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.6-9/zstd-jni-1.5.6-9.aar" \
  -o zstd-jni-1.5.6-9.aar

# 解压获取 .so
unzip -o zstd-jni-1.5.6-9.aar -d zstd-aar-extract
cp zstd-aar-extract/jni/arm64-v8a/libzstd-jni-1.5.6-9.so \
  <项目根目录>/app/src/main/jniLibs/arm64-v8a/

# 清理
rm -rf zstd-jni-1.5.6-9.aar zstd-aar-extract
```

### 3. libbandoripet.so（项目 JNI 库）

这个由 CMake 在 Gradle 构建时自动编译，**无需手动操作**。只要 NDK 装好就行。

构建产物会自动输出到 `app/build/intermediates/cmake/` 并打包进 APK。

### 最终 jniLibs 目录长这样

```
app/src/main/jniLibs/arm64-v8a/
├── libluajit.so                  # LuaJIT 运行时
├── libzstd-jni-1.5.6-9.so        # Zstd 解压
└── (libbandoripet.so 由 CMake 自动生成)
```

---

## 获取 Live2D 运行时 & 模型

### Live2D Lua 运行时 (`third_party/Live2D-v2-Lua/`)

> **注意**：本项目**未使用 Live2D 官方 Cubism Core SDK**，而是基于社区维护的 [EasyLive2D/Live2D-v2-Lua](https://github.com/EasyLive2D/Live2D-v2-Lua) 开源 Lua 运行时。这是一个纯 Lua 实现的 Live2D 解析与渲染引擎，支持 MOC2 / MOC3 模型格式，无需依赖官方原生库。

该运行时负责模型解析和 OpenGL 绘制，需要放到项目根目录下的 `third_party/Live2D-v2-Lua/`。

Gradle sync 任务会自动将其复制到 APK assets 中（排除 `.git`、`venv`、`*.md`、`*.png`、测试文件等）。

### 模型文件 (`models/`)

角色模型以 **Zstd 压缩 tar 归档**（`.zst` 文件）形式存储。放置方式二选一：

- **打包进 APK**：放到 `models/<characterId>.zst`，Gradle 会自动打包进 assets
- **运行时下载**：不打包，应用启动后自动从 ModelScope CDN 下载到本地（需要网络权限）

模型归档内结构示例：
```
<characterId>.zst
  └── <costumeId>/
      ├── character.png
      ├── <name>.model.json          # MOC2 模型配置
      ├── <name>.model3.json         # MOC3 模型配置
      ├── <name>.moc / <name>.moc3   # 二进制模型
      ├── textures/
      ├── motions/
      ├── expressions/
      └── sounds/
```

---

## 编译 APK

### 命令行

```bash
# 在项目根目录执行
./gradlew assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

如果是 Windows：

```powershell
.\gradlew.bat assembleDebug
```

### Android Studio

1. 用 Android Studio 打开项目根目录
2. 等待 Sync 完成（首次需下载依赖，喝杯茶等一等 🍵）
3. Build → Make Project 或直接点 Run

Gradle sync 时会自动：
- 将 `band.json`、`outfit.json`、`band_logo/`、`models/`、`third_party/Live2D-v2-Lua/` 复制到生成 assets 目录
- 将 `icon.png` 复制为应用图标
- 然后触发 CMake 编译 `libbandoripet.so`

### 安装到设备

```bash
# 通过 adb 安装
adb install app/build/outputs/apk/debug/app-debug.apk

# 或者 Android Studio 里直接 Run（⌘R / Ctrl+R）
```

---

## 开发环境提示

### Windows 特别说明

- NDK 编译需要 Linux 交叉工具链，建议在 **WSL2** 或远程 Linux 上编译 LuaJIT
- 编译好的 `libluajit.so` 拉回 Windows 放对应目录即可
- Gradle CMake 编译用的 NDK 自带交叉编译器，这个在 Windows 上没问题
- `dlopen` / `dlsym` 等 POSIX API 由 Android NDK 提供，编译时不需要额外配置

---

## 许可证

本项目基于 **GNU General Public License v3** 发布。详见 [LICENSE](LICENSE) 文件。

---

## 致谢

- [EasyLive2D/Live2D-v2-Lua](https://github.com/EasyLive2D/Live2D-v2-Lua) —— 社区 Live2D Lua 运行时（非官方 Core）
- [Live2D Cubism](https://www.live2d.com/) —— 赋予角色灵魂的 Live2D 技术
- [LuaJIT](https://luajit.org/) —— 极速 Lua 运行时
- [BanG Dream!](https://bang-dream.com/) —— 闪闪发光心动不已！
- 所有为 Bandori Live2D 模型整理做出贡献的社区成员

---

*キラキラドキドキ！*
