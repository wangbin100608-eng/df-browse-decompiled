# Redroid x86_64 翻译桥 Bug 修复记录

## 问题回顾

在 Redroid 11 x86_64 容器里跑 DeltaInventory APK，屏幕采集触发 ML Kit OCR 时 App 崩溃：
1. 第一次：`libndk_translation.so` 的 `Arm64WriteToFpcr` 里 `CHECK failed: 524288 == 0` → SIGABRT
2. 只修第一处后又遇到：`Undefined instruction 0x5ea1b842`（SCVTF）→ `raise(SIGILL)` → SIGABRT

## 根因分析

### Bug 1：FPCR 断言过严（vaddr 0x16e2c5）

反汇编 `Arm64WriteToFpcr`：
```
16e2c5: and $0xfffffffffc3f60ff, %rcx     # 检查 guest FPCR 的"保留位"
16e2cc: jne 16e367                        # 有非法位 -> __android_log_assert
```
ML Kit tflite 写入的 FPCR 含 bit 19 (`0x80000`)，被判非法。但后续计算 mxcsr 的逻辑（16e2d2 起）根本不读这些位，断言属于过度严格。

### Bug 2：undefined 指令直接 raise(SIGILL)（vaddr 0x14739e)

```
147391: call __android_log_print           # "Undefined instruction 0x%08x"
147396: mov %r14d,(%rbx)
147399: mov $0x4,%edi                      # SIGILL
14739e: call raise                         # 崩溃点
1473a3: movb $0x1,0x9(%rbp)                # 标记后本可继续解释循环
1473a7: jmp 147aad
```
翻译器遇到未实现的 ARM64 指令（本例 `0x5ea1b842` = SCVTF 标量浮点转换）时直接 abort。实际上 raise 之后的代码会设置状态并跳回解释循环，指令本身可安全跳过（结果是该条指令无效，OCR 降级但进程存活）。

## 修复方法：二进制 patch `libndk_translation.so`

文件位于容器内 `/system/lib64/libndk_translation.so`。ELF 第二 LOAD 段 vaddr 与 file offset 差 `0x1000`。

```python
d = bytearray(open('libndk_translation.so','rb').read())

# Patch 1: FPCR 断言跳转 -> 6 个 NOP（vaddr 0x16e2cc, file 0x16e2c7）
d[0x16e2cc-0x1000 : 0x16e2cc-0x1000+6] = b'\x90'*6

# Patch 2: raise(SIGILL) 调用 -> 5 个 NOP（vaddr 0x14739e, file 0x14639e）
d[0x14739e-0x1000 : 0x14739e-0x1000+5] = b'\x90'*5
```

成品文件：`/root/盘点/libndk_translation.so`，SHA256 `b6cb92c2ae918ba94fddd1b45eb4e6cf3fdfed5225911442a00b45894d52348c`。原始备份 `libndk_translation.so.orig`（SHA256 `09ed4a60…`）。

## 容器部署（关键：binder 节点 + bind mount）

1. **修复 binder**：宿主机有个陈旧 `/dev/binder`（237,0，指向不存在设备），被 privileged 容器继承导致系统服务全部 `Binder driver could not be opened` 崩溃。删除它：
   ```bash
   rm -f /dev/binder
   ```
   容器内部 `/dev/binder` 会正确指向 binderfs 里的节点。

2. **用 bind mount 注入 patch 库**（只读挂载，容器重建时自动生效）：
   ```bash
   docker run -itd --rm --privileged --name redroid11 \
     -v /root/redroid-data11:/data \
     -v /root/盘点/libndk_translation.so:/system/lib64/libndk_translation.so:ro \
     -p 5555:5555 \
     redroid/redroid:11.0.0-latest \
     androidboot.redroid_width=1080 androidboot.redroid_height=1920 \
     androidboot.redroid_dpi=480 androidboot.redroid_gpu_mode=auto
   ```

3. 等待 `sys.boot_completed=1`。

## 验证结果

重装原版 release APK（`DeltaInventory-v1.0.0-release.apk`，官方签名），授予悬浮窗权限，走完整三步流程：

| 阶段 | 结果 |
|---|---|
| 启动采集 → MediaProjection 授权 | 正常 |
| CaptureService 前台服务 | 正常，`work=360x640` |
| ML Kit OCR 模型加载 | 全部成功（不再 SIGABRT） |
| SCVTF undefined 指令 | 仅打印日志 `Undefined instruction 0x5ea1b842`，**进程存活** |
| 步骤1 识别中 | UI 显示"识别中"，悬浮球出现 |
| 步骤2 结束并比对 | UI 显示"已比对" |
| 步骤3 生成摆放策略 | UI 显示"策略已生成"，`CoreBridge: native di_core loaded` |
| 全程崩溃数 | **0** |

截图：`di3-running.png`（识别中）、`di3-step2.png`（已比对）、`di3-step3.png`（策略已生成）。

## 副作用与限制

- SCVTF 指令被跳过后，ML Kit OCR 在降级模式运行：本测试中采集的是 App 自己的 UI 而非游戏画面，本来也读不到背包格，回退默认 3×3、识别 0 件物品——这与 patch 无关，是测试场景问题。
- 未识别到物品时装箱结果为空。要验证真实识别准确率，需要对着《三角洲行动》游戏画面采集；但根据仓库 accuracy-report，即便在原生环境，dHash 对真实游戏渲染格也存在算法级域漂移。
- Patch 2（跳过所有 undefined 指令）是"让进程活下来"的兜底，理论上翻译器遇到任何未实现指令都会静默跳过，可能导致其他 arm64 应用行为异常。本场景只用于让 DeltaInventory 的 OCR 链路跑通。
- 若需要真正正确的 OCR 数值结果，仍需原生 ARM64 环境，或重新构建含 x86_64 原生库的 APK（ML Kit 官方 AAR 有 x86_64 so，但 App 自身的 `libdi_core.so`/`libdi_vision.so`/`libopencv_java4.so` 只有 arm64，一个进程只能有一个 primary ABI，混搭不可行）。

## 回滚

```bash
# 恢复原始 libndk_translation.so
cp /root/盘点/libndk_translation.so.orig /root/盘点/libndk_translation.so
# 重建容器即可（bind mount 会挂载恢复后的文件）
```

## 复现命令速查

```bash
D="adb -s 127.0.0.1:5555"
$D uninstall com.deltainventory.app
$D install -r -d /root/盘点/DeltaInventory-v1.0.0-release.apk
$D shell appops set com.deltainventory.app SYSTEM_ALERT_WINDOW allow
$D shell logcat -c
$D shell am start -n com.deltainventory.app/.MainActivity
$D shell input tap 325 1660   # 盘点页
$D shell input tap 540 690    # 开始识别 -> MediaProjection 弹窗
$D shell input tap 810 1300   # START NOW
$D shell input tap 540 1080   # 结束并比对
$D shell input tap 540 1325   # 生成摆放策略
```
