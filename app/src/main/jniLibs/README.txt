Place Android LuaJIT shared libraries here before building a runnable APK:

app/src/main/jniLibs/arm64-v8a/libluajit.so
app/src/main/jniLibs/armeabi-v7a/libluajit.so
app/src/main/jniLibs/x86_64/libluajit.so

The JNI layer uses dlopen("libluajit.so") at runtime, so CMake does not need
LuaJIT headers or link-time libraries.
