"""在独立测试集上运行已训练模型，记录错误匹配（只读推理，不改模型）。"""
import json, os, sys, time
import numpy as np
from PIL import Image
import torch
from torchvision import transforms
sys.path.insert(0,'DeltaInventory/ml/scripts')
from common import classification_metrics, confusion

ROOT='DeltaInventory/ml'
D=ROOT+'/testset_independent'
classes=json.load(open(ROOT+'/dataset/classes.json',encoding='utf-8'))
name2id={c['name']:c['class_id'] for c in classes}
id2name={c['class_id']:c['name'] for c in classes}
rows=[json.loads(l) for l in open(D+'/annotations.jsonl',encoding='utf-8')]
appr=[r for r in rows if r['human_review']=='approved']

# 分类器 A
from torchvision import models
net=models.resnet18(weights=None); net.fc=torch.nn.Linear(net.fc.in_features,len(classes))
net.load_state_dict(torch.load(ROOT+'/experiments/classifier_resnet18/best_model.pt',map_location='cpu'));net.eval()
tf=transforms.Compose([transforms.Resize((64,64)),transforms.ToTensor(),
    transforms.Normalize([0.485,0.456,0.406],[0.229,0.224,0.225])])
yt=[];yp=[];errs=[]
t0=time.perf_counter()
for r in appr:
    im=Image.open(D+'/frames/%s.png'%r['frame']).convert('RGB')
    crop=im.crop((r['x0'],r['y0'],r['x1'],r['y1']))
    bg=Image.new('RGB',(64,64),(24,28,36)); c=crop.copy();c.thumbnail((64,64),Image.LANCZOS)
    bg.paste(c,((64-c.width)//2,(64-c.height)//2))
    with torch.no_grad(): out=net(tf(bg)[None])[0]
    pred=int(out.argmax()); gt=name2id[r['name']]
    yt.append(r['name']); yp.append(id2name[pred])
    if pred!=gt: errs.append({'frame':r['frame'],'ts':r['timestamp_s'],'source':r['source_video'],
        'gt':r['name'],'pred':id2name[pred],'conf':float(out.softmax(0)[pred]),'bbox':[r['x0'],r['y0'],r['x1'],r['y1']]})
infer=(time.perf_counter()-t0)/len(appr)*1000
met=classification_metrics(yt,yp,[c['name'] for c in classes])
res={'model':'clf-resnet18-v1','testset':'independent-timepoints','samples':len(appr),
     'accuracy':met['accuracy'],'macroP':met['macro_precision'],'macroR':met['macro_recall'],
     'macroF1':met['macro_f1'],'avg_infer_ms':infer,
     'per_class':met['per_class'],'errors':errs,
     'rejected_annotations':[r for r in rows if r['human_review']=='rejected']}
json.dump(res,open(D+'/classification_results.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
print('独立测试集分类: n=%d acc=%.3f macroF1=%.3f infer=%.2fms'%(len(appr),met['accuracy'],met['macro_f1'],infer))
print('错误匹配 %d 条:'%len(errs))
for e in errs: print('  %s t=%.1f gt=%-16s pred=%-16s conf=%.2f'%(e['frame'],e['ts'],e['gt'][:15],e['pred'][:15],e['conf']))
