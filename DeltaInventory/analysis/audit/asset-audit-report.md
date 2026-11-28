# 视觉数据资产审计报告

- **审计时间**：2026-11-28
- **审计对象**：`/root/工作区/df-browse-decompiled`（GitHub `wangbin100608-eng/df-browse-decompiled`）
- **审计原则**：只读审计，不修改现有数据、不重新生成已有数据、不启动训练
- **范围**：`DeltaInventory/analysis/`、物品特征库、参考图片、脚本、ONNX、真实标注截图

---

## 1. 执行摘要

| 发现 | 结论 |
|---|---|
| `analysis/` 下 8 个文件 | 4 JSON + 1 CSV + 3 MD/文本，结构完整，覆盖 1663 件物品 |
| **仓库内物品参考图片本体** | **0 张**——1663 件物品的图片全部只有 CDN URL，无本地缓存 |
| 仓库内实际图片 | 22 张，全是 UI 图标（favicon / 格子占位 / 安全箱尺寸 / 品质框），**不是物品参考图** |
| `item-features.json` | 1663 条，id 与 `item-database.json` **100% 一一对应** |
| 特征质量 | 有问题：颜色直方图 1271/1663 条只有 1 个非零桶；87 组共 350 件物品 phash 完全相同 |
| 裁剪/预处理脚本 | `build_features.py`（下载+phash+hist），无裁剪/标注脚本 |
| 训练脚本 | **仓库内不存在** `train_detector.py`（README/accuracy-report 提到但文件缺失） |
| ONNX 模型 | `item_detector.onnx` 9956 字节，未训练骨架，5 个算子，confidence 被压低 |
| 真实游戏画面标注 | **存在**：`research/train/real_labels.jsonl` 19 条 / 11 类，13 张高清帧，2 张网格图 |
| 普通非武器类 | `props` 795 件、`protect` 98 件；仓库内其参考图片 **0 张**本地持有 |

**一句话**：数据"元数据完备、图片本体缺失、特征库有质量缺陷、真实标注刚起步"，训练所需的最大缺口是**物品参考图片的本地持有**和**真实域标注规模**。

---

## 2. `DeltaInventory/analysis/` 文件审计

| 文件 | 大小 | 结构 | 内容 |
|---|---|---|---|
| `assets-manifest.json` | 1.2 KB | dict | `generatedAt=2026-11-24`、来源、许可证、**2 个 WASM 文件**（主 3.5 MB 提取 1663 件、辅 200 KB）、`iconUrlTemplate=https://df-assets.lza11111.com{prePic .png→.webp}` |
| `item-database.json` | 670 KB | dict | `version=2, totalItems=1663, priceVersion=6, priceDate=2026-11-17`；`items[1663]`，字段 itemId/name/shortName/width/height/category/subClass/grade/baseValueCents/displayValueCents/priceVersion/priceDate/iconPath/source |
| `item_table.json` | 1.4 MB | list[1663] | 原始 WASM 导出，字段 objectID/objectName/shortName/shortNameSec/length/width/grade/weight/primaryClass/secondClass |
| `item_table_v6.json` | 1.4 MB | list[1663] | 同上，v6 价格快照版本 |
| `asset-inventory.csv` | 461 KB | 表头 12 列 | 1663 行：objectID/objectName/shortName/length/width/grade/primaryClass/secondClassCN/prePic/pic/**icon_url**/license；prePic 1663 个全部唯一，无重复 |
| `icon_examples.txt` | 3.7 KB | 文本 | 图标 URL 示例清单 |
| `resource-analysis.md` | 2.9 KB | 文档 | WASM 分析过程：调用 `wasm_get_all_items()` 提取 1663 件 |
| `price-source-report.md` | 1.6 KB | 文档 | 价格来源：上游 `/api/prices` DFPB v6（2026-11-17） |

**结论**：元数据层（名称/尺寸/价格/分类/图标 URL）完备且一致；`analysis/` 不含任何图片二进制文件。

---

## 3. 参考图片实际存储位置

### 3.1 仓库内图片文件（22 张，均非物品参考图）

```
playable/apple-touch-icon.png, favicon-16x16.png, favicon-32x32.png,
gold-icon.png, icon-192x192.png, icon-512x512.png      → PWA 图标
playable/icons/item-badges/*.png (4)                    → 物品角标（绑定/锁定/切换/队友）
playable/icons/quality/*.webp (2)                       → 品质框背景
playable/safebox/*.png (4)                              → 安全箱 1x2/2x2/2x3/3x3 尺寸图
playable/slot-placeholder/*.png (6)                     → 背包格子占位图
```

