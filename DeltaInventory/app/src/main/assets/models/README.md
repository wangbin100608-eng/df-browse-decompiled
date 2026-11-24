# 视觉识别模型说明

## 当前模型：item_detector.onnx（untrained-stub-v1）

- **状态：未训练的骨架模型，不具备识别准确率。**
- 用途：验证 ONNX Runtime Android 推理链路（输入/输出/预处理/后处理）端到端可运行。
- 输入：`images` float32 `[1,3,640,640]`，RGB，归一化到 [-1,1]
- 输出：`output` float32 `[1,100,6]`，每行 `[x,y,w,h,confidence,class_id]`（归一化坐标）
- opset 13，ir_version 8
- 权重为确定性初始化，confidence 被压到极低（约 0.0067），低于阈值 0.35，**不会产生任何有效检测**。

## 重要说明

本项目没有可用的标注训练集，因此**不提供、也不宣称任何高准确率模型**。
实际物品识别依赖：
1. 物品数据库（1663 件真实物品的 name/size/prePic）；
2. 感知哈希 + 颜色特征的传统视觉匹配（用于跨帧追踪和变化检测）；
3. 低置信度全部进入人工确认队列。

后续若有标注数据，可用 `tools/train_detector.py`（离线，Python）训练并用相同输入/输出格式替换本文件，
Android 端无需改动。

## 准确率评估报告

见 `docs/accuracy-report.md`：当前模型在测试数据上 mAP=0（未训练），链路可用性 100%。
