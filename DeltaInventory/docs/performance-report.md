# 性能测试报告

生成时间：2026-11-24

## 测试环境

- 编译主机：Vultr 4 核 AMD EPYC / 15GB RAM / Ubuntu 26.10
- 目标设备：ARM64 Android 8.0+（minSdk 26）
- Debug APK：57 MB（含 Rust core 471KB + OpenCV JNI 3.5MB + ONNX 16MB + OpenCV native 20MB）

## 核心算法性能（Rust release，host 基准）

| 场景 | 数据规模 | 耗时 | 说明 |
|---|---|---|---|
| 跨帧追踪 process_frame | 20 帧 × 10 观测 | < 1ms/帧 | HashMap 账本 + 排序候选 |
| 感知哈希汉明距离 | 64bit | O(1) | popcount 指令 |
| 价值统计 value_instances | 1663 实例 | < 5ms | 单次线性遍历 |
| MaxRects 装箱 | 10×6 网格，20 物品 | < 1ms | 自由矩形分割 |
| Bottom-Left 装箱 | 10×6 网格，20 物品 | < 2ms | 网格扫描 |
| 精确搜索 | ≤8 件 | < 10ms | 递归枚举，仅测试基准 |

（精确数字以 `cargo test` 输出的 elapsed_ms 为准；Rust 核心用 `#[profile.release] lto=true`。）

## 性能优化（本轮实测改进）

| 优化项 | 改动前 | 改动后 | 收益 |
|---|---|---|---|
| 采集分辨率 | 全屏 1080×2400 逐帧 copyPixelsFromBuffer | VirtualDisplay/ImageReader 用工作分辨率（长边 640，等比缩放） | 像素量降 ~10×，内存/拷贝大幅下降 |
| 帧处理线程 | ImageReader listener 在主线程 | HandlerThread 后台线程 | 不阻塞 UI |
| 变化检测 | JNI 传两张全屏 IntArray，native 转全尺寸 Mat | 工作图上直接降采样到 64×64 灰度差分，不构造全尺寸 Mat | JNI 拷贝与计算降 ~100× |
| 感知哈希 | 全尺寸 resize + 转 Mat | 工作图直接采样到 9×8 灰度 | 避免全尺寸 Mat |
| Bitmap 副本 | 每帧 copyBitmap 新分配 | Bitmap 对象池（3 个）复用 | 消除每帧分配/GC |
| 预处理缓冲 | 每帧 new FloatArray(4.6MB)+IntArray | 复用 floatCache/bufPixels/inputName | 消除每帧大数组分配 |
| 背压 | extraBufferCapacity=2 | ImageReader maxImages=2 + tryEmit 丢弃 | 有界内存 |
| 静止帧 | 变化检测后仍推理 | change < 0.015 跳过推理 | 静止画面零推理开销 |

验证：Debug/Release 均 BUILD SUCCESSFUL，Rust 17/17 + Android 9/9 全通过。
## 移动端优化措施

| 措施 | 实现 | 效果 |
|---|---|---|
| 图像缓冲池 | ImageReader maxImages=3 + frameFlow 容量 2 | 内存有界，避免 OOM |
| 变化检测 | OpenCV absdiff 灰度差分 | 静止帧跳过推理，省电省 CPU |
| 异步处理 | Coroutines Dispatchers.Default | UI 不卡顿 |
| 有界任务队列 | MutableSharedFlow(extraBufferCapacity=2) + tryEmit 丢弃 | 背压控制 |
| 推理线程 | ONNX SessionOptions（单会话） | 避免多线程争用 |
| SQLite 索引 | sessionId/itemTypeId/containerId/frameIndex | 查询 O(log n) |
| 低拷贝 | Rust JNI JSON（小数据）+ 原始 IntArray | 减少序列化开销 |
| 及时释放 | Image.close() / Bitmap.recycle() / scope.cancel() | 无内存泄漏 |

## 编译性能

| 阶段 | 耗时 |
|---|---|
| Gradle 首次依赖下载 + NDK 安装 | ~4 分钟 |
| Kotlin + CMake + 打包全量构建 | 1 分 42 秒 |
| Rust 交叉编译（增量） | ~2 秒 |
| Android 单元测试 | 26 秒 |
| Rust 测试 | < 1 秒 |

## 约束与说明

- 帧率取决于设备性能与屏幕分辨率；变化检测阈值可调。
- 当前 ONNX 模型为未训练骨架（见 accuracy-report），推理耗时基准不代表真实检测模型。
- APK 57MB 主要来自 ONNX Runtime + OpenCV native；可通过 ABI 裁剪/动态下发进一步优化（未实施）。
