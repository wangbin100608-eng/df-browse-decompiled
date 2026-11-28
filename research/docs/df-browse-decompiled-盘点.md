# df-browse-decompiled 仓库盘点报告

- **调查对象**：<https://github.com/wangbin100608-eng/df-browse-decompiled>
- **默认分支**：`main`（最新提交 `c40e41f8`，2026-11-28T02:10:28Z）
- **调查方式**：GitHub REST API（仓库元数据、recursive tree、commits、issues）、raw.githubusercontent.com 原始文件。所有结论均附来源。
- **报告位置说明**：工作目录 `/root/盘点` 调查时为空目录，无 `notes/`、`docs/` 等既有笔记约定；按要求放在根目录，文件名 `df-browse-decompiled-盘点.md`。

---

## 0. 一句话结论

这是一个**混合型仓库**：最初目的是做一个「三角洲行动（Delta Force）浏览游戏模块」的反编译源码在线查看器（静态单页 HTML），后来（同一作者）陆续加入了两个衍生项目——一个可玩的「理包模拟器」前端镜像站点（带 API 反向代理），以及一个完整的 Android 原生应用 **DeltaInventory**（用逆向提取的真实游戏数据做本地物资盘点与背包优化）。当前 README 和大部分提交活动都围绕 DeltaInventory。

---

## 1. 项目是什么？"df-browse" 指什么？被反编译的对象是什么？

### 1.1 项目定位与仓库元数据

- GitHub 仓库描述：`Delta Force /browse module decompiled source viewer (43 modules)`。语言标记为 HTML，无 License（`license: null`），无 open issues，有 Pages（CNAME 指向 `lb.czyz.eu.cc`）。来源：[仓库元数据 API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled)、[CNAME 原文](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/CNAME)。
- 仓库于 2026-11-23 创建，首个提交即 `Add decompiled browse module viewer`。来源：[commits API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/commits?per_page=100)。

### 1.2 "df-browse" 的含义

`df` = Delta Force（三角洲行动），`browse` = 游戏内的 **"/browse"（浏览游戏/浏览场景）路由页面**——即浏览他人分享的游戏场景（关卡/包袱摆法）、查看排行榜、评分、下载场景配置的页面。

直接证据（均来自反编译产物本身）：

