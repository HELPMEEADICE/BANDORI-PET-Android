Place native runtime libraries here before building on Linux.

Required per ABI, for example `app/src/main/jniLibs/arm64-v8a/`:

- `libluajit.so`
- Mesa/Zink OpenGL stack required by your target device or package, typically including `libEGL.so`, `libGL.so`, Zink/Mesa driver libraries, and a Vulkan driver/loader setup compatible with the device.

The app intentionally creates an EGL desktop OpenGL context and does not fall back to OpenGL ES.
