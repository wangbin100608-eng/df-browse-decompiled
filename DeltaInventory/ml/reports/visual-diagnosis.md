# 真实画面视觉识别专项诊断报告

- **日期**：2026-11-28
- **范围**：仅普通非武器类物品（category=`props` 795 + `protect` 98 = 893 件）
- **方法**：逐文件阅读实际源码（非 README 推测）、真实数据实测、独立测试集推理
- **原则**：不修改现有工程/数据库、不伪造结果；每项结论附源码路径+行号或测试证据

---

## 一、现有实现核查（任务一）

### 1.1 最重要的判断：文档已过时，代码已升级

`/root/盘点/源码核查结果.md`（345 行，逐行附行号）核心发现：

- `docs/accuracy-report.md`（65 行）描述**第一阶段**状态：mAP=0、无训练、`tools/train_detector.py` 不存在、dHash 真实格失效。
- 但第二阶段 `DeltaInventory/ml/` 已训练并部署 YOLOv8n：`ml/scripts/train_detector.py`（59 行）、`assets/models/detector_yolov8n.onnx`（12.8 MB，与 `ml/models/` md5 一致）、`ScanProcessor.processFrame` 已切到 YOLO 优先链路。
- **不能再用 accuracy-report 的"0 有效检测"描述当前 APK，也不能用 mAP 0.51 宣称游戏内可用**。

**证据**：源码核查报告 L12–L16；`accuracy-report.md` L52/L56/L58/L64（dHash 5:5、真实格全错配、算法失效、同外观上限）；`ml/scripts/train_detector.py` 全文；md5 `148f328a...`。

### 1.2 用户点名的核查项逐项结论

| 核查项 | 实际结论 | 证据 |
|---|---|---|
| accuracy-report.md | 65 行，第一阶段结论：dHash 纯图标 5:5（L52）、合成格 5:5 错配（L53）、真实渲染格 4/4 全错配（L56）、算法级域漂移（L58）、同外观 1400 唯一/87 组/350 件/最大 16（L64-65） | 源码核查报告第 1 节 |
| tools/train_detector.py | **不存在**（tools/ 仅 6 文件）；训练脚本实际在 `ml/scripts/train_detector.py`（59 行） | 源码核查报告第 2 节，`find` 输出 |
| analysis/ | 12 文件：item-database.json(1663)、item_table.json/v6(list 1663)、assets-manifest.json、2 CSV、4 MD；Python 实读结构见核查报告第 3 节 | 核查报告第 3 节 |
| 图片匹配模块 | `StepHash.java`：9×8 步长 dHash + 32×32 16 桶 hist；`IconMatcher.kt` matchRegion(L112-129)/matchTop(L132-150)：高 16 位桶±1 候选 + bitCount(xor) + L1 直方图，score=(1-dist/20)*0.65+(1-min(hd,1))*0.35 | 核查报告第 4 节 |
| 图像预处理 | `VisionEngine.preprocess`(L173-192) 640 缩放 + [-1,1]；`YoloDetector.preprocess`(L53-68) 640 + [0,1]；`RegionDetector` 行边缘密度；`GridOcr` ML Kit latin | 核查报告第 5 节 |
| ONNX 推理接口 | `VisionEngine.detect` [1,3,640,640][-1,1]→[1,N,6] conf≥0.35，**main 源码无调用点，旧骨架已脱离主流程**；`YoloDetector.detect` [1,3,640,640][0,1]→[1,20,8400] conf≥0.25+NMS0.45 | 核查报告第 6 节 |
| Android 调用链 | `CaptureService.frameBitmapFlow → ScanProcessor.processFrame(L173-252)`；YOLO 优先(L193)，dHash 兜底(L217)；YOLO 模式绕过 OCR(L183/L121 在 !yoloEnabled 内)；格数保持(0,0,0,0)→buildLayout 退化 1×1 | 核查报告第 7 节 |
| ml/ 训练产物 | 98 条/16 类，train53/val8/test37，泄漏0；分类器 test acc 0.8649/macroF1 0.768；检测器 mAP50 0.5126/Recall 0.3419/P 0.7126/mAP50-95 0.3049/108.7ms；实机检出强效注射器 0.59 | 核查报告第 8 节 |