- 根 `index.html` `<title>` 为「三角洲行动 - 浏览游戏模块反编译源码」，并 0 秒 meta refresh 跳转到 `./browse反编译.html`。来源：[index.html](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/index.html)。
- `browse反编译.html` 标题「浏览游戏模块 · 反编译」，副标题「/browse 路由 · 43 个模块 · 1.08 MB」，底部注明「来源：dfc.lza11111.com · Vite/React 打包产物；入口 Y-x8muZd.js 导出 component」。来源：[browse反编译.html](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/browse%E5%8F%8D%E7%BC%96%E8%AF%91.html)。
- 入口模块 `playable/assets/Y-x8muZd.js` 中可见路由与组件逻辑：`o({to:/browse,...})`、`e('browseGamesScreen.browseScenes')`、`e('browseGamesScreen.sceneList')`、`/game/$gameId/record/$recordId`、`needsSignIn`、`signInDialogs` 等，且文件末尾 `export{Ie as component}`。来源：[Y-x8muZd.js](https://github.com/wangbin100608-eng/df-browse-decompiled/blob/main/playable/assets/Y-x8muZd.js)、其 [raw 文件](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/assets/Y-x8muZd.js)。
- 国际化文案（在 `playable/assets/BVTo7rNE.js` 中）包含 `browseGamesScreen` 命名空间：浏览场景、场景列表、排行榜、分享记录、编辑场景、加载场景失败等中文词条。来源：[BVTo7rNE.js](https://github.com/wangbin100608-eng/df-browse-decompiled/blob/main/playable/assets/BVTo7rNE.js)。

### 1.3 被反编译的对象

被反编译的是**上游站点 `dfc.lza11111.com`（第三方"理包模拟器"）的 Vite/React 前端打包产物**，重点是其 `/browse` 路由对应的代码分割 chunk。依据：

- `browse反编译.html` 底部「来源：dfc.lza11111.com · Vite/React 打包产物」。
- `playable/server.js` 中 `const UPSTREAM = 'dfc.lza11111.com'`；`PROXY_PREFIXES = ['/api/']`，把 API 请求反代到 `https://dfc.lza11111.com` 以绕过 CORS。来源：[playable/server.js](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/server.js)。
- `playable/assets/index-DdGw8vbq.js` 开头是典型 Vite 动态 import map（`__DF_STATIC__ + "assets/XXXX.js"` 形式，`__DF_BROWSER_SUPPORT__` 检查），依赖 React 生产包（可见 `Minified React error` 字样）。来源：[index-DdGw8vbq.js](https://github.com/wangbin100608-eng/df-browse-decompiled/blob/main/playable/assets/index-DdGw8vbq.js)。
- `web_fetch` 实际检查 `browse反编译.html`：大小约 5.59 MB，内嵌 43 个 `data-file` 按钮（即 43 个模块源码）及对应语法高亮 `<pre>` 代码块，首屏展示入口 `Y-x8muZd.js`（标注 1988 行 / 73.7 KB）。来源：[browse反编译.html](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/browse%E5%8F%8D%E7%BC%96%E8%AF%91.html)、[tree API 中的 size 字段](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/git/trees/main?recursive=1)。
- 注意措辞：仓库自称「反编译源码」，但从产物看更准确地说是**保留原变量名压缩过的 Vite 打包 JS（未必经过真正的 decompiler）**，查看器做的是把 43 个 chunk 以 `<pre>` 内嵌展示并做语法高亮。

### 1.4 反编译产物的用途（数据是如何被进一步利用的）

仓库作者从上游 WASM 中**直接导出了真实游戏物品数据**，作为 Android 项目的基础：

- `DeltaInventory/analysis/resource-analysis.md`：通过 Node 实例化 `BBjP65vW.wasm`（stub imports）调用 `wasm_get_all_items()`，导出 **1663 条**真实物品记录（props 795 / acc 582 / ammo 119 / protect 98 / gun 69）；并列举 WASM 可复用接口 `wasm_get_item`、`wasm_get_effective_dimensions`、`wasm_calculate_item_price`、`wasm_load_price_book` 等。来源：[resource-analysis.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/resource-analysis.md)。
- 价格来自上游 `/api/prices`，返回二进制 DFPB 文件（magic `DFPB`，v6 / 2026-11-17），喂给 WASM 解码；Android 离线携带 v6 快照。来源：[price-source-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/price-source-report.md)。

---

## 2. 技术栈、项目结构、主要模块和入口文件

### 2.1 顶层结构（来源：[recursive tree API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/git/trees/main?recursive=1)）

| 路径 | 作用 |
|---|---|
| `index.html` | 根跳转页，meta refresh 到反编译查看器 |
| `browse反编译.html` | **反编译查看器**（单文件，内嵌 43 个模块源码） |
| `CNAME` | GitHub Pages 自定义域名 `lb.czyz.eu.cc` |
| `README.md` | 当前 README，主体是 DeltaInventory 说明（与 `DeltaInventory/README.md` 内容相同，SHA 均为 `3ce46983...`） |
| `playable/` | 可玩的理包模拟器前端镜像 + 本地服务器（`server.js`） |
| `DeltaInventory/` | Android 原生应用完整工程（源码、APK、分析、文档、工具） |

### 2.2 反编译查看器（前端，零构建）

- 纯静态 HTML+CSS+JS（无框架），侧边栏 43 个文件名按钮 + 搜索过滤、代码 pane 切换、上/下一个导航、语法高亮（手写 span class：`kw/str/com/num/fn/id/op`）、`mark` 搜索高亮。
- 入口文件：`browse反编译.html`（5.59 MB）。43 个模块即 `playable/assets/` 下的 chunk（如 `Y-x8muZd.js`、`BVTo7rNE.js`、`yzrtAEyr.js` 等）。
- `playable/assets/` 共约 110 个 `.js` + 1 个 CSS + 2 个 `.wasm`（`BBjP65vW.wasm`、`Se1zKGnR.wasm`），另有 `assembly_wasm_bg.js` / `deltaforce_repack_sim_core_bg.js` 等 glue（资源分析指出真正 glue 是 `yzrtAEyr.js`，另两个同名文件实为 HTML 副本、0 export）。来源：[resource-analysis.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/resource-analysis.md)。
- `playable/server.js`：Node 原生 http/https 服务器，`PORT` 默认 8095；静态资源本地托管（SPA 回退 index.html、静态长缓存、`.wasm` 设置 `Cross-Origin-Opener-Policy: same-origin`），`/api/*` 与 `/health` 反代到上游。来源：[server.js](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/server.js)。
- `playable/manifest.json`：PWA「理包模拟器」，竖屏 standalone。来源：[manifest.json](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/manifest.json)。

### 2.3 DeltaInventory（Android 应用）

技术栈（来源：[app/build.gradle.kts](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/app/build.gradle.kts)、[architecture.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/architecture.md)、[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)）：

- Kotlin + Jetpack Compose（BOM 2024.09.00、Material3、Navigation Compose 2.7.7）
- Room 2.6.1（9 张表）、Coroutines/Flow、KSP
- ONNX Runtime Android 1.18.0（本地推理，模型为未训练骨架）
- ML Kit Text Recognition 16.0.1（本地 OCR 读格数/数量角标）
- OpenCV JNI（本地 AAR 或 Maven，变化检测 / dHash）
- Rust 核心（JNI `libdi_core.so`，追踪/去重/价值/MaxRects 装箱）
- Moshi KTX（JSON）
- targetSdk 34 / minSdk 26 / Java 17 / CMake 3.22.1 / NDK arm64-v8a；Release 使用 `delta-release.jks` 签名。

目录与入口（来源：[recursive tree API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/git/trees/main?recursive=1)）：

| 路径 | 模块 |
|---|---|
| `DeltaInventory/app/src/main/AndroidManifest.xml` | 应用清单 |
| `.../java/com/deltainventory/app/MainActivity.kt` | **应用入口 Activity** |
| `.../app/capture/CaptureService.kt`、`CaptureBinder.kt` | MediaProjection 屏幕采集、前台服务、悬浮球、Binder 帧流 |
| `.../app/vision/VisionEngine.kt`、`GridOcr.kt`、`IconMatcher.kt`、`IconScanEngine.kt`、`RegionDetector.kt`、`StepHash.java` | 视觉层：帧变化检测、OCR、图标 dHash/直方图匹配、区域检测 |
| `.../app/domain/ScanProcessor.kt`、`ScanViewModel.kt`、`CoreBridge.kt`、`ClusterMerger.kt`、`Valuation.kt` | 领域层：处理流水线、状态机、JNI 桥、跨格合并、估值 |
| `.../app/data/Entities.kt`、`DatabaseProvider.kt` | Room 9 表与 DI |
| `.../app/ui/AppNav.kt`、`Screens.kt` | Compose 导航与 8 个页面 |
| `DeltaInventory/app/src/main/cpp/vision_jni.cpp` + `CMakeLists.txt` | C++/OpenCV JNI |
| `DeltaInventory/app/src/main/jniLibs/arm64-v8a/` | 预编译 `libdi_core.so`（481 KB）、`libopencv_java4.so`（20 MB） |
| `DeltaInventory/app/src/main/assets/` | `item-database.json`、`item-features.json`、`models/item_detector.onnx` |
| `DeltaInventory/rust-core/src/` | `lib.rs`、`jni.rs`、`model.rs`、`tracker.rs`、`packing.rs`、`valuation.rs` |
| `DeltaInventory/tools/` | `build_features.py`、`fetch-opencv.sh`、`build-rust-android.sh`、`import_assets.py`、`FEATURES_README.md` |
| `DeltaInventory/analysis/` | 物品/资产/价格分析中间产物（CSV/JSON/MD） |
| `DeltaInventory/docs/` | `architecture.md`、`database.md`、`test-report.md`、`performance-report.md`、`accuracy-report.md` |
| `DeltaInventory/*-v1.0.0-*.apk` | 已签名 APK（debug 78.8 MB / release 66.1 MB） |

### 2.4 构建与运行入口

- **反编译查看器**：直接打开 `browse反编译.html` 即可（或部署为 GitHub Pages：`lb.czyz.eu.cc`）。
- **playable 站点**：`node playable/server.js`，默认 `http://0.0.0.0:8095`，API 自动反代上游。来源：[server.js](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/server.js)。
- **Android APK**：README 第七节给出 `bash tools/fetch-opencv.sh` → `bash tools/build-rust-android.sh` → `gradle assembleRelease`（JDK17 + Android SDK34 + NDK26.3 + CMake3.22）。仓库已含 Gradle 8.9 wrapper 与预编译 so，最新提交声称「本地 SDK/NDK/CMake/OpenCV 环境可一键 assembleRelease」。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)、[commit c40e41f8](https://github.com/wangbin100608-eng/df-browse-decompiled/commit/c40e41f8f0661425c2212fce55bb1c88d5652f8e)。

---

## 3. 项目的目的、功能、显著特征

### 3.1 目的演进（按 commit 时间线）

来源：[commits API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/commits?per_page=100)。

1. `655ffada`（11-23）Add decompiled browse module viewer → 初始目的：反编译源码查看器。
2. `9521d208`（11-23）Add CNAME for lb.czyz.eu.cc → 部署到 Pages。
3. `210ce8f8`（11-24）Add playable site: full frontend mirror + API proxy → 加入可玩镜像。
4. `8d143087`（11-24）Add DeltaInventory Android app → 转向 Android 物资盘点应用。
5. `e57ccf02`~`c40e41f8`（11-25~11-28）持续迭代采集/视觉/追踪流水线、性能测试、修复识别算法不一致、补全构建与披露识别限制。

### 3.2 查看器功能

- 43 个反编译模块的离线浏览、文件名过滤、上/下一个切换、语法高亮，标注入口 `Y-x8muZd.js` 和移动端分支 `DFTulD4U.g()`。来源：[browse反编译.html](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/browse%E5%8F%8D%E7%BC%96%E8%AF%91.html)。

### 3.3 DeltaInventory 核心功能（三步流程）

来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)。

1. **点击「开始识别全部物资」**：授权 MediaProjection → 长边 640 的自适应分辨率采集（后台线程）→ OCR 读格数、边缘检测定位区域 → 图标特征匹配扫描背包/地面/保险箱 → 低置信度（0.35~0.55）进待确认队列。
2. **点击「结束并比对数据库」**：停止采集 → 跨格合并（按已知宽高）→ Room 查最新价格（来源/时间/过期判断）→ OCR 读数量角标 → 确认物资。
3. **给出完整摆放策略**：背包 + 保险箱分别 MaxRects 装箱，输出每件坐标、总价值、未放入物品、空余格数、可视化网格，写入 Room 持久化。

显著特征：

- **数据真实性**：1663 件物品名称/尺寸来自 WASM `wasm_get_all_items()` 真实提取，价格来自 DFPB v6 快照并标注时间，不猜测价格。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)、[price-source-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/price-source-report.md)。
- **全离线**：识别与统计在本地完成，不依赖云端 AI；Room 9 表离线优先，支持导入/备份/恢复。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)。
- **性能优化**：640 长边工作图（像素量降约 10×）、后台 HandlerThread、JNI 64×64 灰度差分变化检测、HashMap id + pHash 高 16 位分桶加速、Bitmap 对象池、背压丢弃、Room 索引。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)、[architecture.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/architecture.md)。
- **诚实披露局限**（见第 4 节）。

