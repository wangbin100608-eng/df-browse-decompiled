# 技术架构文档

## 系统视图

```
┌──────────────────────── Android 应用 (Kotlin/Compose) ────────────────────────┐
│                                                                              │
│  UI 层 (Compose)                                                             │
│  主页 / 盘点控制 / 待确认 / 价值统计 / 背包网格 / 历史 / 数据库 / 设置          │
│         ▲ StateFlow                    │ 用户操作                             │
│         │                              ▼                                     │
│  领域层 (ScanViewModel) ─── CoreBridge (JNI + Kotlin fallback)                │
│         │                              │                                     │
│         ▼                              ▼                                     │
│  Room 数据库 (9表)              Rust 核心 (libdi_core.so)                    │
│  items/prices/instances/...     追踪/去重/统计/装箱                           │
│         ▲                              ▲                                     │
│         │                              │ 观测 Observation                     │
│  视觉层 (VisionEngine) ────────────────┘                                     │
│  ONNX Runtime (本地推理) + OpenCV JNI (变化检测/phash)                         │
│         ▲ 帧 (Image → Bitmap)                                                │
│  采集层 (CaptureService)                                                     │
│  MediaProjection + VirtualDisplay + WindowManager 悬浮球 + Foreground Service │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 模块职责

### 1. 采集层（capture/CaptureService.kt）
- 用户显式授权 MediaProjection 后启动 Foreground Service。
- VirtualDisplay + ImageReader 采集屏幕帧；有界缓冲（extraBufferCapacity=2）+ 背压丢弃。
- WindowManager `TYPE_APPLICATION_OVERLAY` 悬浮球，支持拖动与点击进入/暂停。
- 处理：授权撤销（`MediaProjection.Callback.onStop`）、系统停止、应用后台、配置变化、异常结束。

### 2. 视觉层（vision/VisionEngine.kt + cpp/）
- OpenCV JNI：`nativeChangeScore`（灰度绝对差分均值）做帧变化检测，`nativePhash`（dHash 64bit）。
- ONNX Runtime：本地加载 `item_detector.onnx`，输入 `[1,3,640,640]`，输出 `[1,100,6]`。
- **模型当前为未训练骨架**：mAP=0，仅验证推理链路真实可用，不伪造识别结果。
- 低置信度（<0.35）不产生有效检测；未知物品由上层标记。

### 3. 领域层（domain/）
- `CoreBridge.Tracker`：跨帧实例追踪。六重证据匹配（类型/区域/容器/位置/感知哈希/时间），严格去重。
  - 同类型不同位置 → 独立实例
  - 重复帧 → 匹配而非新建（不重复计数）
  - 低置信度 → PendingReview（不计入确定价值）
  - 人工确认/拆分/合并/删除
- `ScanViewModel`：会话状态机（idle/running/paused/stopped）、帧处理、统计、价值、优化调度。

### 4. Rust 核心（rust-core/）
- `model.rs`：ItemInstance / Observation / VisualSignature / ContentPosition。
- `tracker.rs`：InstanceTracker（与 Kotlin 版语义一致，作为高可靠参考实现）。
- `valuation.rs`：价值统计（数量乘算、背包/地面分别统计、过期/缺失标记）。
- `packing.rs`：MaxRects、Bottom-Left、小规模精确搜索基准、冲突校验、多方案取优。
- 17 项自动化测试全部通过（含连续滑动、重复计数、区域独立、拆分合并、过期价格、越界重叠等）。

### 5. 数据层（data/）
9 张 Room 表：items, item_images, item_prices, containers, item_instances, scan_sessions, frame_records, recognition_events, optimization_results。
索引、外键、事务、备份恢复、离线查询。数据全部本地，启动不依赖网络。

## 性能设计

- 图像缓冲池：ImageReader 有界缓冲（3），frameFlow 容量 2，超限关闭旧帧。
- 异步处理：Coroutine Dispatchers.Default 处理推理与计算，Main 仅 UI。
- 变化检测：OpenCV absdiff，未变化帧跳过推理。
- 推理批处理：ONNX session 固定线程数 2，单次一帧。
- SQLite 索引：sessionId/itemTypeId/containerId/frameIndex 等关键字段建索引。
- 低拷贝：Rust 通过 JNI 接收 JSON（小数据），视觉特征用原始数组。
- UI 与计算线程隔离：StateFlow 单向数据流。

## 数据流（一帧）

1. ImageReader → Image → Bitmap（RGBA）
2. OpenCV `changeScore(prev, cur)`：若 < 阈值则丢弃帧
3. ONNX `detect(bitmap)` → 候选 Detection 列表（当前模型无有效输出）
4. 每候选 → Observation（itemTypeId, region, position, phash, confidence, qty）
5. `Tracker.processFrame(frameIndex, observations)` → 匹配/新建/待确认
6. 更新 StateFlow → UI；写 Room（frame_records, recognition_events, item_instances）
7. 用户触发统计 → `value_instances()`；触发优化 → `CoreBridge.optimize()`
