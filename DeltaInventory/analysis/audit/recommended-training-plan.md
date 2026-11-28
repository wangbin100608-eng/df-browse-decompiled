# 建议训练方案（Recommended Training Plan）

> 基于审计与缺口报告的建议方案，**尚未执行**。方案遵循：先审计、后方案、不重复生成已有数据、不修改现有数据。

## 0. 原则

1. **不重造已存在的数据**：复用 `item-features.json`（1663 条）、`item-database.json`、`asset-inventory.csv` 的 URL、本地已持有的 15 张图标、`research/train/real_labels.jsonl` 的 19 条真实标注。
2. **真实域优先**：已实测证明"CDN 纯图标合成深色格子"路线上限低（合成域 100% → 真实帧 37.5%），不再走合成路线。
3. **先验证再投入**：每个阶段设 go/no-go 门槛，不达标就停。
4. **对普通非武器类**：`props`(795) + `protect`(98) 为主，暂不覆盖 gun/acc/ammo。

---

## 阶段 1 — 数据补全（P0，预计 1–2 小时）

### 1.1 下载参考图片（只补缺的）

- 从 `asset-inventory.csv` 的 `icon_url` 下载 `props`+`protect` 共 893 张 WebP 到 `dataset/icons/`；
- 已持有的 15 张（见 `image-inventory.csv` 的 `local_image_present=yes`）直接复制，不重复下载；
- 记录 sha256 与尺寸，追加到 `image-inventory.csv` 的新列或独立 `dataset/icons-manifest.csv`；
- **Go/No-Go**：下载成功率 ≥95%。

### 1.2 扩大真实域标注（最关键）

- 对 `research/train/annotate/` 的 13 张高清帧逐张标注，**整帧全标注**（每张帧上所有可见物资 bbox），输出到 `dataset/real_annotations/`（yolo 格式：每帧一个 txt，每行 `class x_center y_center w h`，归一化）。
- 优先补 8 个"只有 1 个样本"的类别（UZI/M1911/北极星/突击手背心/MHS/武士背心/GA背包/CAT），每类 ≥8 个真实裁剪。
- 保留 1–2 张帧做独立验证集（G6）。
- **Go/No-Go**：11 类每类 ≥8 样本，且独立验证集 ≥20 个 bbox。

---

## 阶段 2 — 工具链补齐（P1，预计 1 小时）

- `tools/crop_annotate.py`：bbox → 64×64 分类器裁剪 + 640×640 检测器拼图；生成裁剪预览拼图做质检。
- `tools/augment.py`：真实域内增广（亮度 0.8–1.25、对比度 0.8–1.2、82–100% 中心裁剪、JPEG 压缩模拟），**不使用 CDN 纯图标合成**。
- `tools/train_detector.py`（仓库缺失的脚本）：训练 YOLO 风格小检测器，导出 ONNX `[1,3,640,640]→[1,100,6]`，opset 13，与 `VisionEngine.detect` 契约一致。
- 复用 `research/train/scripts/export_onnx.py` 的导出经验（opset 13 可用）。

---

## 阶段 3 — 分类器验证（快速，预计 30 分钟）

- 用扩大后的真实标注重训分类器（复用 `research/train/scripts/train_real_merged.py`）；
- 评估：同源 3 折 + 独立帧留出；
- **Go/No-Go**：独立留出准确率 ≥80%（当前 19 条样本的同源 3 折为 68.4%）。不达标则回到阶段 1.2 补标注。

---

## 阶段 4 — 检测器训练（P0→GPU 视情况，预计 2–6 小时）

- 数据：阶段 1.2 的整帧 yolo 标注 + 阶段 2 的增广；
- 模型：小型 YOLO（如 YOLOv8n 或等效自实现），输入 640×640，输出按 conf 取 top100 整理成 `[1,100,6]`；
- 类别：11 类起步，成功后扩展到 `props`+`protect` 的 893 类（需要对应标注）；
- 训练：迁移学习（若有预训练权重）或从零；验证 mAP@0.5；
- **Go/No-Go**：独立验证集 mAP@0.5 ≥0.6。

---

## 阶段 5 — APK 集成与验证（预计 1 小时）

1. 替换 `app/src/main/assets/models/item_detector.onnx`（Kotlin 推理端零改动，输入输出契约一致）；
2. 修改 `ScanProcessor.processFrame`：`visionEngine.detect(bitmap, itemTypes)` 替代 `iconScanner.scan(...)`，用 bbox 直接产出 `RawItem`，**绕过 OCR 切格**；
3. `./gradlew --offline assembleRelease`（已验证可成功），用 `delta-release.jks` 签名；
4. 安装到 Redroid，跑两个视频，导出识别结果与数据库；
5. 与用户提供的正确清单比对。
- **Go/No-Go**：两个视频识别出的物资集合与清单一致（最终成功标准）。

---

## 阶段 6 — 同外观物品处理（P2，可选）

- 对 87 组同 phash 物品，用上下文/局部纹理训练次级分类器，或在输出中标注"同外观组"；
- 不阻塞前 5 阶段的主流程。

---

## 时间与依赖

| 阶段 | 依赖 | 本机是否具备 |
|---|---|---|
| 1.1 下载 | 网络、CDN 可用 | ✅ 已验证 4/4 成功 |
| 1.2 标注 | 高清帧（已有 13 张）、多模态读图 | ✅ |
| 2 工具链 | Python + PIL/cv2/torch | ✅ 已装 |
| 3 分类器 | torch | ✅ |
| 4 检测器 | torch（CPU 可跑，GPU 更快） | ⚠️ CPU 训练 hours 级 |
| 5 APK | JDK17 + SDK34 + NDK26 + Gradle（已验证 assembleRelease 成功） | ✅ |

## 不做什么

- 不下载/训练 gun/acc/ammo（除非成功后再扩）；
- 不重算 `item-features.json`（已有且 id 对应正确）；
- 不修改 `analysis/` 下原始 JSON/CSV（本报告与 `image-inventory.csv` 为新增文件）；
- 不再走 CDN 纯图标合成训练路线（已实测证伪）。