---

## 4. 值得注意的发现

### 4.1 许可证与版权

- **GitHub 元数据 `license: null`**，仓库**没有 LICENSE 文件**（tree API 无此路径）。来源：[仓库元数据](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled)。
- 根 README 第九节与 `analysis/resource-analysis.md`、`price-source-report.md` 均声明：游戏数据、图片、素材版权归腾讯公司及《三角洲行动》官方所有，本项目仅用于个人本地技术研究，不作为商业分发。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)、[resource-analysis.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/resource-analysis.md)、[price-source-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/price-source-report.md)。
- 构建配置中 Release 签名密钥 `delta-release.jks` 与口令（`DeltaInventory2026`）**明文入库**（`app/build.gradle.kts`：storeFile/storePassword/keyAlias/keyPassword）。来源：[app/build.gradle.kts](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/app/build.gradle.kts)。已签名 APK 也在仓库中（release 66.1 MB，README 给出 SHA256 `0dc85c017b...`）。

### 4.2 是否完整可运行

- **查看器/站点**：`browse反编译.html` 自包含；`playable/` 有全部静态资源与 server.js，Node 启动即可运行；上游 API 反代依赖外网 `dfc.lza11111.com` 可用。来源：[server.js](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/server.js)。
- **Android**：仓库含完整 Android Studio 工程、Gradle wrapper、Rust 源码、预编译 so、已签名 APK；最新提交声称已在本机 SDK/NDK/CMake/OpenCV 环境编译成功并跑通 21/21 单测。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)、[commit c40e41f8](https://github.com/wangbin100608-eng/df-browse-decompiled/commit/c40e41f8f0661425c2212fce55bb1c88d5652f8e)。
- **关键限制**：README 自述早期「无真机端到端验证」；`accuracy-report.md` 后续在 Redroid 11 x86_64 做了动态验证，结论是**真实游戏格子识别不可用**（详见 4.3）。`DeviceSmokeTest` 仍标注「当前无设备环境未执行」。来源：[README.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/README.md)、[accuracy-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/accuracy-report.md)。

### 4.3 最值得注意的技术发现：识别能力的真实边界

`DeltaInventory/docs/accuracy-report.md`（2026-11-28 补充）诚实披露：

- `item_detector.onnx` 是**未训练骨架**（untrained-stub-v1-20261124），权重确定性初始化，confidence 被压到 ~0.0067，**有效检测数 0、mAP 0.00**，仅用于验证 ONNX Runtime 链路。来源：[accuracy-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/accuracy-report.md)。
- 动态验证（Redroid 11 x86_64）：纯 CDN 图标原图 5/5 正确（全库 1663/1663 自匹配）；但**合成游戏格子 5/5 错配**、**官方 playable 真实渲染格子 4 个全部错配**（汉明距离 6~25）。结论：dHash 全局感知哈希在「纯图标 vs 游戏渲染格子（深色背景、光照、文字标签、数量角标）」之间存在**算法层面的域漂移**，中心裁剪与背景域建库均无效。来源：[accuracy-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/accuracy-report.md)。
- 物理上限：1663 件中仅 1400 个唯一外观，87 组共 350 件图标完全相同（最大组 16 件），dHash 无法区分同外观物品。来源：[accuracy-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/accuracy-report.md)。
- 修复记录：`7561808c` 提交修复了两个运行时缺陷——`parseLong` 无符号溢出（331/1663 条哈希加载失败，改为 `parseUnsignedLong`）以及生成端与运行时采样算法漂移（新增 `StepHash.java` 统一）。来源：[commit 7561808c](https://github.com/wangbin100608-eng/df-browse-decompiled/commit/7561808ccd4eeb1fa65d61793b2622dcc3a87e51)、[accuracy-report.md](https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/accuracy-report.md)。

### 4.4 其它

- `README.md` 与 `DeltaInventory/README.md` 内容完全一致（同一 blob SHA `3ce46983...`），根 README 是后加 DeltaInventory 时整体替换的。来源：[tree API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/git/trees/main?recursive=1)。
- Issues：`GET /issues?state=all` 返回空数组，无任何 issue/讨论记录。来源：[issues API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/issues?state=all&per_page=100)。
- 提交历史共 15 个提交，作者均为 `wangbin100608-eng`，其中 3 个为「验证自动推送 hook」的 chore 提交；所有提交 `verification.verified: false`（未签名）。来源：[commits API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/commits?per_page=100)。
- 仓库体积较大：元数据 `size: 137583`（约 134 MB），主要来自 5.59 MB 查看器 HTML、两个 APK（~145 MB）、20 MB OpenCV so 以及大量前端资源。来源：[仓库元数据](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled)、[tree API](https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/git/trees/main?recursive=1)。

---

## 5. 来源索引（主要一手 URL）

1. 仓库元数据：<https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled>
2. 完整文件树：<https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/git/trees/main?recursive=1>
3. 提交历史：<https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/commits?per_page=100>
4. Issues：<https://api.github.com/repos/wangbin100608-eng/df-browse-decompiled/issues?state=all&per_page=100>
5. 根 README：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/README.md>
6. 根跳转页：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/index.html>
7. 查看器：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/browse%E5%8F%8D%E7%BC%96%E8%AF%91.html>
8. CNAME：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/CNAME>
9. 入口 chunk：`https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/assets/Y-x8muZd.js`
10. i18n/逻辑 chunk：`https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/assets/BVTo7rNE.js`
11. Vite 入口：`https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/assets/index-DdGw8vbq.js`
12. 服务器：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/server.js>
13. PWA manifest：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/playable/manifest.json>
14. 架构文档：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/architecture.md>
15. 准确率报告：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/docs/accuracy-report.md>
16. 资源分析：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/resource-analysis.md>
17. 价格来源：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/analysis/price-source-report.md>
18. Gradle 配置：<https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/app/build.gradle.kts>
19. 关键提交 `c40e41f8`：<https://github.com/wangbin100608-eng/df-browse-decompiled/commit/c40e41f8f0661425c2212fce55bb1c88d5652f8e>
20. 关键提交 `7561808c`：<https://github.com/wangbin100608-eng/df-browse-decompiled/commit/7561808ccd4eeb1fa65d61793b2622dcc3a87e51>
