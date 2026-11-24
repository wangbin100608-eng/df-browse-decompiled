# 价格数据来源报告

生成时间：2026-11-24

## 上游价格接口

| 端点 | 返回 | 说明 |
|---|---|---|
| `GET /api/prices` | JSON | `{"latest":6,"versions":[{"version":6,"priceDate":"2026-11-17"}, ... version 1..6]}` |
| `GET /api/prices/{version}` | **二进制 DFPB** | 例 v6 = 14,963 字节，magic `DFPB`，非 JSON |
| `GET /api/prices/latest` | 404 | 不支持，必须先取版本号 |

## 价格数据结构

价格本体不在 JS 层，而是：

1. 前端下载 DFPB 二进制；
2. 转成 `Uint8Array` 喂给 WASM `wasm_load_price_book(bytes)`；
3. WASM 内部与物品表关联；
4. 通过 `wasm_calculate_item_price(itemJson)` / `displayValue` 字段读出。

**已验证**：加载 v1..v6 均返回对应版本号；`wasm_use_price_version(6)` 后，1663 件中 1249 件 `displayValue` 变化（例：金刚防弹衣 965758 → 1045157），`marketPrice` 为内置基准价。

## 本项目的处理

- Android 端离线携带 `item-database.json`，其中的 `displayValue` 来自 v6（2026-11-17）快照，**已标注来源与时间**，不冒充实时价格。
- 价格表字段 `item_prices` 记录 `source / updatedAt / version`，UI 展示数据更新时间，支持过期判断。
- 联网同步仅用于拉取新版 DFPB 并由 WASM/导入工具解码；网络不可用时完全离线工作。
- **不猜测价格**：无法确认价值的物品 `baseValueCents = null`，统计时标记 missing。

## 授权状态

价格数据来自 `dfc.lza11111.com` 公开接口，版权归原站/腾讯所有，本项目仅作个人本地研究用途。
