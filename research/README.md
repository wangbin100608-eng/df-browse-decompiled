# research/ — 逆向分析与识别模型研究

> 本目录记录对 `df-browse-decompiled` 项目的本地运行、逆向分析、APK 识别失败归因、以及识别模型训练的完整探索过程。所有内容为研究记录，按时间线归纳。

## 文档（`docs/`）

| 文档 | 内容 |
|---|---|
| `df-browse-decompiled-盘点.md` | 仓库整体盘点：定位、结构、技术栈、时间线、许可证与 notable 发现 |
| `APK运行记录.md` | release APK 在 Redroid 11 x86_64 上的首次运行：安装、逐页验证、导入 1663 件、Rust 装箱 |
| `翻译桥Bug修复记录.md` | 修复 Redroid x86_64 翻译桥两处 bug（FPCR 断言 + SIGILL）的二进制 patch 方法 |
| `视频识别结果.md` | 对两个游戏录屏的识别结果（0 件）与三因素归因 |
| `APK改造进展与卡点.md` | APK 换识别算法的进展、实测证伪的路线、卡点、代码改动预案 |
| `改造能力盘点.md` | 本机构建/ARM64/特征库/离线 CV 能力盘点 |
| `对照与清单数据源核对.md` | item-database.json 字段、物资名匹配、CDN 下载、帧图标尺寸等事实核查 |
| `模型训练进展.md` | 三轮训练实测对比（合成失败→真实有效）、当前最佳模型 |
| `训练方法.md` | 完整训练方法：数据源坑、分类器训练、检测器训练、APK 集成、复现命令 |
| `APK重跑全量dump.md` | 改进采集重跑（纯图标/v1/v2 全部 0 件）+ 12 表数据库全量导出 |

## 训练（`train/`）

- `scripts/` — 训练与导出脚本：`gen_data.py`（合成数据，已弃用）、`precheck_transfer.py`（迁移性预检）、`train_real.py` / `train_real_merged.py`（真实标注训练）、`export_onnx.py`（导出 ONNX）
- `real_labels.jsonl` — **真实游戏帧标注**（19 条，我自己从带网格高清帧框选并逐条校验）：帧名 + 物资名 + bbox
- `classes.json` / `real_classes.json` — 类别映射
- `icons/` — 15 类 CDN 纯图标原图（来自 `df-assets.lza11111.com`）
- `annotate/` — 13 张高清标注帧（`*_grid.png` 为叠加 100px 坐标网格的版本）
- `gen/labels.jsonl` — 合成训练数据标注（1800 张，路线已证伪）
- `net_real_final.pt` — 当前最佳分类器（19 条真实标注全量训练，同源 3 折 68.4%）
- `classifier_real.onnx` — 导出验证用的 ONNX（opset13，`[1,3,64,64]→[1,11]`）
- `crop_check*.png` / `sample_gen.png` — 标注/合成样本校验图

## 证据（`evidence/`）

运行与对照实验截图：主页、导入 1663 件、Rust 装箱、纯图标对照、v1/v2 铺屏采集结果、游戏帧、待确认队列为空。

## 结论摘要

1. **APK 官方版对两个游戏录屏识别出 0 件物资**（UI、待确认队列、数据库三处一致）；三因素归因：dHash 域漂移（根因）、x86_64 翻译桥 OCR 降级、画面缩放。
2. **翻译桥 bug 已修复**（两处二进制 patch），但 OCR 的 SCVTF 指令仍被跳过，属降级运行。
3. **任何"CDN 纯图标造数据→迁移到游戏格"的路线都实测失败**：合成训练真实帧 37.5%、模板匹配错位、霍夫分格失败。
4. **真实帧标注训练有效**：同源 3 折 68.4%、全量自测 100%；但样本少（19 条/11 类），且是分类器不是检测器，尚未替换进 APK。
5. **APK 换算法的代码路径已摸清**：保持 `VisionEngine.detect` 的 `[1,3,640,640]→[1,100,6]` 契约换 ONNX 模型，改 `ScanProcessor` 绕过 OCR 切格，可 `./gradlew --offline assembleRelease` 重编译。只差足够的真实检测标注。
