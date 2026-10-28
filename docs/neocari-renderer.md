# Neocari Android 渲染层

运行时跟随 `HELPMEEADICE/Neocari` 默认分支的最新提交。Gradle 原生构建和
相关 CI 检查在执行前更新 Neocari 的锁文件记录，其他依赖仍由锁文件固定。
Neocari 遵循 GPL-3.0-only，原始 Mocari 的 MIT 声明保留在上游仓库；
本项目继续使用 GPLv3。

## 数据与帧更新

- `AssetSync` 仅准备模型资源。JNI 不再接受 Lua 运行时目录。
- `resources.rs` 统一解析普通路径和 `archive://` 内存路径，保留中文路径、
  相对引用和反斜杠兼容。模型与 PNG 解码后从资源表释放，动作可继续按需加载。
- `model.rs` 通过 Neocari 的公开解析 API 从内存构造运行时。
  MOC2 使用 `Moc2Runtime` 的动作、表情、眨眼、呼吸、拖拽、物理和 Pose 管线；
  口型在最终网格更新前覆盖。MOC3 参考 LITE，依次执行参数和部件重置、
  动作、表情、姿态过渡、视线 / 眨眼 / 呼吸 / 口型覆盖、物理、Pose、网格更新。
- `interaction.rs` 保留原七个触摸区域、方向动作标签、候选轮换和 idle 回退。
- `framing.rs` 根据中性姿态固定模型边界，适配 MOC2 向下和 MOC3 向上的 Y 轴，
  平移、缩放、触摸逆变换在更改渲染分辨率后保持一致。

## Android 后端

保留现有 EGL / OpenGL ES 2.0、ANativeWindow 和独立渲染线程。
`gles.rs` 直接消费 Neocari 的 `Moc3DrawableMesh` 与 `render::common` 绘制顺序，
处理逐网格透明度、乘算色、滤色色，以及普通、叠加、乘算和滤色混合模式。
独立 RGBA 帧缓冲按遮罩贴图 Alpha 合并覆盖率，支持柔和边缘与反向裁剪；
遮罩最长边限制为 2048 像素并遵守设备纹理上限。

背景、透明 Surface、FPS、帧率限制、VSync、分辨率、暂停 / 恢复、
全屏、悬浮宠物和动态壁纸继续走原 Android 控制接口。
恢复帧会处理已取出的命令；切换模型先释放旧 CPU / GPU 资源。

APK 不再包含 `third_party/Live2D-v2-Lua` 或 `libluajit.so`。
`libbandoripet.so` 包含 Neocari；Zstd JNI 的独立动态库仍需打包。

## 验证

GitHub Actions 执行 Rust 格式检查、宿主端回归测试与 Clippy，
Android 目标 Clippy、ARMv8 原生库 / Debug APK 构建和 Kotlin 单元测试。
宿主回归覆盖资源解析、动作匹配与轮换、触摸区域、坐标和分辨率变换，
并通过 Mesa EGL 的离屏 OpenGL ES 测试检查实际绘制与遮罩像素。
真实 MOC3 / MOC2 模型分别采用当前 Neocari 版本内的 Hiyori 和固定原仓库提交内的
Kasumi 数据（仅测试时下载模型文件，不获取或执行 Lua），比较内存和文件加载、
动作 / 表情 / 口型更新的一致性。模型数据不随源码或 APK 分发。
本次迁移不在本机运行 Cargo 或 Kotlin 编译。

设备端仍需检查不同 GPU 上的模型显示、触摸与口型、悬浮层 / 壁纸切换、
后台恢复、VSync 与长期运行时的内存表现；离屏像素测试不替代真机验收。
