# 数据库说明

## 引擎

SQLite via AndroidX Room 2.6.1，数据库名 `delta_inventory.db`，version 1。
`exportSchema=true`；当前版本使用 `fallbackToDestructiveMigration()`，后续版本应提供显式 Migration。

## 表结构（9 张）

| 表 | 主键 | 关键索引 | 用途 |
|---|---|---|---|
| `items` | itemId | name, category | 物品静态数据（名/尺寸/图标/基础价值） |
| `item_images` | id(auto) | itemId | 物品图片索引（本地路径/远程URL/phash） |
| `item_prices` | id(auto) | itemId, updatedAt | 价格历史（值/来源/更新时间/版本/有效天数） |
| `containers` | containerId | — | 容器定义（背包/地面/安全箱，网格尺寸） |
| `item_instances` | instanceId | sessionId, itemTypeId, containerId, lastSeenFrame | 物品实例账本（核心） |
| `scan_sessions` | sessionId | startedAt | 盘点会话 |
| `frame_records` | id(auto) | sessionId, frameIndex | 帧记录（时间/变化分/推理耗时） |
| `recognition_events` | id(auto) | sessionId, frameIndex | 识别事件（created/matched/pending/merged/split/discarded） |
| `optimization_results` | id(auto) | sessionId | 装箱方案结果（JSON/价值/耗时） |

## 外键与约束

- `item_instances.containerId → containers.containerId`
- 实例账本以 `instanceId` 唯一主键；所有统计基于此表，不从帧记录累加。
- 价格按 `(itemId, updatedAt)` 保留历史，查询取最新。

## 事务与并发

- Room DAO 的 `@Insert/@Update` 默认事务化；批量操作通过 `@Transaction` 或 `withTransaction`。
- ViewModel 用 `viewModelScope` 串行化数据库写入；UI 读通过 Flow 观察。

## 离线与备份

- 数据库不依赖网络；物品表与价格快照随 APK 离线携带。
- `DatabaseProvider.backup()`：`PRAGMA wal_checkpoint(FULL)` 后复制 db 文件。
- `DatabaseProvider.restore()`：关闭数据库后覆盖文件。

## 数据导入

`tools/import_assets.py` 把 `item_table.json`（WASM 导出 1663 件）转为 `item-database.json`（893 件普通物品），
字段无法确认时写 null，不猜测。APK 构建时把 JSON 放入 assets，首次启动导入 Room。

## 价格来源

`/api/prices` → 版本号 → `/api/prices/{v}`（DFPB 二进制）由 WASM 解码。APK 携带 v6（2026-11-17）快照。
UI 显示价格更新时间；超 `validDays` 标记过期；缺失标记 unknown 而非猜测。
