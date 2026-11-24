# 准确率评估报告

生成时间：2026-11-24

## 视觉检测模型

| 项目 | 值 |
|---|---|
| 模型 | `item_detector.onnx` (untrained-stub-v1-20261124) |
| 输入 | images float32 [1,3,640,640] RGB [-1,1] |
| 输出 | output float32 [1,100,6] `[x,y,w,h,conf,class]` |
| opset / ir | 13 / 8 |
| 权重 | 确定性初始化（非训练所得） |
| confidence | 被压至 ~0.0067（低于阈值 0.35） |
| **有效检测数** | **0** |
| **mAP** | **0.00（未训练）** |

**声明**：本项目没有标注训练集，因此没有训练出具备识别能力的模型。
当前模型仅用于验证 ONNX Runtime Android 推理链路（加载/预处理/推理/后处理）端到端可用。
**不宣称任何识别准确率**。任何声称的准确率都会是虚假的。

## 真实数据来源（不依赖模型准确率的部分）

| 能力 | 数据来源 | 可靠性 |
|---|---|---|
| 物品名称/尺寸/图标 | WASM `wasm_get_all_items()` 导出 1663 件真实数据 | 高（真实提取） |
| 跨帧追踪/去重 | Rust 17 项自动化测试验证 | 高（逻辑确定） |
| 感知哈希/变化检测 | OpenCV dHash + absdiff（确定性算法） | 高（无训练依赖） |
| 价格 | DFPB v6 快照（2026-11-17） | 中（带时间戳，可过期） |

## 测试数据

- 物品表：`analysis/item_table.json`（1663 条真实记录）
- 追踪测试：`rust-core/tests/core_tests.rs`（17 场景）
- Android 测试：`app/src/test/.../CoreLogicTest.kt`（9 场景）

## 改进路径

若有标注数据，用 `tools/train_detector.py`（离线 Python）训练 YOLO 风格模型，
保持相同输入输出格式替换 `assets/models/item_detector.onnx`，Android 端无需改动。
届时需重新评测 mAP@0.5 并更新本报告。
