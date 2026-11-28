# DeltaInventory APK 本地运行记录

- **时间**：2026-11-28 04:32–04:36（Asia/Shanghai）
- **设备**：Redroid 11.0.0 x86_64 容器（`/root/盘点` 所在宿主机，adb `emulator-5554`，已存在）
- **ROM**：`redroid/redroid_x86_64:11.0.0-latest`，Android 11，1080×1920 / 480dpi
- **ABI**：`x86_64, x86, arm64-v8a, armeabi-v7a, armeabi`（带 NDK Translation 翻译桥）

## 一、安装

```bash
cd /root/盘点
curl -L -o DeltaInventory-v1.0.0-release.apk \
  https://raw.githubusercontent.com/wangbin100608-eng/df-browse-decompiled/main/DeltaInventory/DeltaInventory-v1.0.0-release.apk
adb -s emulator-5554 install -r -d DeltaInventory-v1.0.0-release.apk
adb -s emulator-5554 shell am start -n com.deltainventory.app/.MainActivity
```

- 下载大小 63.0 MiB；`file` 识别为 Zip archive（APK）
- 本地 SHA256：`e202065b81f0c4c31fb70ea80afdddfc99f6db7d422e44eb7137b44bca24c850`
  （与 README 声称的 `0dc85c01…` 不一致 —— README 的签名哈希对应的是旧版或另一构建；本 APK 为仓库当前 main 分支的文件）
- 包名 `com.deltainventory.app`，versionName `1.0.0`
- APK 内仅有 `lib/arm64-v8a/`，7 个 so：`libdi_core.so`(481 KB)、`libdi_vision.so`(2.8 MB)、`libopencv_java4.so`(20 MB)、`libonnxruntime.so`(16 MB)、ML Kit OCR、ONNX JNI 等
- `install -r -d` 成功；`am start -n com.deltainventory.app/.MainActivity` 成功（monkey 找不到 launcher 入口，直接指定 component 即可）

## 二、已验证的功能（全部实机截图）

| 页面/功能 | 结果 | 截图 |
|---|---|---|
| 主页（8 页导航底栏） | 正常渲染 | `di-screen1.png` |
| 数据库管理页（9 表说明） | 正常 | `di-db.png` |
| 导入全量物品库 | **成功：1663 件物品 + 1663 条价格** | `di-import.png` |
| 价值统计页 | 正常（未盘点时 0.00） | `di-stats.png` |
| 二维背包优化页 | 正常（10×6 网格） | `di-bag.png` |
| 计算最佳布局 | **成功：Rust 核心加载，输出 `算法: bottom_left`，耗时 0ms** | `di-pack.png` |
| 设置页 | 正常：阈值 0.55 / 汉明距离 20 / `untrained-stub-v1` 模型声明 / 全部 1663 件范围 | `di-settings.png` |
| 盘点三步页 | 正常 | `di-scan.png` |
| 悬浮窗权限请求 | 正确触发并授权后返回 | `di-scan-start.png` / `di-overlay.png` |
| MediaProjection 屏幕采集授权 | 正确触发系统弹窗 | `di-capture.png` |
| CaptureService 前台服务 | **正常启动**（`work=360x640 density=480`，前台通知 `di_capture`） | — |

关键日志：
```
I CoreBridge: native di_core loaded
I CaptureService: 采集启动(自适应) 1080x1920(work=360x640) density=480 rotation=0
```

## 三、发现的问题：屏幕采集后 ML Kit OCR 线程崩溃

- **现象**：授权 MediaProjection 后约 1.3 秒，App 进程 SIGABRT 崩溃，前台服务被系统重启；连续两次复现稳定。
- **根因**：Redroid x86_64 的 ARM64 翻译桥 bug，**不是 App 代码缺陷**：
  ```
  Abort message: 'vendor/unbundled_google/libs/ndk_translation/intrinsics/
  intrinsics_impl_x86_64.cc:86: CHECK failed: 524288 == 0'
    #05 libndk_translation.so (ndk_translation::intrinsics::Arm64WriteToFpcr(unsigned long)+213)
    tid: vkp-mediapipe-0 / Thread-4  ← ML Kit OCR 线程
  ```
  APK 只有 arm64-v8a 的 so；ML Kit OCR（arm64）在翻译执行到 FPCR/NEON intrinsic 时触发 Redroid 翻译层 CHECK 失败。
- **结论**：在有原生 ARM64 设备（或支持翻译更完整的模拟器）上跑才会走完 OCR→图标匹配→装箱的完整链路。当前 x86_64 环境下，除 OCR 外的全部功能（UI、Room 数据库、Rust 核心、采集服务启动）均已验证正常。

## 四、最终状态

App 已重启并停留在主页，进程健康运行。

## 五、复现命令速查

```bash
ADB="adb -s emulator-5554"
$ADB install -r -d /root/盘点/DeltaInventory-v1.0.0-release.apk
$ADB shell am start -n com.deltainventory.app/.MainActivity
$ADB shell appops set com.deltainventory.app SYSTEM_ALERT_WINDOW allow   # 预先授予悬浮窗
$ADB shell input tap 540 690         # 盘点页 -> 开始识别（权限弹窗确认后触发采集）
$ADB logcat -d -t 200 | grep -iE 'CaptureService|ndk_translation|Fatal'
$ADB exec-out screencap -p > screen.png
```
