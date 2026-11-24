# 测试报告

生成时间：2026-11-24

## Rust 核心测试（host，cargo test）

**结果：17/17 全部通过**

| 测试 | 覆盖场景 | 结果 |
|---|---|---|
| `test_multiple_same_items_different_positions_kept_separate` | 多个相同物品 | PASS |
| `test_same_item_repeated_frames_no_double_count` | 连续滑动重复计数 | PASS |
| `test_scroll_up_down_content_coordinates` | 连续上/下滑动（内容坐标） | PASS |
| `test_fast_scroll_partial_overlap` | 快速滑动/部分重叠 | PASS |
| `test_different_regions_independent` | 不同区域独立坐标系 | PASS |
| `test_quantity_change` | 物品数量变化 | PASS |
| `test_temporarily_hidden` | 物品暂时不可见 | PASS |
| `test_low_confidence_goes_pending` | 低置信度识别 | PASS |
| `test_human_confirm_and_split_merge` | 人工确认/拆分/合并 | PASS |
| `test_visual_signature_conflict_not_merged` | 视觉证据冲突不合并 | PASS |
| `test_valuation_pending_not_counted` | 待确认不计入价值 | PASS |
| `test_valuation_stale_and_missing` | 价格过期/缺失 | PASS |
| `test_bag_ground_separate` | 背包与地面分别统计 | PASS |
| `test_packing_no_overlap_no_out_of_bounds` | 网格冲突/越界 | PASS |
| `test_packing_rotation_and_unplaced` | 旋转约束/未放入物品 | PASS |
| `test_exact_search_matches_maxrects_on_small_case` | 优化结果正确性 | PASS |
| `test_yuan_to_cents` | 金额精度 | PASS |

## Android 单元测试（gradle testDebugUnitTest）

**结果：9/9 全部通过**（`tests="9" skipped="0" failures="0" errors="0"`）

测试文件：`app/src/test/java/com/deltainventory/app/CoreLogicTest.kt`，覆盖：多实例、重复帧不重复计数、滚动内容坐标、区域独立、低置信度待确认、视觉冲突不合并、数量变化、拆分合并、装箱面积守恒。

执行环境：Vultr 4 核 AMD EPYC / 15GB RAM，Gradle 8.7 + AGP 8.5.2，耗时 26 秒。
（配置 `testOptions.unitTests.isReturnDefaultValues=true` 使 android.util.Log 在 JVM 测试中可运行。）

## 准确率评估

### 视觉检测模型

| 模型 | 状态 | mAP | 说明 |
|---|---|---|---|
| `item_detector.onnx` v1 | **未训练骨架** | **0.00** | 权重确定性初始化，confidence 压至 ~0.0067，低于阈值 0.35，不产生有效检测 |

**声明**：本项目没有标注训练集，因此不提供高准确率模型，也不宣称任何准确率数字。
推理链路（ONNX Runtime Android 加载 → 预处理 → 推理 → 后处理）100% 可运行，已通过编译验证。
实际物品识别依赖离线物品数据库 + 传统视觉特征（感知哈希/颜色直方图）+ 人工确认机制。

### 追踪与去重

基于 Rust 17 项测试：重复帧不重复计数、同位置不同类型不误合并、视觉冲突不合并——均由自动化测试验证。

## 捕获中断场景

- 系统停止：`MediaProjection.Callback.onStop` → `stopCapture("system_stop")` → 广播 + 清理资源。
- 用户撤销授权：同上路径。
- 应用后台：Foreground Service 持续运行，前台通知保活。
- 内存不足：`frameFlow` 有界丢弃 + Image.close() 及时释放。
- 配置变化（旋转）：`handleConfigurationChanged()` 重建 VirtualDisplay。
- 异常结束：`onDestroy` → `stopCapture("destroy")` + CoroutineScope.cancel()。
