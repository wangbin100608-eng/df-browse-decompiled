"""用真实游戏帧标注训练分类器：留出验证（按标注组划分），避免同一物体裁剪同时进训练/测试。
真实样本少，先做近邻+小CNN两条线，报告留一视频验证准确率。
"""
import json, random, os, copy
import numpy as np
from PIL import Image, ImageEnhance
import torch, torch.nn as nn, torch.nn.functional as F

random.seed(7); np.random.seed(7); torch.manual_seed(7)
BASE='/root/盘点/train'
labels=[json.loads(l) for l in open(BASE+'/real_labels.jsonl',encoding='utf-8')]
name2id={}
for l in labels:
    if l['name'] not in name2id: name2id[l['name']]=len(name2id)
N=len(name2id)
print('labels',len(labels),'classes',N,name2id)

def crop(l):
    im=Image.open(BASE+'/annotate/%s.png'%l['frame']).convert('RGB')
    return im.crop((l['x0'],l['y0'],l['x1'],l['y1']))

def augment(im, k):
    out=[im]
    # 亮度/对比度/轻微平移/缩放（真实域内增广）
    for j in range(k):
        a=copy.copy(im)
        a=ImageEnhance.Brightness(a).enhance(random.uniform(0.8,1.2))
        a=ImageEnhance.Contrast(a).enhance(random.uniform(0.85,1.15))
        w,h=a.size
        # 随机中心裁剪 85-100% 再放大回原尺寸
        r=random.uniform(0.85,1.0); nw,nh=int(w*r),int(h*r)
        x=random.randint(0,w-nw); y=random.randint(0,h-nh)
        a=a.crop((x,y,x+nw,y+nh)).resize((w,h),Image.LANCZOS)
        out.append(a)
    return out

def to_np(im):
    return np.array(im.resize((64,64),Image.LANCZOS),dtype=np.float32).transpose(2,0,1)/255.0

class Net(nn.Module):
    def __init__(self,n):
        super().__init__()
        self.c1=nn.Conv2d(3,24,3,padding=1); self.c2=nn.Conv2d(24,48,3,padding=1)
        self.c3=nn.Conv2d(48,64,3,padding=1); self.fc=nn.Linear(64*8*8,n)
    def forward(self,x):
        x=F.relu(F.max_pool2d(self.c1(x),2))
        x=F.relu(F.max_pool2d(self.c2(x),2))
        x=F.relu(F.max_pool2d(self.c3(x),2))
        return self.fc(x.reshape(x.size(0),-1))

# 留出：以视频为划分 —— 用 v1 训练，v2 测试（反之亦然），最能说明泛化
for train_v,test_v in [('v1','v2'),('v2','v1')]:
    tr=[l for l in labels if l['frame'].startswith(train_v)]
    te=[l for l in labels if l['frame'].startswith(test_v)]
    print('\n=== train %s (%d) -> test %s (%d) ==='%(train_v,len(tr),test_v,len(te)))
    X=[];y=[]
    for l in tr:
        for a in augment(crop(l),12): X.append(to_np(a)); y.append(name2id[l['name']])
    X=np.array(X); y=np.array(y)
    xt=torch.tensor(X); yt=torch.tensor(y)
    net=Net(N); opt=torch.optim.Adam(net.parameters(),lr=1e-3,weight_decay=1e-4)
    for ep in range(30):
        net.train()
        for i in range(0,len(xt),32):
            opt.zero_grad(); loss=F.cross_entropy(net(xt[i:i+32]),yt[i:i+32]); loss.backward(); opt.step()
    # 测试：每个真实裁剪直接预测
    net.eval(); hits=0
    print('  %-22s pred / gt'%('test item'))
    for l in te:
        t=torch.tensor(to_np(crop(l))[None])
        with torch.no_grad(): p=net(t)[0]
        pred=int(p.argmax()); gt=name2id[l['name']]
        ok=pred==gt; hits+=ok
        inv={v:k for k,v in name2id.items()}
        print('  %-22s %s / %s %s'%(l['name'][:20],inv[pred][:16],inv[gt][:16],'OK' if ok else 'MISS'))
    print('  accuracy %d/%d = %.3f'%(hits,len(te),hits/len(te)))
    torch.save(net.state_dict(),BASE+'/net_train_%s.pt'%train_v)