### 1.3 核查发现的源码缺陷（需记录，不在本阶段修改）

1. **北极星 class 10 的 item_id 为空**：`ml/dataset/classes.json` 中北极星无 item_id，`ScanProcessor` L116 `findById("")` 会跳过其检测——即使 YOLO 检出北极星也无法确认。
2. **文档矛盾**：`ml/reports/evaluation-report.md` 第八节称"5 个 item_id 无库条目"，但 `classes.json` 实际仅北极星 1 个为空。
3. **Go/No-Go 未过**：`recommended-training-plan.md` 设 mAP≥0.6 门槛，实测 mAP50=0.5126 未达标。
4. **report 过时**：`analysis/audit/dataset-gap-report.md` 的 G4"train_detector.py 缺失"已不成立。

---

## 二、识别误差分析（任务二）

### 2.1 分辨率差异（证据：`testset_independent/evaluate.py` 统计）

| 维度 | CDN 参考图 | 真实截图裁剪 |
|---|---|---|
| 宽 | 143–336 px | 110–260 px |
| 高 | 150–336 px | 120–200 px |
| 背景 | RGBA，透明像素占 51.3%（CINDER 样本） | RGB 不透明深色 |

结论：参考图与真实裁剪尺寸量级重叠但背景语义完全不同；特征库按整图采样，真实格含边框/文字/角标。

### 2.2 亮度与颜色差异

实测（独立测试集 31 样本 vs 15 张 CDN 图）：
- CDN 参考图亮度均值 **39.9**（范围 10.5–73.9）
- 真实截图裁剪亮度均值 **58.3**（范围 26.9–84.0）
- 真实裁剪四角 RGB 均值 [44.2, 44.7, 58.6]（深色背景），中心 [6.7, 7.5, 14.3]

结论：真实裁剪整体更亮但物品本体更暗（深色渲染），与透明背景参考图存在系统性域差异——印证 accuracy-report L58 的域漂移判断。

### 2.3 缩放/压缩差异

- 训练帧 2560×1600，APK 采集工作图 `360×640`（`CaptureService` 日志），YOLO 输入 640×640 再缩放 → 单物品图标在工作图中仅几十像素，两次缩放引入插值损失。
- 视频为 H.264 录屏，游戏画面经 JPEG 类压缩；参考图为无损 WebP。

### 2.4 相似物品混淆（证据：item-features.json phash 统计）

- 全库 87 组 / 350 件 phash 完全相同，其中**纯普通非武器类 51 组**。
- 最大组 16 件（如"博物馆展厅套间/老浴场贵宾室/旅店用餐间…"等房卡/藏宝图类），15 件组、12 件组等。
- 结论：这些物品**物理上不可区分**（同外观），任何仅靠视觉特征的模型都会混淆——需上下文或接受组内任一返回。

### 2.5 低置信度结果（证据：独立测试集推理）

分类器在独立测试集 31 样本上 acc 0.806、macroF1 0.364；**6 条错误匹配全部为低置信度**：

| 帧 | 时间 | GT | 预测 | conf |
|---|---|---|---|---|
| v2_new_1.0 | 1.0 | 强效注射器 | CINDER | 0.20 |
| v2_new_7.0 | 7.0 | DICH训练头盔 | 复合弓 | 0.21 |
| v2_new_8.0 | 8.0 | DICH训练头盔 | CAT止血带 | 0.13 |
| v1_new_2.0 | 2.0 | CINDER | 武士防弹背心 | 0.20 |
| v1_new_3.0 | 3.0 | 强效注射器 | 突击手防弹背心 | 0.31 |
| v1_new_0.0 | 0.0 | 黑鹰野战胸挂 | 强效注射器 | 0.15 |

