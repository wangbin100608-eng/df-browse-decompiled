"""模型 A：图像分类器（ResNet18 迁移学习）。
- 迁移学习（ImageNet 预训练）
- 日志自动保存、最佳验证模型、断点续训
- 记录模型版本 + 数据集版本
- 输出：分类准确率、Precision/Recall/F1、混淆矩阵、各类别、推理耗时、失败案例
"""
import os, json, time, argparse, sys
import numpy as np
from PIL import Image
import torch, torch.nn as nn, torch.optim as optim
from torch.utils.data import Dataset, DataLoader
from torchvision import models, transforms
sys.path.insert(0,os.path.dirname(__file__))
from common import Logger, dataset_version, save_checkpoint, load_checkpoint, classification_metrics, confusion

ROOT='/root/工作区/df-browse-decompiled/DeltaInventory/ml'
DS=ROOT+'/dataset'

class ClfDataset(Dataset):
    def __init__(self,split,classes):
        self.manifest=[m for m in json.load(open(DS+'/manifest.json',encoding='utf-8')) if m['split']==split]
        self.c2i={c['name']:c['class_id'] for c in classes}
        self.tf=transforms.Compose([
            transforms.Resize((64,64)),
            transforms.ToTensor(),
            transforms.Normalize([0.485,0.456,0.406],[0.229,0.224,0.225])])
    def __len__(self): return len(self.manifest)
    def __getitem__(self,i):
        m=self.manifest[i]
        im=Image.open(DS+'/'+m['clf_path']).convert('RGB')
        return self.tf(im), self.c2i[m['name']], i

def build_model(n,pretrained=True):
    m=models.resnet18(weights=models.ResNet18_Weights.DEFAULT if pretrained else None)
    m.fc=nn.Linear(m.fc.in_features,n)
    return m

def evaluate(model,loader,classes,device):
    model.eval(); yt=[]; yp=[]; lat=[]
    with torch.no_grad():
        for x,y,_ in loader:
            t0=time.perf_counter(); out=model(x.to(device)); 
            if device.type=='cuda': torch.cuda.synchronize()
            lat.append((time.perf_counter()-t0)/len(x)*1000)
            yp.extend(out.argmax(1).cpu().numpy().tolist()); yt.extend(y.numpy().tolist())
    names=[c['name'] for c in classes]
    ytn=[names[i] for i in yt]; ypn=[names[i] for i in yp]
    met=classification_metrics(ytn,ypn,names)
    met['avg_infer_ms']=float(np.mean(lat)); met['p95_infer_ms']=float(np.percentile(lat,95))
    return met, confusion(ytn,ypn,names), ytn, ypn

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--epochs',type=int,default=15); ap.add_argument('--resume',action='store_true')
    a=ap.parse_args()
    classes=json.load(open(DS+'/classes.json',encoding='utf-8')); n=len(classes)
    exp=ROOT+'/experiments/classifier_resnet18'; os.makedirs(exp,exist_ok=True)
    log=Logger(exp+'/train.log'); dv=dataset_version(DS)
    log.log(f'=== 分类模型 A 训练 | dataset={dv} classes={n} epochs={a.epochs} ===')
    tl=DataLoader(ClfDataset('train',classes),batch_size=16,shuffle=True)
    vl=DataLoader(ClfDataset('val',classes),batch_size=16)
    te=DataLoader(ClfDataset('test',classes),batch_size=16)
    log.log(f'样本 train={len(tl.dataset)} val={len(vl.dataset)} test={len(te.dataset)}')
    device=torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    model=build_model(n).to(device)
    crit=nn.CrossEntropyLoss(); opt=optim.Adam(model.parameters(),lr=1e-4)
    start=0; best=-1
    ckpt=exp+'/checkpoint.pt'
    if a.resume and os.path.exists(ckpt):
        c=load_checkpoint(ckpt,model,opt); start=c['epoch']+1; best=c['metrics'].get('val_acc',0)
        log.log(f'断点续训: 从 epoch {start} 恢复, best={best:.4f}')
    for ep in range(start,a.epochs):
        model.train(); loss=0
        for x,y,_ in tl:
            x,y=x.to(device),y.to(device); opt.zero_grad()
            l=crit(model(x),y); l.backward(); opt.step(); loss+=l.item()*len(x)
        met,_,_,_=evaluate(model,vl,classes,device)
        acc=met['accuracy']
        log.log(f'epoch {ep+1}/{a.epochs} loss={loss/len(tl.dataset):.4f} val_acc={acc:.4f} val_macroF1={met["macro_f1"]:.4f}')
        if acc>best:
            best=acc
            torch.save(model.state_dict(),exp+'/best_model.pt')
            save_checkpoint(ckpt,model,opt,ep,{'val_acc':acc},{'dataset_version':dv,'classes':n})
            log.log(f'  保存最佳模型 val_acc={acc:.4f}')
        else:
            save_checkpoint(ckpt,model,opt,ep,{'val_acc':best},{'dataset_version':dv,'classes':n})
    # 最终评估：加载最佳模型，评估 test
    model.load_state_dict(torch.load(exp+'/best_model.pt',map_location=device))
    tmet,tcm,ytn,ypn=evaluate(model,te,classes,device)
    vmet,vcm,_,_=evaluate(model,vl,classes,device)
    log.log(f'TEST acc={tmet["accuracy"]:.4f} macroP={tmet["macro_precision"]:.4f} macroR={tmet["macro_recall"]:.4f} infer={tmet["avg_infer_ms"]:.2f}ms')
    res={'model_version':'clf-resnet18-v1','dataset_version':dv,'classes':[c['name'] for c in classes],
         'best_val_acc':best,'val':vmet,'test':tmet,'val_confusion':vcm,'test_confusion':tcm,
         'train_samples':len(tl.dataset),'val_samples':len(vl.dataset),'test_samples':len(te.dataset)}
    json.dump(res,open(exp+'/evaluation.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
    # 失败案例图片
    fdir=exp+'/failures'; os.makedirs(fdir,exist_ok=True)
    manifest=[m for m in json.load(open(DS+'/manifest.json',encoding='utf-8')) if m['split']=='test']
    fails=[(t,p,m) for t,p,m in zip(ytn,ypn,manifest) if t!=p]
    for i,(t,p,m) in enumerate(fails[:30]):
        im=Image.open(DS+'/'+m['clf_path']); im.save(f'{fdir}/{i:02d}_gt{t[:12]}_pred{p[:12]}.png')
    res['failures']=[{'gt':t,'pred':p,'frame':m['frame'],'bbox':[m['x0'],m['y0'],m['x1'],m['y1']]} for t,p,m in fails]
    json.dump(res,open(exp+'/evaluation.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
    log.log(f'评估完成，失败案例 {len(fails)} 张已保存，结果 -> evaluation.json')
    log.close()

if __name__=='__main__': main()
