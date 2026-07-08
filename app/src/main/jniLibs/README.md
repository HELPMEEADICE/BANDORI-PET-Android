Place native runtime libraries here before building.

Required per ABI, for example `app/src/main/jniLibs/arm64-v8a/`:

- `libluajit.so`

Android devices use the system EGL/OpenGL ES 2.0 stack. A bundled Mesa/Zink desktop OpenGL stack is no longer required for normal device builds.
