"""模型 B：YOLOv8n 检测器（迁移学习）。
- 迁移学习：yolov8n 预训练权重
- 日志、最佳模型、断点(yolo默认last.pt)、版本
- 输出 mAP50 / mAP50-95 / Precision / Recall / 每类 / 推理耗时 / 失败案例
"""
import os, json, sys, time, shutil
sys.path.insert(0,os.path.dirname(__file__))
from common import Logger, dataset_version
from ultralytics import YOLO

ROOT='/root/工作区/df-browse-decompiled/DeltaInventory/ml'
DS=ROOT+'/dataset'; EXP=ROOT+'/experiments/detector_yolov8n'
os.makedirs(EXP,exist_ok=True)
log=Logger(EXP+'/train.log'); dv=dataset_version(DS)
classes=[c['name'] for c in json.load(open(DS+'/classes.json',encoding='utf-8'))]
log.log(f'=== 检测模型 B YOLOv8n | dataset={dv} classes={len(classes)} ===')
log.log('注意: 标注帧仅11张(train6/val1/test4)，样本严重不足，mAP预期偏低')

model=YOLO('yolov8n.pt')
# 训练：迁入 ml/experiments 目录
t0=time.time()
results=model.train(data=DS+'/yolo/dataset.yaml', epochs=40, imgsz=640,
                    batch=4, name='yolov8n', project=EXP, exist_ok=True,
                    patience=15, save=True, verbose=False)
log.log(f'训练完成 耗时 {time.time()-t0:.1f}s')

# 在 test 上评估（val=True 用 test split）
best=EXP+'/yolov8n/weights/best.pt'
model=YOLO(best)
metrics=model.val(data=DS+'/yolo/dataset.yaml', split='test', imgsz=640, verbose=False)
# 推理耗时（test 帧）
imgs=[f for f in os.listdir(DS+'/yolo/images/test') if f.endswith('.png')]
lat=[]
for f in imgs:
    t=time.time(); model.predict(DS+'/yolo/images/test/'+f,imgsz=640,verbose=False); lat.append((time.time()-t)*1000)
box=metrics.box
res={
 'model_version':'det-yolov8n-v1','dataset_version':dv,'classes':classes,
 'precision':float(box.mp),'recall':float(box.mr),
 'mAP50':float(box.map50),'mAP50_95':float(box.map),
 'per_class':{},
 'avg_infer_ms':float(sum(lat)/len(lat)),'p95_infer_ms':float(sorted(lat)[int(len(lat)*0.95)-1] if len(lat)>1 else lat[0]),
 'train_frames':6,'val_frames':1,'test_frames':len(imgs),
 'limitation':'仅11张标注帧，样本严重不足，指标仅供参考，不足以部署'
}
# 每类 AP
try:
    for i,n in enumerate(classes):
        res['per_class'][n]={'ap50':float(box.ap50[i]),'ap50_95':float(box.ap[i])}
except Exception as e: log.log(f'per-class AP读取失败:{e}')
json.dump(res,open(EXP+'/evaluation.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
log.log(f'TEST P={res["precision"]:.3f} R={res["recall"]:.3f} mAP50={res["mAP50"]:.3f} mAP50-95={res["mAP50_95"]:.3f} infer={res["avg_infer_ms"]:.0f}ms')
# 失败案例：test 预测可视化
fdir=EXP+'/failures'; os.makedirs(fdir,exist_ok=True)
for f in imgs:
    r=model.predict(DS+'/yolo/images/test/'+f,imgsz=640,verbose=False)[0]
    r.save(filename=fdir+'/'+f.replace('.png','_pred.jpg'))
log.log('评估报告与失败案例已保存')
log.close()
