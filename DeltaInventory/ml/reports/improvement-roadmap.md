# 改进路线图（Improvement Roadmap）

> 前置：诊断完成（见 `visual-diagnosis.md` / `dataset-quality-report.md` / `classification-error-report.md`）。本文件在诊断**之后**提出方案。严格遵循：不重写现有 Android 工程、不删除数据库、不修改现有功能、仅普通非武器类、复用优先。

---

## 原则

1. **复用已有代码**：训练管线（`ml/scripts/`）、匹配模块（`StepHash.java`/`IconMatcher.kt`）、ONNX 推理（`YoloDetector.kt`）、低置信度队列、数据库关联——已存在，不重复开发。
2. **数据优先**：当前误差主因是数据规模与低频类，不是模型选型；优先补数据再调模型。
3. **小步验证**：每项改动用独立测试集（已建立）验证，不凭训练集准确率判断。

---

## P0 — 数据修复（零代码改动，最高性价比）

| 项 | 动作 | 证据/理由 | 验收 |
|---|---|---|---|
| 北极星映射 | 在 `ml/dataset/classes.json` 为北极星补 item_id（或确认库中确无此物并在 UI 标为"未知外观"） | 核查发现 class 10 item_id 空，`ScanProcessor` L116 会跳过高置信检出的北极星 | 独立测试集中北极星不再被静默丢弃 |
| CAT 止血带重框 | 重新框选 CAT（当前 2/2 被驳回：框到 UI 文字） | dataset-quality 第 2 节 | CAT 有效标注 ≥8，审核通过率 ≥80% |
| 修正过时文档 | 更新 accuracy-report / dataset-gap / evaluation-report 中"训练未执行/train_detector.py 缺失/5 个 item_id 空"等语句 | 核查报告 1.3 节 4 处矛盾 | 文档与源码一致 |
| 低频类补样 | Mini-14/.357/复合弓/黑鹰/DICH 从 2 → ≥8 | classification-error 第 3 节 | 独立测试 macroF1 提升 |

## P1 — 数据扩充（复用现有脚本）

1. 用 `research/train/real_labels.jsonl` 格式新增标注（帧名/物资名/bbox/source/timestamp），重跑 `build_dataset.py`（自动生成划分、泄漏检查、裁剪）。
2. 目标：检测帧 11 → ≥60、每类样本 ≥8（普通非武器类优先：CAT/GA/MHS/武士背心/突击手背心 + 常见消耗品）。
3. 下载普通非武器类 CDN 参考图（893 件 URL 在 `asset-inventory.csv`），复用 `tools/build_features.py` 的下载逻辑；用于数据增强（仅真实域内扰动，不重蹈"纯图标合成"覆辙——已在第二阶段证伪）。
4. 增加"不同对局/不同设备"的独立测试帧（当前独立测试仍来自同两个视频——诚实边界）。

## P2 — 模型侧（复用现有训练脚本）

1. 重跑 `train_detector.py`（YOLOv8n 迁移学习，40 epoch，早停）；验收 mAP50 ≥0.6（当前 0.5126，未过第二代 Go/No-Go）。
2. 低频类：训练时启用 class-weighted loss 或 oversample（在 `ml/scripts/train_detector.py` 加参数，不改 APK）。
3. 同外观物品（51 组普通类 phash 重复）：增设"组标签"输出（返回组内任一物品并在 UI 提示"外观相同，可能为以下之一"），不强行区分——接受物理上限。
4. 分类器 A 作为给定裁剪的复核路径保留，不再投入。

## P3 — Android 侧小改（不动架构）

| 项 | 动作 | 现有代码位置 |
|---|---|---|
| 恢复格数 | 当前 YOLO 模式绕过 OCR，格数(0,0,0,0)→buildLayout 退化 1×1；可让 YOLO 检测框聚类估算网格尺寸（不用 ML Kit，避免翻译桥问题） | `ScanProcessor.processFrame` L183 在 `!yoloEnabled` 内 |
| 独立推理计时 | 在 `YoloDetector.detect` 加一行 `Log.d` 输出单帧推理 ms，替代当前"估算" | `YoloDetector.kt` detect 方法 |
| 内存记录 | 按标准 `dumpsys meminfo` 流程记录 PSS（已测 337MB） | 无代码改动 |
| 低置信度 UI 提示 | 待确认队列已有；补一个"本帧 N 件未确认"提示（可选） | `ScanViewModel` |

## P4 — 评估规范固化

- 每次训练后用独立测试集（非 train test）跑 `evaluate.py`，报告必须含 acc/macroF1/错误清单；
- 禁止只用训练集准确率汇报（本阶段已独立复核 macroF1 从 0.768 掉到 0.364）；
- 新样本 → `build_dataset.py` → 重训 → 独立测试 → 换 assets 模型 → 重编译，增量迭代闭环。

---

## 优先级排序与预期收益

| 优先级 | 项 | 预期收益 | 成本 |
|---|---|---|---|
| P0 | 北极星映射/CAT重框/低频补样 | 消除静默丢弃、macroF1 提升 | 低（数据） |
| P1 | 帧数 11→60、跨对局测试 | mAP50 有望 0.6+ | 中（标注工时） |
| P2 | 重训 + class-weighted | 低频类 Recall 提升 | 中（CPU 训练小时级） |
| P3 | 格数恢复/计时/UI | 功能完整性与可观测性 | 低（小改） |
| P4 | 评估规范 | 防止虚报 | 低 |

**不做**：不重写 Android 工程、不用 CDN 纯图标合成训练（第二阶段已证伪）、不删除任何数据库、不宣称 mAP 0.51 的生产可用性。

**验收锚点**：独立测试集 macroF1 ≥0.7、检测器 mAP50 ≥0.6、低频类（样本≥8）F1 ≥0.6、北极星不再被静默跳过——达到后再谈"普通非武器类视觉识别可用"。
