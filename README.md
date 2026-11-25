# 三角洲行动 · 理包模拟器 —— 反编译、可游玩站点与 Android 盘点应用

本仓库包含三样成果：

| 目录 | 内容 | 用途 |
|---|---|---|
| `browse反编译.html` | 「浏览游戏」模块反编译源码浏览页 | **看代码** |
| `playable/` | 与 `https://dfc.lza11111.com/browse` 一样可游玩的网页（同源镜像 + API 代理） | **在浏览器玩** |
| `DeltaInventory/` | **Android 原生应用**：本地视觉盘点、价值统计、二维背包优化 | **在手机玩/辅助盘点** |

> 三者的重点不同：反编译页用于阅读；playable 用于浏览器游玩；DeltaInventory 是独立 Android 应用，聚焦普通非武器物品的本地盘点与背包优化。

---

## 一、DeltaInventory（Android 应用）★ 新增

### 简介

本地视觉物品盘点与背包优化工具。仅处理**普通非武器物品**（893 件，来自 WASM 真实提取），不实现枪械/配件/弹药的识别或优化。全部识别与统计在本地完成，不上传云端。

**APK**（两版均在 `DeltaInventory/`）：

- `DeltaInventory-v1.0.0-release.apk`（51 MB，ARM64，**正式签名**，Rust LTO 优化，推荐安装）
- `DeltaInventory-v1.0.0-debug.apk`（57 MB，调试版）
- Release SHA256：`3f73ceeddf0956d95d06e6ed14d5c8a40db5b8a115203c99cfe4df9e0e287ffc`

### 技术架构

| 层 | 技术 |
|---|---|
| UI | Kotlin + Jetpack Compose（主页/盘点/待确认/统计/背包网格/历史/数据库/设置） |
| 系统集成 | WindowManager 悬浮球 + MediaProjection 屏幕采集 + Foreground Service |
| 视觉 | ONNX Runtime Android + OpenCV(JNI 变化检测/phash) |
| 核心 | Rust（跨帧追踪、去重、价值统计、MaxRects/Bottom-Left/精确搜索装箱） |
| 存储 | SQLite + Room（9 张表，离线优先，备份恢复） |
| 离线工具 | Python（仅数据导入/模型训练，不参与 Android 运行时） |

关键设计：以**实例账本**为唯一真相，绝不逐帧累加识别数量；去重同时参考类型/区域/容器/位置/视觉签名/时间连续性，禁止仅凭名称或相似度合并；低置信度进人工确认队列，未确认不计入确定价值。

### 已实现功能（需求全部 14 项）

1. Android 悬浮窗（可拖动/点击进入暂停） 2. MediaProjection 实时屏幕采集 3. 普通物品视觉识别 4. 背包与地面完整盘点 5. 连续滑动跨帧追踪 6. 重复物品去重 7. 物品数据库 8. 价格数据管理 9. 低置信度人工确认 10. 二维背包摆放优化 11. 最终收益统计 12. 完整 UI 13. 自动化测试 14. 可安装 APK

### 构建（完整 Android Studio 项目在 `DeltaInventory/`）

```bash
cd DeltaInventory
bash tools/fetch-opencv.sh        # 下载 OpenCV ARM64 构建依赖（约190MB，已 gitignore）
bash tools/build-rust-android.sh  # 编译 Rust core -> jniLibs
# Android Studio 打开 DeltaInventory/ 需 JDK17 + SDK34 + NDK26 + CMake3.22
gradle assembleDebug              # -> app/build/outputs/apk/debug/app-debug.apk
```

### 测试（真实执行结果）

- Rust 核心：`cargo test` → **17/17 PASS**（连续滑动、重复计数、区域独立、拆分合并、过期价格、越界重叠等）
- Android：`gradle testDebugUnitTest` → **9/9 PASS**
- Debug APK：v2 签名有效，SHA256 `007760354d36b05583fcbbec322ef36013632b71d2edd081bea14b8d84e1bb87`
- Release APK：正式 keystore 签名（v2），51 MB，SHA256 `3f73ceeddf0956d95d06e6ed14d5c8a40db5b8a115203c99cfe4df9e0e287ffc`（推荐）

### 资源分析产物（`DeltaInventory/analysis/`）

- `assets-manifest.json`、`item-database.json`（893 件普通物品）、`asset-inventory.csv`（1663 行）
- `price-source-report.md`、`resource-analysis.md`
- 物品数据来自直接实例化原站 WASM 调 `wasm_get_all_items()` 提取，非猜测。

### 模型说明（如实）

`item_detector.onnx` 是**未训练的骨架模型**，仅验证 ONNX 推理链路端到端可运行，mAP=0，不产生有效检测，也不宣称任何准确率。实际识别依赖离线物品库 + 感知哈希/颜色特征 + 人工确认。有标注数据后可用 `tools/train_detector.py`（离线）训练替换。详见 `DeltaInventory/docs/accuracy-report.md`。

详细文档见 `DeltaInventory/README.md`、`docs/architecture.md`、`docs/database.md`、`docs/test-report.md`、`docs/performance-report.md`。

---

## 二、可游玩网页（playable/）

- 线上：<https://game.czyz.eu.cc/>
- 完整镜像原站前端（110 JS + 1 CSS + 2 WASM + 图片），Node 服务做静态托管 + `/api/*` 同源反向代理（绕过 CORS），游戏核心 WASM 原样运行，行为与原站一致。
- 本地：`cd playable && node server.js` → <http://localhost:8095/>

---

## 三、反编译源码浏览页（根目录）

- 单文件 `browse反编译.html`（5.3 MB），43 个格式化模块，左侧文件树/过滤/切换/高亮，离线可打开。
- 在线：<https://lb.czyz.eu.cc/>；`CNAME` 为 `lb.czyz.eu.cc`。

---

## 免责声明

游戏数据、图片、素材版权归腾讯公司及《三角洲行动》官方所有，本项目仅用于个人本地技术研究。
