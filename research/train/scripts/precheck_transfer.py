"""迁移性预检：合成样本训练 CNN 分类器 -> 真实游戏帧裁块测试。
若合成域准确率很高但真实域崩盘，说明合成训练路线无法解决域漂移。
"""
import json, os, random
import numpy as np
from PIL import Image
import torch, torch.nn as nn, torch.nn.functional as F

random.seed(1); np.random.seed(1); torch.manual_seed(1)
BASE='/root/盘点/train'
GEN=BASE+'/gen'
classes=json.load(open(BASE+'/classes.json',encoding='utf-8'))
N=len(classes)

def load_synth():
    labels=[json.loads(l) for l in open(GEN+'/labels.jsonl',encoding='utf-8')]
    # 按 bbox 裁剪图标区域 -> 64x64
    X=[]; y=[]
    for l in labels:
        im=Image.open(GEN+'/images/'+l['image']).convert('RGB')
        bx,by,bw,bh=l['bbox']; W,H=im.size
        x0=int(bx*W); y0=int(by*H); x1=int((bx+bw)*W); y1=int((by+bh)*H)
        crop=im.crop((x0,y0,x1,y1)).resize((64,64),Image.LANCZOS)
        X.append(np.array(crop,dtype=np.float32).transpose(2,0,1)/255.0)
        y.append(l['class'])
    return np.array(X),np.array(y)

class Net(nn.Module):
    def __init__(self,n):
        super().__init__()
        self.c1=nn.Conv2d(3,24,3,padding=1); self.c2=nn.Conv2d(24,48,3,padding=1)
        self.c3=nn.Conv2d(48,64,3,padding=1)
        self.fc=nn.Linear(64*8*8,n)
    def forward(self,x):
        x=F.relu(F.max_pool2d(self.c1(x),2))
        x=F.relu(F.max_pool2d(self.c2(x),2))
        x=F.relu(F.max_pool2d(self.c3(x),2))
        return self.fc(x.reshape(x.size(0),-1))

X,y=load_synth()
print('synth samples',X.shape)
# 80/20 划分
idx=np.random.permutation(len(X)); tr=idx[:int(0.8*len(idx))]; va=idx[int(0.8*len(idx)):]
xt=torch.tensor(X[tr]); yt=torch.tensor(y[tr]); xv=torch.tensor(X[va]); yv=torch.tensor(y[va])
net=Net(N); opt=torch.optim.Adam(net.parameters(),lr=1e-3)
for ep in range(6):
    net.train()
    for i in range(0,len(xt),64):
        opt.zero_grad(); loss=F.cross_entropy(net(xt[i:i+64]),yt[i:i+64]); loss.backward(); opt.step()
    net.eval()
    with torch.no_grad():
        acc=(net(xv).argmax(1)==yv).float().mean().item()
    print('epoch',ep,'val acc',round(acc,4))
torch.save(net.state_dict(),BASE+'/precheck_net.pt')

# 真实帧裁块：从 v1_2.png / v2_2.png 里人工框几个已知物品格子
# 坐标基于 1280x800 帧目视估计（name, x0,y0,x1,y1）
real_crops=[
    ('CINDER',300,510,376,555),   # 口袋 CINDER 弹
    ('M995',380,510,455,555),
    ('强效注射器',215,510,290,560),
    ('DICH 训练头盔',260,410,490,530),
    ('Mini-14',260,200,500,330),
    ('复合弓',260,345,495,470),
    ('UZI',760,120,1020,250),
    ('M1911',1080,120,1260,250),
]
name2id={c['name'].replace(' ',''):i for i,c in enumerate(classes)}
# 别名映射
alias={'CINDER':'5.56x45mmCINDER','M995':'5.56x45mmM995','强效注射器':'强效注射器','DICH 训练头盔':'DICH训练头盔','Mini-14':'Mini-14射手步枪','复合弓':'复合弓','UZI':'UZI冲锋枪','M1911':'M1911'}
net.eval()
print('\n=== 真实帧裁块预测（合成域训练的模型）===')
hit=0; tot=0
for nm,x0,y0,x1,y1 in real_crops:
    im=Image.open('/root/盘点/frames/v1_2.png').convert('RGB').crop((x0,y0,x1,y1)).resize((64,64),Image.LANCZOS)
    t=torch.tensor(np.array(im,dtype=np.float32).transpose(2,0,1)[None]/255.0)
    with torch.no_grad(): p=net(t)[0]
    probs=F.softmax(p,0); top=probs.argsort(descending=True)[:3].tolist()
    pred=[classes[i]['name']+' %.2f'%probs[i].item() for i in top]
    gt=alias.get(nm,nm); gtid=name2id.get(gt.replace(' ',''),-1)
    ok=gtid in top
    hit+=ok; tot+=1
    print('%-14s -> %s | gt=%s %s'%(nm,' ; '.join(pred),gt,'OK' if ok else 'MISS'))
print('\n真实域 top3 命中率: %d/%d = %.3f'%(hit,tot,hit/tot))