全部 conf ≤0.31，说明低置信度阈值过滤（APK 设 0.35/0.55）能拦住这些错误——但拦住后这些物品就漏检。错误集中在训练样本仅 2 个的类（DICH/黑鹰）。

### 2.6 重复样本与错误标签（证据：独立测试集审核）

- 独立测试集标注 38 条，人工审核**驳回 7 条**（框到空白/UI文字/非目标），驳回率 18.4%。
- 训练/独立测试帧重叠 = **空集**（无泄漏）。
- 独立测试集内重复 bbox = 0。
- 被驳回的类别恰好是 CAT止血带（2 条全驳回）——该物资在画面里是小图标且贴着数字角标，自动框选时易框到"背包:"文字。

---

## 三、独立测试集（任务三）

- **来源**：两个训练视频中**未进入 train/val/test 的新时间点**（0.0/1.0/2.0/3.0/4.0/5.0/7.0/8.0 s），14 张 2560×1600 帧，来自 `screenrecorder-20261128-111428.mp4`（v1）与 `...104159.mp4`（v2）。
- **隔离证据**：训练帧集合 {v1_0.5, v1_1.5, v1_2.5, v1_4.5, v1_5.5, v2_1.5, v2_2.5, v2_3.5, v2_4.5, v2_6.5, v2_8.5} 与独立帧交集为空。
- **规模**：38 条标注 → 人工审核 31 通过 / 7 驳回。
- **类别分布**（通过）：CINDER 7、强效注射器 6、武士防弹背心 5、M995 5、DICH头盔 4、MHS头盔 2、黑鹰胸挂 2。
- **来源记录**：每条标注含 `source_video`/`timestamp_s`/`origin`/`human_review`/`reject_reason`。
- **错误匹配案例**：见 2.5 表 + `testset_independent/classification_results.json`。
- **人工审核结果**：`testset_independent/annotations.jsonl` 每条带 `human_review` 字段。

**局限**：独立来源仍为同两个视频（只是不同时间点），非不同对局/不同设备——这是当前可用素材的诚实边界，已在报告中标注。

---

## 四、改进方向评估（任务四，先诊断后方案）

复用优先的评估（不改现有工程、不重复造轮子）：

1. **修北极星 item_id 空值**：`classes.json` 补一条映射（或从库确认是否真无此物品），使 `findById` 不跳过——属数据修复，零代码改动。
2. **低置信度过滤已存在且有效**：APK `matchThreshold=0.55`，独立测试 6 条错误 conf≤0.31 全会被拦——保留机制，但需配套"漏检提示 UI"（不属本阶段）。
3. **复用 `StepHash.java` 的域内特征**：dHash 对纯图标失效（accuracy-report L58），但可作为快速去重/变化检测（`CaptureService` 已在用），不再投入其识别能力。
4. **复用 `ml/scripts/` 训练管线**：补标注后重跑 `build_dataset.py` + `train_detector.py`，无需重写训练框架。
5. **普通非武器类的同外观问题**：51 组 phash 重复无法视觉区分，需上下文（格子位置/组合）或标注组内代表——建议作为独立课题。

具体修改方案见 `improvement-roadmap.md`（完成诊断后才提出）。

---

## 五、证据索引

| 结论 | 证据文件 |
|---|---|
| 源码核查 | `/root/盘点/源码核查结果.md`（345 行） |
| 独立测试集标注 | `DeltaInventory/ml/testset_independent/annotations.jsonl` |
| 独立测试推理结果 | `DeltaInventory/ml/testset_independent/classification_results.json` |
| 分辨率/亮度/背景统计 | 本报告 2.1–2.3（evaluate 脚本实测） |
| 同外观混淆 | item-features.json phash 统计（本报告 2.4） |
| 训练/独立帧隔离 | 本报告第三节（交集为空） |
