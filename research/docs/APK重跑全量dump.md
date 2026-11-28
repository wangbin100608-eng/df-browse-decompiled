# APK 重跑全量 dump（改进采集 + 对照实验）

- **时间**：2026-11-28 08:37–08:52（UTC）
- **环境**：Redroid 11 x86_64，翻译桥已 patch（SHA `b6cb92c2…`），release APK v1.0.0（官方签名）
- **输入**：`v1_fill.mp4` / `v2_fill.mp4`（原视频 2560×1600 → 拉伸填满 1080×1920，0.5× 慢放，让图标占满采集工作图）

---

## 一、APK 的全部输出（逐实验）

### 实验 A：App 内对照——纯图标域（CDN CINDER 弹药原图铺屏）

| 项 | 结果 |
|---|---|
| 输入 | CDN 原图 `p_37100400003.webp`（5.56x45mm CINDER）放大到 760×760 居中铺满 1080×1920 深色背景 |
| 采集 | App MediaProjection 前台服务正常，悬浮球出现 |
| 结果 | **已确认物资 0 种**，格数"未识别到（默认 3×3）" |
| 截图 | `dump/ctrl-icon-display.png`（铺屏输入）、`dump/ctrl-icon-result.png`（0 件） |

**判定**：连纯图标域在 App 内也是 0 件。按既定预案，标注为**未能干净归因**——App 内 OCR 降级（SCVTF 被 patch 跳过）导致格数读不到、无法切格，纯图标对照也被这个混杂因素污染，不能据此单独判定 dHash 问题。主归因仍以 accuracy-report 的一手实测为准。

### 实验 B：v1 视频（铺满 + 慢放）

| 项 | 结果 |
|---|---|
| 输入 | v1_fill.mp4（游戏画面铺满全屏，见 `dump/v1_fill-preview.png`） |
| 采集时长 | 全片约 13 秒（0.5× 慢放） |
| 结果 | **策略已生成，已确认物资 0 种**，格数仍未识别 |
| 截图 | `dump/v1-fill-playing.png`、`dump/v1-fill-result.png` |
| 崩溃 | 0 |

### 实验 C：v2 视频（铺满 + 慢放）

| 项 | 结果 |
|---|---|
| 采集时长 | 全片约 20 秒 |
| 结果 | **策略已生成，已确认物资 0 种** |
| 截图 | `dump/v2-fill-playing.png`、`dump/v2-fill-result.png` |
| 崩溃 | 0 |

**三类输入（纯图标 / v1 游戏 / v2 游戏）APK 全部输出 0 件。**

---

## 二、数据库全表 dump（12 张表，WAL 已合并）

导出自 `/data/data/com.deltainventory.app/databases/delta_inventory.db`，完整内容在 `dump/full_db_dump.txt`。

| 表 | 行数 | 说明 |
|---|---|---|
| `items` | 1663 | 物品库（正常） |
| `item_prices` | 1663 | 价格库（正常） |
| `scan_sessions` | 4 | 4 次采集会话（对照1 + v1原 + v2原 + 本轮3次中的记录） |
| `optimization_results` | 4 | 装箱结果，空输入 |
| `item_images` | 0 | — |
| **`item_instances`** | **0** | **识别出的物资实例：0** |
| **`recognition_events`** | **0** | **识别事件：0** |
| **`frame_records`** | **0** | **帧记录：0** |
| `containers` | 0 | — |

三张识别结果表全为 0，与 UI"已确认物资 0 种"一致。

---

## 三、运行日志统计

完整日志在 `dump/full_logcat.txt`（688 行）。

| 指标 | 值 |
|---|---|
| `Fatal signal` / `Abort message` | **0**（无崩溃） |
| `Undefined instruction 0x5ea1b842`（SCVTF） | **271 次**（翻译桥跳过，OCR 降级直接证据） |

---

## 四、为什么铺满+慢放仍然是 0 件

1. **OCR 降级未解决**：271 次 SCVTF 跳过 → 格数 OCR 始终读不到（每次都显示"默认 3×3"）。没有网格尺寸，App 无法把画面切成单格 ROI，图标匹配缺少输入区域——这解释了为什么连纯图标铺屏都 0 件。
2. **dHash 域漂移（根因，与平台无关）**：即使切格正常，accuracy-report 一手实测显示真实渲染格 4/4 全错配（汉明距离 6~25），纯图标库无法匹配游戏渲染格。铺屏放大只改变尺寸，不改变"深色背景+文字+角标"的域。
3. 画面质量因素通过铺满+慢放已基本消除，但 0 件不变，进一步支持前两条。

---

## 五、下一步（按已确认的共识）

APK 这条路已到头：在 x86_64 下 OCR 降级 + 算法本身对真实格子失效，重跑多少次都是 0 件。接下来按你之前的选择，改为**直接用高清视频帧产出准确物资清单**（你提供正确清单文件 → 我逐条核对帧证据 + 用 item-database.json 补单价 → 输出 Markdown + CSV 合并总表）。

---

## 六、dump 文件清单

- `dump/ctrl-icon-display.png`、`dump/ctrl-icon-result.png` — 纯图标对照输入/结果
- `dump/v1_fill-preview.png`、`dump/v1-fill-playing.png`、`dump/v1-fill-result.png` — v1 铺屏采集
- `dump/v2-fill-playing.png`、`dump/v2-fill-result.png` — v2 铺屏采集
- `dump/full_db_dump.txt` — 12 张表完整导出
- `dump/full_logcat.txt` — 运行日志
- `dump/db_final.db` + wal — 原始数据库快照
- `dump/v1_fill.mp4`、`dump/v2_fill.mp4` — 铺屏慢放输入视频
