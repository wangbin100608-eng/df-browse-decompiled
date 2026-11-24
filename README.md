# 三角洲行动 · 理包模拟器 —— 浏览游戏模块反编译与可游玩站点

本仓库包含两样东西：

1. **`browse反编译.html`** —— 「浏览游戏」模块的**反编译源码浏览页**（静态单文件）。
2. **`playable/`** —— 基于反编译成果重建的、**可实际游玩的站点**（完整前端镜像 + API 反向代理服务），线上地址：<https://game.czyz.eu.cc/>

> 两者用途不同：前者用于**看代码**，后者用于**玩游戏**。

---

## 一、可游玩站点（playable/）

### 线上体验

- 地址：<https://game.czyz.eu.cc/>
- 玩法路径：首页 → 「浏览场景」→ 选一个场景 → 「加载场景」→ 弹窗点「继续」（无需登录）→ 进入理包游戏。
- 全部功能与原站 <https://dfc.lza11111.com/browse> 一致：场景列表/搜索/排序/评分、场景详情与排行榜、WASM 游戏核心、计时、物品与收益计算。

### 为什么能"一样游玩"

原站是 Vite + React 的单页应用，游戏核心逻辑编译在 WebAssembly（`.wasm`）里，数据来自需鉴权的后端 API。因此**重写游戏逻辑不现实**，本项目采用保真路线：

- **前端 100% 镜像**：把原站 `/browse` 及其全部传递依赖（110 个 JS chunk、1 个 CSS、2 个 WASM 核心、22 张 UI 图片等，共 138 个文件）原样下载到本地。
- **WASM 原样运行**：游戏引擎 `BBjP65vW.wasm`、`Se1zKGnR.wasm` 直接从原站 CDN 拉取到本地，游戏行为与原站完全相同。
- **API 同源反向代理**：前端写死的 `/api/*` 请求由本地 Node 服务转发到上游 `dfc.lza11111.com`。关键点在于——浏览器看到的是**同源** `/api/*`，彻底绕开跨域（原站 CORS 白名单不含自有域名，直连会被浏览器拦截）。反向代理同时透传 Cookie 与鉴权头，登录/提交记录等端点也能正常工作。
- **静态资源改同源**：原 HTML 里的 CDN 基址 `https://df-assets.lza11111.com/web/` 改为 `/`，资源全部从本地提供。

```
浏览器 ──https──> game.czyz.eu.cc (Cloudflare Tunnel)
                        │
                        ├─ /assets/* 静态文件 ──> 本地镜像（playable/）
                        ├─ /api/*    反向代理 ──> https://dfc.lza11111.com/api/*
                        └─ *.wasm    本地 WASM 核心
```

### 本地运行

需要 Node.js 18+，无需安装任何依赖（仅用内置模块）。

```bash
cd playable
node server.js
# 默认监听 0.0.0.0:8095，可用环境变量 PORT 修改
```

然后浏览器打开 <http://localhost:8095/>。

`server.js` 的职责：

- 托管当前目录的静态文件，带合理缓存头（HTML 不缓存、资源长缓存）与 SPA 路由回退（无扩展名路径回退到 `index.html`）。
- 把 `/api/*` 与 `/health` 反向代理到 `https://dfc.lza11111.com`，去除上游 CORS 头，让浏览器按同源处理。
- 提供正确的 `Content-Type`（含 `application/wasm`），保证 WASM 能实例化。

### 目录结构

```
playable/
├── index.html                  # 原站 SPA 入口（CDN 基址已改为同源 /）
├── manifest.json               # PWA manifest
├── server.js                   # 静态托管 + API 反向代理服务
├── *.png                       # 图标 / UI 装饰图（gold-icon、safebox、slot-placeholder 等）
├── icons/                      # 品质边框、物品徽章等图片
├── safebox/ slot-placeholder/  # 游戏格子占位图
└── assets/
    ├── index-DdGw8vbq.js       # 入口 chunk
    ├── BVTo7rNE.js             # 浏览模块 chunk
    ├── BJ1yUifx.js             # WASM glue 核心（674 KB）
    ├── BBjP65vW.wasm           # 主游戏核心（3.5 MB）
    ├── Se1zKGnR.wasm           # 辅助 WASM（205 KB）
    ├── DwlFuPD4.css            # 全站样式
    └── ...（共 110 个 JS）
```

统计：138 个文件，约 7.4 MB（JS 2.7 MB / WASM 3.6 MB / 图片 1.1 MB）。

### 线上部署架构（当前实例）

- 服务器：一台常驻主机，运行 `playable/server.js`（systemd 服务 `df-game.service`）。
- 公网入口：Cloudflare Tunnel（`df-game`），DNS `game.czyz.eu.cc` CNAME 到该隧道，ingress 指向 `http://127.0.0.1:8095`。
- 两个服务均配置开机自启与崩溃重启。

> 说明：数据（场景、排行榜、价格等）实时来自原站 API，本地不存业务数据库。若上游接口变更或下线，游戏界面仍可打开，但动态数据会受影响。

---

## 二、反编译源码浏览页（browse反编译.html）

仓库根目录的单文件，约 5.3 MB，供**阅读代码**使用，不需要服务器，双击即可离线打开。

- 来源：从 <https://dfc.lza11111.com/browse> 的 Vite 打包产物还原。
- 内容：`/browse` 路由入口 `Y-x8muZd.js` 及其全部传递依赖，共 43 个模块，经 Prettier 格式化。
- 功能：左侧文件树、文件名过滤、上/下一个模块切换、JS 语法高亮。
- 在线浏览：<https://lb.czyz.eu.cc/browse反编译.html>（根目录 `index.html` 为跳转页）。
- `CNAME` 文件内容：`lb.czyz.eu.cc`，用于 GitHub Pages 自定义域。

> 该页只是源码阅读器，**不能游玩**；要玩游戏请用上面的 `playable/` 或 <https://game.czyz.eu.cc/>。

---

## 三、技术背景

- 原站技术栈：Vite + React + TanStack Router，核心逻辑为 Rust/C 编译的 WebAssembly。
- `/browse` 路由在入口 chunk 中通过 `__vite__mapDeps` 懒加载，依赖集合由此精确还原。
- 关键 API：`/api/games/browse`（场景列表，无需鉴权）、`/api/games/:id`（详情）、`/api/games/:id/records`（排行榜）、`/api/prices`（价格）、`/api/games/:id/run-ticket`（运行凭证）等。
- 上游 CORS 白名单仅含原站域名，自有域名直连会被拦截，故必须同源反向代理。

## 四、免责声明

本项目为技术逆向与本地化复现研究。游戏数据、图片、素材版权归腾讯公司及《三角洲行动》官方所有，仅用于学习与个人使用。

---

## 五、快速索引

| 想要 | 去哪里 |
|---|---|
| 直接玩游戏 | <https://game.czyz.eu.cc/> |
| 本地跑游戏 | `cd playable && node server.js` |
| 看反编译源码 | 打开 `browse反编译.html` 或 <https://lb.czyz.eu.cc/> |
| 了解游戏站原理 | 本文「一、可游玩站点」 |
| 部署细节 | 本文「一 → 线上部署架构」 |
