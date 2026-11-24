# 资源分析报告（DeltaInventory）

生成时间：2026-11-24
分析对象：`df-browse-decompiled` 仓库全部文件 + 上游 `dfc.lza11111.com` 静态资源

## 仓库内容

142 个文件：Vite/React 前端镜像（110 JS + 1 CSS + 2 WASM）、22 张 UI 图片、反编译 HTML。
**仓库内没有物品大图**——运行时从 CDN 加载。

## 物品数据提取（关键成果）

物品元数据固化在 WASM 内置表，静态 JS 无明文。采用 Node 直接实例化 `BBjP65vW.wasm`（stub imports）调用 `wasm_get_all_items()`，导出 **1663 条真实物品记录**，包含：

```
objectID, objectName, shortName, length, width, grade, weight,
primaryClass, secondClassCN, desc, pic, prePic, marketPrice, displayValue ...
```

分类分布：props 795 / acc 582 / ammo 119 / protect 98 / gun 69。
**本项目范围仅普通非武器物品**：过滤后 893 件（props + protect + 其他，排除 gun/ammo/acc）。

尺寸字段为 `length × width`（前端根据旋转互换），**不是** gridWidth/gridHeight。
所有物品名、尺寸均来自真实提取，未猜测；无法确认字段标记 unknown/null。

## 图标 URL

真实 `prePic` 形如 `/playerhub/60004/object/p_<objectID>.png`。
构造规则（来自 `MgERhGBy.js`）：基址 `https://df-assets.lza11111.com` + `.png → .webp` 重写。
示例（已 curl 验证 200 image/webp）：
- `p_11050006001.webp` 金刚防弹衣 4×4
- `p_11050051001.webp` M4A1 突击步枪 5×2
- `p_37150600001.webp` 6.8×51mm AP 1×1

## WASM 可复用接口

真正 glue 是 `yzrtAEyr.js`（`deltaforce_repack_sim_core_bg.js` 实为 HTML 副本，0 export）：

- `wasm_get_all_items()` / `wasm_get_item(id)` / `wasm_get_items_by_class(class)`
- `wasm_get_effective_dimensions(item, rotated)` → `{w,h}`
- `wasm_calculate_item_price(itemJson)` / `wasm_calculate_item_unit_price(itemJson)`
- `wasm_load_price_book(bytes)` / `wasm_use_version(v)` / `wasm_has_price_version(v)`
- `wasm_validate_and_calculate` / `wasm_serialize_game_state` / `wasm_deserialize_game_state`

Android 端采用「离线导出 JSON + Rust 自实现核心算法」路线，避免运行时依赖 WASM glue。

## API 数据结构

- `/api/games/browse` → 场景列表（无需鉴权）
- `/api/games/{id}` → `gameJson` 含 player 装备槽、lootSources 物品实例（`objectID` + `position` + 耐久）
- `/api/games/{id}/records` → 排行榜记录
- 价格见 `price-source-report.md`

## 静态 vs 动态

| 类型 | 内容 | 本项目处理 |
|---|---|---|
| 静态 | JS/CSS/WASM/UI 图标 | 离线携带 |
| 静态数据 | 物品表（1663） | 离线携带 item-database.json |
| 动态数据 | 价格 DFPB、场景、排行榜 | 本地缓存 + 可选联网同步 |

## 授权状态

所有图片/数据版权归腾讯公司及《三角洲行动》官方，本项目为个人本地技术研究用途，不作为商业分发。
