# zstd-jni Android Native Library

## 问题

`com.github.luben:zstd-jni:1.5.6-9` 在 Maven Central 上的主 artifact 为 JAR，只包含桌面平台 (linux/darwin/win) 的 .so/.dll/.dylib，不含 Android 原生库。Android AAR 存在但 Gradle 默认不解析，导致 APK 中缺少 `libzstd-jni-1.5.6-9.so`。

## 修复步骤

### 1. 下载 AAR 并提取 arm64-v8a 的 .so

```bash
# 下载 AAR
curl -sL "https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.6-9/zstd-jni-1.5.6-9.aar" -o zstd-jni-1.5.6-9.aar

# 解压并提取 .so
unzip -o zstd-jni-1.5.6-9.aar -d zstd-aar-extract
cp zstd-aar-extract/jni/arm64-v8a/libzstd-jni-1.5.6-9.so app/src/main/jniLibs/arm64-v8a/
```

### 2. 排除 JAR 中的桌面平台二进制

在 `app/build.gradle.kts` 的 `packaging` 块中添加：

```kotlin
packaging {
    resources {
        excludes += setOf(
            "darwin/**",
            "win/**",
            "linux/**",
            "aix/**",
            "freebsd/**",
        )
    }
}
```

### 3. 验证

```bash
# 确认 APK 中只包含 arm64-v8a 的 .so
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep zstd
# 应该只有: lib/arm64-v8a/libzstd-jni-1.5.6-9.so
```

## 版本升级

升级 zstd-jni 版本时，需同步更新 AAR 下载 URL 中的版本号，并重新提取对应版本的 .so。