### 3.2 物品参考图片的真正来源

- **CDN 模板**（`assets-manifest.json` + `tools/build_features.py` 第 24 行）：
  `https://df-assets.lza11111.com/playerhub/60004/object/p_<itemId>.webp`
- CSV 的 `icon_url` 列给出 1663 条完整 URL。
- **这些图片未被下载进仓库**。`build_features.py` 下载到临时 cache（`{itemId}.webp`），算完 phash/hist 后不保留原图。
- 本机临时缓存现状（本次审计外）：`research/train/icons/` 15 张、`/root/盘点/train/icons/` 15 张、`/root/盘点/icons/` 4 张——都是后续研究中按需下载的样本，**不属于仓库既有资产**。

**结论**：仓库**不持有** 1663 件物品的参考图片本体；只有 URL。任何"基于现有参考图片训练"的计划，第一步必须是下载。

---

## 4. `item-features.json` 与原始图片的对应关系

- **结构**：`{"features":[{id,name,w,h,cat,price,ph,hist}, ...]}`，1663 条。
- **id 对应**：与 `item-database.json` 的 1663 个 `itemId` **完全一致**（db-only=0，feat-only=0）。
- **`ph`**：64-bit dHash 的无符号十进制字符串，由 `build_features.py:phash()` 从 CDN 图计算（9×8 步长采样灰度 + 相邻比较，RGBA 预乘）。
- **`hist`**：16 桶颜色直方图，由 `build_features.py:hist()` 计算（32×32 采样，亮度归一化）。

**质量问题（实测）**：

| 指标 | 数值 | 影响 |
|---|---|---|
| 唯一 phash | 1400 / 1663 | 87 组共 350 件物品视觉哈希完全相同，**物理上不可区分** |
| 最大重复组 | 16 件 | 同外观物品（如不同颜色弹药） |
| hist 非零桶 | 1271 条仅 1 个非零桶 | 颜色直方图几乎全为单一桶，**区分力极弱** |
| hist 非零桶分布 | 1桶:1271, 2桶:240, 3桶:97, 4桶:54, 5桶:1 | 与透明背景占主导有关 |

**结论**：特征库与物品 id 一一对应、可复现，但特征本身区分力有限——这是 `accuracy-report.md` 已披露的 dHash 物理上限，本次审计在特征层面独立复核。

---

## 5. 脚本审计

| 脚本 | 存在 | 功能 |
|---|---|---|
| `tools/build_features.py` | ✅ | 下载 CDN 图标 → 计算 dHash(ph) + 16 桶 hist → 生成 `item-features.json` |
| `tools/import_assets.py` | ✅ | 导入资源 |
| `tools/fetch-opencv.sh` / `build-rust-android.sh` | ✅ | 构建依赖 |
| **图片裁剪脚本** | ❌ 不存在 | — |
| **图片预处理脚本**（除 build_features 的特征提取外） | ❌ 不存在 | — |
| **数据标注脚本** | ❌ 不存在 | — |
| **`tools/train_detector.py`** | ❌ **不存在** | README 第 "六、已知限制" 与 `models/README.md` 提到"有标注数据后可用 `tools/train_detector.py` 训练"，但仓库 tools 目录中**无此文件**（GitHub raw 404） |

**结论**：现有脚本只覆盖"物品元数据→特征库"这一条链；训练、标注、裁剪链路**全部缺失**。

---

## 6. ONNX 模型状态

| 项 | 值 |
|---|---|
| 文件 | `DeltaInventory/app/src/main/assets/models/item_detector.onnx` |
| 大小 | 9956 字节 |
| 版本标识 | `models/version.txt` = `untrained-stub-v1-20261124` |
| opset | 13，ir_version 8 |
| 输入 | `images` float32 `[1,3,640,640]`（RGB 归一化到 [-1,1]） |
| 输出 | `output` float32 `[1,100,6]` = `[x,y,w,h,conf,cls]` |
| 算子 | `GlobalAveragePool, Reshape, MatMul, Add, Reshape`（仅 5 个） |
| 实际能力 | **未训练骨架**：confidence≈0.0067，低于 0.35 阈值，mAP=0，不产生有效检测 |

`VisionEngine.kt` 已实现完整输入/输出/后处理链路，模型替换无需改 Kotlin 推理端。

---

## 7. 真实游戏画面已标注截图

**存在**，在本次研究新增的 `research/` 目录（已推送到 GitHub）：

