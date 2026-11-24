# DeltaInventory

Android 原生应用：本地视觉物品盘点、物品价值统计与二维背包布局优化。

## 定位与范围

- **范围**：仅普通非武器物品（props/protect 等，893 件）；**不实现**枪械、配件、弹药的识别/关联/优化。
- **隐私**：视觉识别、追踪、统计全部本地完成，不上传云端 AI；仅价格同步可选联网。
- **数据源**：从 `df-browse-decompiled` 仓库的 WASM 提取的 1663 件真实物品表。

## 技术架构

| 层 | 技术 | 职责 |
|---|---|---|
| UI | Kotlin + Jetpack Compose | 主页/盘点/待确认/统计/背包网格/历史/数据库/设置 |
| 系统集成 | Kotlin | WindowManager 悬浮球、MediaProjection 屏幕采集、Foreground Service |
| 视觉 | ONNX Runtime Android + OpenCV(JNI) | 本地推理、变化检测、感知哈希 |
| 核心 | Rust | 跨帧追踪、去重、价值统计、MaxRects/Bottom-Left/精确搜索装箱 |
| 存储 | SQLite + Room | 9 张表，离线优先，备份恢复 |
| 离线工具 | Python | 仅作数据导入/模型训练，不参与 Android 运行时 |

```
屏幕帧 → OpenCV 变化检测 → ONNX 推理 → 观测(Observation)
       → Rust/Kotlin 实例追踪账本 → 去重/待确认 → 价值统计 + 装箱优化 → Compose UI
```

## 目录结构

```
app/
├── rust-core/          # Rust 核心库 + 17 项自动化测试
├── app/src/main/
│   ├── java/.../
│   │   ├── data/       # Room 实体、DAO、数据库提供者
│   │   ├── domain/     # CoreBridge(JNI)、ScanViewModel
│   │   ├── vision/     # VisionEngine(ONNX+OpenCV)
│   │   ├── capture/    # CaptureService(MediaProjection+悬浮球)
│   │   └── ui/         # Compose 全部页面
│   ├── cpp/            # OpenCV JNI (变化检测/phash)
│   ├── jniLibs/arm64-v8a/  # libdi_core.so + libopencv_java4.so
│   └── assets/models/  # ONNX 模型、版本、说明
├── tools/import_assets.py  # 离线数据导入
├── docs/               # 架构/数据库/测试/性能报告
└── analysis/           # 资源分析 5 份文档
```

## 构建

需要 JDK17、Android SDK 34、NDK 26.3、Rust + aarch64-linux-android target、CMake 3.22。

```bash
# 1. Rust 交叉编译
cd rust-core && cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libdi_core.so ../app/src/main/jniLibs/arm64-v8a/

# 2. Gradle 构建 APK
cd .. && gradle assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
cd rust-core && cargo test     # 17 项核心测试全通过
gradle test                    # Android 单元测试
```

## 关键设计

- **实例账本**：所有统计以 `ItemInstance` 账本为唯一真相，绝不逐帧累加识别数量。
- **去重证据**：类型+区域+容器+位置+感知哈希+时间连续性，六项同时满足才合并；禁止仅凭名称/图片相似度。
- **人工确认**：低置信度（<0.75）进入待确认队列，未确认不计入确定价值。
- **价值**：价格来自离线 DFPB v6 快照，带来源/更新时间/过期判断；缺失标记 unknown，不猜测。
- **装箱**：MaxRects/Bottom-Left 多方案比较，小规模精确搜索作测试基准，输出坐标/价值/未放入/耗时。

## 视觉模型状态

当前 `item_detector.onnx` 为**未训练的骨架模型**，仅验证推理链路，mAP=0，不宣称准确率。见 `docs/accuracy-report.md`。

## 免责声明

游戏数据、图片、素材版权归腾讯公司及《三角洲行动》官方所有，仅用于个人本地技术研究。
