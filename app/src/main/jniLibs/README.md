Place native runtime libraries here before building.

Required per ABI, for example `app/src/main/jniLibs/arm64-v8a/`:

- `libluajit.so`

Android devices use the system EGL/OpenGL ES 2.0 stack. A bundled Mesa/Zink desktop OpenGL stack is no longer required for normal device builds.

For arm64 device builds, build LuaJIT with the Android NDK target toolchain and GC64 enabled. A known-good build command from a LuaJIT checkout is:

```sh
NDK=/home/debian/Android/sdk/ndk/27.0.12077973
TOOL=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
make clean
make -j$(nproc) \
  HOST_CC="gcc" \
  CROSS="$TOOL/aarch64-linux-android23-" \
  TARGET_SYS=Linux \
  XCFLAGS="-DLUAJIT_ENABLE_GC64"
cp src/libluajit.so app/src/main/jniLibs/arm64-v8a/libluajit.so
```