- `research/train/annotate/`：13 张 2560×1600 高清帧（v1 6 张、v2 7 张），其中 2 张叠加 100px 坐标网格（`*_grid.png`）
- `research/train/real_labels.jsonl`：**19 条真实标注**，格式 `frame/name/x0/y0/x1/y1`，覆盖 11 类：
  `5.56x45mm CINDER`(4)、`强效注射器`(4)、`5.56x45mm M995`(3)、`UZI冲锋枪`、`M1911`、`北极星`、`突击手防弹背心`、`MHS 战术头盔`、`武士防弹背心`、`GA野战背包`、`CAT止血带`(各 1)
- `research/train/real_classes.json`：类别编号映射
- `research/train/net_real_final.pt`：用这 19 条标注训练的分类器（同源 3 折 68.4%）

**结论**：真实域标注**存在但规模很小**（19 条/11 类），只够验证方向，不够训练检测器。

---

## 8. 普通非武器类物品统计（按现有特征库）

"普通非武器类"按仓库 `category` 定义：`props`（795）与 `protect`（98）（`gun`/`acc`/`ammo` 为武器/配件/弹药）。

| 类别 | 物品数 | 有特征 | 唯一 phash | 重复 phash 组 | 本地参考图 |
|---|---|---|---|---|---|
| `props` 收集品 | 669 | 669 | — | — | 0 |
| `props` 钥匙 | 80 | 80 | — | — | 0 |
| `props` 消耗品 | 34 | 34 | — | — | 0 |
| `props` 曼德尔砖 | 12 | 12 | — | — | 0 |
| **props 小计** | **795** | 795 | 619 | 51 | 0 |
| `protect` 护甲 | 26 | 26 | — | — | 0 |
| `protect` 头盔 | 27 | 27 | — | — | 0 |
| `protect` 胸挂 | 19 | 19 | 98 | 0 | 0 |
| `protect` 背包 | 26 | 26 | — | — | 0 |
| **protect 小计** | **98** | 98 | 98 | 0 | 0 |

（全库其他类：ammo 119 / 47 唯一 phash / 24 重复组；acc 582 / 568 唯一；gun 69 / 69 唯一）

### 图片质量与重复（对本机持有的 15 张样本）

- 尺寸分布：300×150（5 张，枪械类）、336×336（3）、258×286（2）、其余 143×242 / 168×216 / 192×259 / 202×259 / 300×300 各 1——**尺寸不统一**，宽高比从 2:1 到 1:2 都有。
- 格式：RGBA WebP，透明背景（特征库假设透明预乘）。
- 本机样本无 MD5 重复；全库层面的重复以 phash 计（见上）。

---

## 9. 参考图片与真实游戏截图的差异

| 维度 | CDN 参考图 | 真实游戏格子 |
|---|---|---|
| 背景 | 透明（RGBA alpha） | 深色不透明 + 网格线 + 高亮 |
| 尺寸 | 143–336 px，画布多为正方形 | 小格本体 40–80 px，大格 120–240 px（2560×1600 帧） |
| 附加元素 | 无 | 中文名称标签、数量角标（+60/8/13）、耐久条、磨损遮罩 |
| 压缩 | WebP 原始 | 游戏渲染 + JPEG 压缩 + 缩放 |
| 特征表现 | 纯图标域自匹配 1663/1663、5/5 正确 | 真实渲染格 4/4 全错配（accuracy-report；本次研究合成训练迁移仅 37.5%） |

**结论**：两者是**不同域**，不能用纯参考图特征直接匹配真实截图（这是已实测证伪的路线）。

---

## 10. 缺失的数据类型

1. **物品参考图片本体**：1663 张 CDN WebP 未本地持有（只有 URL）；
2. **真实域检测标注**：整张游戏帧上所有物资的 bbox（当前只有 19 条孤立裁剪，无"整帧全标注"）；
3. **训练脚本**：`tools/train_detector.py` 缺失；
4. **裁剪/预处理/标注工具**：全部缺失；
5. **足量真实样本**：每类 ≥8 个真实裁剪（当前 7 类只有 1 个样本）；
6. **统一尺寸规范**：参考图尺寸不一，无标准化流水线；
7. **可区分特征**：hist 退化、87 组同 phash，缺少对同外观物品的有效特征；
8. **验证集**：无独立、带标注的真实游戏帧测试集。

---

## 11. 审计边界

- 未修改任何现有文件（仅新增本报告与 `image-inventory.csv`）；
- 未下载 1663 张图片、未启动训练、未重新生成特征；
- 本机已有的 15 张图标 / 19 条标注为前序研究产物，本次仅盘点不改动。
