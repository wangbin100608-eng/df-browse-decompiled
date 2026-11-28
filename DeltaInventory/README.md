# DeltaInventory — Android 智能物品盘点与背包优化系统

基于 `df-browse-decompiled` 仓库逆向提取的真实游戏数据构建的 **Android 原生应用**：本地视觉盘点全部物资、价值统计、二维背包布局优化。所有识别与统计在本地完成，不使用云端 AI。

---

## 一、这是什么

- **范围**：全部 **1663 件**物资（普通物品 795 / 枪械配件 582 / 弹药 119 / 防具 98 / 枪械 69），名称和尺寸来自原站 WASM `wasm_get_all_items()` 真实提取，价格来自 DFPB v6（2026-11-17）快照。
- **识别方式**：1663 件真实物品图标的特征库（dHash + 亮度归一化颜色直方图）本地匹配，**不依赖未训练模型**；ONNX Runtime 链路保留但非识别依赖。
- **格数识别**：ML Kit 本地 OCR 从游戏画面读背包/保险箱网格尺寸；`RegionDetector` 用边缘密度动态定位网格区域，替代硬编码。
- **输出**：背包 + 保险箱 + 地面物资的完整摆放策略（MaxRects 二维装箱）。

## 二、三步运行流程

1. **点击「开始识别全部物资」**
   授权 MediaProjection 屏幕采集 → 自适应分辨率采集（长边 640 工作图，后台线程）→ OCR 读格数、边缘检测定位区域 → 图标特征匹配扫描背包/地面/保险箱 → 低置信度（0.35~0.55）进待确认队列。
2. **点击「结束并比对数据库」**
   停止采集 → 跨格合并（按物品已知宽高把相邻同格合并为单一实例）→ 从 Room 数据库查最新价格（含来源/更新时间/过期判断）→ OCR 读数量角标 → 确认物资名称/格数/价格/数量。
3. **给出完整摆放策略**
   背包 + 保险箱分别 MaxRects 装箱，输出每件坐标、总价值、未放入物品、空余格数、网格可视化；结果写入 Room 持久化。

## 三、技术架构

```
屏幕帧(自适应工作分辨率)
  → CaptureService(MediaProjection + Foreground Service + 悬浮球, 后台HandlerThread)
  → Binder 帧流(有界缓冲/背压)
  → ScanProcessor
      ├─ RegionDetector  动态边缘密度定位网格区域
      ├─ GridOcr         ML Kit 本地 OCR：格数 & 数量角标
      ├─ IconScanEngine  网格切分 + IconMatcher.matchRegion (HashMap索引/分桶加速)
      └─ ClusterMerger   跨格合并 / 数量判定 / 置信度分流(纯逻辑)
  → ScanViewModel(StateFlow) → Compose UI(8页)
  → Room(9表: items/prices/instances/sessions/frames/events/optimizations/containers/images)
  → Rust 核心(libdi_core.so: 追踪/价值/MaxRects) / OpenCV JNI(变化检测/phash)
```

| 层 | 技术 |
|---|---|
| UI | Kotlin + Jetpack Compose（主页/三步盘点/待确认/统计/背包网格/历史/数据库/设置） |
| 系统集成 | WindowManager 悬浮球、MediaProjection、Foreground Service |
| 视觉 | ML Kit OCR + 图标特征匹配 + OpenCV(JNI) |
| 核心 | Rust（跨帧追踪、去重、价值统计、装箱）+ Kotlin 镜像实现 |
| 存储 | SQLite + Room 9 表，离线优先，导入/备份/恢复 |
| 离线工具 | Python（图标特征/物品数据提取；模型训练脚本），不参与 Android 运行时 |

## 四、性能优化

| 优化 | 实现 |
|---|---|
| 采集分辨率 | VirtualDisplay/ImageReader 用长边 640 工作图（按屏幕比例），像素量降约 10× |
| 帧处理线程 | 后台 HandlerThread，不阻塞 UI |
| 变化检测 | JNI 直接降采样 64×64 灰度差分，不构造全尺寸 Mat；静止帧跳过 |
| 匹配加速 | 特征库 HashMap id 索引 + pHash 高 16 位分桶，只比较邻近桶；`matchRegion` 避免每格 createBitmap；复用 px72/px1024 缓冲 |
| 内存 | Bitmap 对象池、像素/浮点缓冲复用、Image 及时 close、背压丢弃 |
| 数据库 | sessionId/itemTypeId/containerId/frameIndex 索引，Room 事务 |

## 五、测试

| 套件 | 结果 |
|---|---|
| Rust `cargo test` | **17/17 PASS**（追踪/去重/滑动/拆分合并/价格/装箱） |
| Android `CoreLogicTest` | **9/9 PASS** |
| Android `FlowLogicTest` | **9/9 PASS**（多格合并/区域隔离/置信度分流/OCR格数） |
| Android `RegionAndQtyTest` | **3/3 PASS**（区域带检测/数量解析） |
| `DeviceSmokeTest` (androidTest) | 真机冒烟代码；**当前无设备环境未执行** |

Release APK：正式 keystore 签名（v2 有效），SHA256 `e202065b81f0c4c31fb70ea80afdddfc99f6db7d422e44eb7137b44bca24c850`。

## 六、已知限制（如实）

- **真实游戏画面识别未完成**：已在 Redroid 11 上完成动态验证（特征库加载、采集权限流程、1663 件导入、21/21 单测通过），并修复两处运行时缺陷（无符号哈希溢出、特征库与运行时采样算法不一致）。纯图标识别 5/5 正确；但官方模拟器真实渲染格子上 dHash 失效（4/4 错配，属算法域漂移限制），游戏内识别尚不可用，需带标注的真实截图训练局部特征/CNN 模型。详见 `DeltaInventory/docs/accuracy-report.md`。
- **模型状态**：`item_detector.onnx` 是未训练骨架（mAP=0），不用于识别；识别靠图标特征库。有标注数据后可用 `tools/train_detector.py` 训练替换。
- **区域启发式**：`RegionDetector` 基于边缘密度，对复杂 UI 可能定位偏差，可回退默认区域；设置页可调阈值。
- **保险箱区域**：当前按画面右上小网格扫描，若游戏 UI 布局不同需调整区域参数。

## 七、构建

```bash
cd DeltaInventory
bash tools/fetch-opencv.sh        # 下载 OpenCV ARM64 构建依赖（gitignore，不入库）
bash tools/build-rust-android.sh  # 编译 Rust core -> jniLibs
# JDK17 + Android SDK34 + NDK26.3 + CMake3.22
gradle assembleRelease            # app/build/outputs/apk/release/app-release.apk
```

## 八、交付内容

- `DeltaInventory-v1.0.0-release.apk`（正式签名，推荐安装）
- `DeltaInventory-v1.0.0-debug.apk`（调试版）
- 完整 Android Studio 工程：Kotlin 源码、Rust 核心、C++/OpenCV JNI、ONNX 模型、特征库、Room 表、测试
- `analysis/`：assets-manifest.json、item-database.json、item-features.json、asset-inventory.csv、price-source-report.md、resource-analysis.md
- `docs/`：architecture、database、test-report、performance-report、accuracy-report

## 九、免责声明

游戏数据、图片、素材版权归腾讯公司及《三角洲行动》官方所有，本项目仅用于个人本地技术研究。
