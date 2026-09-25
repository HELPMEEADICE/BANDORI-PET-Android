Place native runtime libraries here before building.

Do not copy `libbandoripet.so` here. Gradle builds that library from
`app/src/main/rust` with `cargo-ndk` and packages the generated output.

Required per ABI, for example `app/src/main/jniLibs/arm64-v8a/`:

- `libluajit.so`
- `libzstd-jni-1.5.6-9.so`

Android devices use the system EGL/OpenGL ES 2.0 stack. A bundled Mesa/Zink desktop OpenGL stack is no longer required for normal device builds.

## 16 KB page alignment

All packaged shared libraries must have ELF `PT_LOAD` segments aligned to at least 16 KB. The Rust library receives the required linker flags from `app/src/main/rust/.cargo/config.toml`; build precompiled libraries with the same flags or verify their ELF headers. Gradle packages `.so` files uncompressed so AGP can apply 16 KB ZIP alignment.

For arm64 device builds, build LuaJIT with the Android NDK target toolchain and GC64 enabled. For NDK r27, pass both 16 KB linker flags:

```sh
NDK="$HOME/Android/Sdk/ndk/27.2.12479018"
TOOL="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
make clean
make -j"$(nproc)" \
  HOST_CC="gcc" \
  CROSS= \
  CC="$TOOL/aarch64-linux-android23-clang" \
  TARGET_AR="$TOOL/llvm-ar rcus" \
  TARGET_STRIP="$TOOL/llvm-strip" \
  TARGET_SYS=Linux \
  XCFLAGS="-DLUAJIT_ENABLE_GC64" \
  TARGET_SHLDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
cp src/libluajit.so <project root>/app/src/main/jniLibs/arm64-v8a/libluajit.so
```

Verify the final APK ZIP alignment with:

```sh
$ANDROID_HOME/build-tools/35.0.0/zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```
