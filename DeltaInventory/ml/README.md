# DeltaInventory ML：训练与部署说明

## 目录结构

```
DeltaInventory/ml/
├── dataset/                    # 数据集（构建器生成）
│   ├── manifest.json           # 98 条标注清单（含 split/关联 item_id/质量标记）
│   ├── classes.json            # 16 类定义（关联 1663 库）
│   ├── dataset_report.json     # 划分/泄漏/平衡/低置信度检查
│   ├── images/{train,val,test}/# 原始 2560x1600 帧
│   ├── clf/{train,val,test}/   # 分类器裁剪图 64x64
│   ├── annotations/yolo/       # 每帧 yolo 标签
│   └── yolo/                   # YOLO 训练用（images/labels + dataset.yaml）
├── scripts/
│   ├── common.py               # 版本/日志/断点/指标（自实现，无 sklearn 依赖）
│   ├── build_dataset.py        # 数据集构建：关联库/按帧划分/泄漏检查/裁剪
│   ├── train_classifier.py     # 模型 A：ResNet18 迁移学习
│   └── train_detector.py       # 模型 B：YOLOv8n 迁移学习
├── experiments/
│   ├── classifier_resnet18/    # train.log / checkpoint.pt / best_model.pt / evaluation.json / failures/
│   └── detector_yolov8n/       # 同上 + yolov8n/weights/best.onnx
├── models/
│   └── detector_yolov8n.onnx   # 导出模型（部署到 assets）
├── reports/
│   ├── evaluation-report.md    # 完整评估报告
│   └── figures/                # 混淆矩阵
└── README.md                   # 本文件
```

## 环境依赖

```bash
pip install torch torchvision onnx ultralytics pillow opencv-python-headless onnxscript
```

## 一、构建数据集

```bash
python3 DeltaInventory/ml/scripts/build_dataset.py
```

- 读取 `research/train/real_labels.jsonl`（多模态框选的真实标注）
- 与 `item-database.json`（1663 件）按名称关联，写入 item_id
- 按帧分层划分 train/val/test（帧不跨集合，杜绝泄漏）
- 生成分类裁剪图与 yolo 标签
- 输出 `dataset/dataset_report.json`

## 二、训练模型 A（分类器）

```bash
python3 DeltaInventory/ml/scripts/train_classifier.py --epochs 20
# 断点续训：
python3 DeltaInventory/ml/scripts/train_classifier.py --epochs 30 --resume
```

- 迁移学习（ImageNet 预训练 ResNet18）
- 自动保存 `train.log`、`checkpoint.pt`（断点）、`best_model.pt`（最佳 val）、`evaluation.json`
- 记录数据集版本 + 模型版本
- 失败案例图片保存到 `failures/`

## 三、训练模型 B（检测器）

```bash
python3 DeltaInventory/ml/scripts/train_detector.py
```

- YOLOv8n COCO 预训练权重迁移学习
- 40 epoch，best.pt，测试集 mAP 评估 + 失败案例可视化
- 导出 ONNX 见下

## 四、导出 ONNX

```bash
python3 -c "from ultralytics import YOLO; YOLO('experiments/detector_yolov8n/yolov8n/weights/best.pt').export(format='onnx',imgsz=640,opset=13,simplify=True)"
```

输出 `[1,20,8400]`，输入 `[1,3,640,640]` RGB [0,1]，与 Android `YoloDetector.kt` 契约一致。

## 五、Android 集成

1. 复制 ONNX 到 `DeltaInventory/app/src/main/assets/models/detector_yolov8n.onnx`
2. `yolo_labels.json`（class_id → item_id/name）与 `detector_version.txt` 一并放入 assets
3. `YoloDetector.kt`：ONNX Runtime Mobile 推理 + NMS（阈值 CONF=0.25，NMS=0.45）
4. `ScanProcessor.kt`：YOLO 优先，dHash 兜底；YOLO 模式绕过 OCR 切格；低置信度入待确认
5. 编译：

```bash
cd DeltaInventory && ./gradlew assembleRelease --offline
# 产物 app/build/outputs/apk/release/app-release.apk（74 MB）
```

## 六、验证

- 安装 APK，授予悬浮窗，进"盘点页 → 开始识别全部物资"，授权 MediaProjection
- 播放游戏录屏，观察 logcat：`YoloDetector`、`ScanProcessor: YOLO 检测`、`比对完成`
- 结束比对后查看"已确认物资"与数据库

## 七、增量迭代

新增样本 → 补充 `research/train/real_labels.jsonl`（帧名/物资名/x0,y0,x1,y1/source/timestamp_s）→ 重跑 `build_dataset.py` → 重训 → 替换 assets 模型 → 重编译。数据集版本由 manifest 的 SHA256 自动记录。

## 八、已知限制

1. 检测训练仅 6 帧、测试 4 帧，mAP50 0.51 / Recall 0.34 仅供参考
2. 普通物品 CDN 参考图片本体未本地持有（第一阶段缺口 G1）
3. x86_64 翻译桥下 ONNX 推理可运行但非原生速度，部分 arm64 指令被跳过
4. 北极星等 5 个 item_id 在 1663 库中无条目，无法关联价格
