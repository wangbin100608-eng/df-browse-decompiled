"""合并两视频真实标注训练 + 同源 k 折交叉验证（按标注组分层）。
目标：验证"这两个视频"上的识别能力（Q36 已定不强求跨对局泛化）。
"""
import json, random, copy
import numpy as np
from PIL import Image, ImageEnhance
import torch, torch.nn as nn, torch.nn.functional as F

random.seed(11); np.random.seed(11); torch.manual_seed(11)
BASE='/root/盘点/train'
labels=[json.loads(l) for l in open(BASE+'/real_labels.jsonl',encoding='utf-8')]
name2id={}
for l in labels:
    if l['name'] not in name2id: name2id[l['name']]=len(name2id)
N=len(name2id); inv={v:k for k,v in name2id.items()}
print('labels',len(labels),'classes',N)

def crop(l):
    im=Image.open(BASE+'/annotate/%s.png'%l['frame']).convert('RGB')
    return im.crop((l['x0'],l['y0'],l['x1'],l['y1']))

def to_np(im):
    return np.array(im.resize((64,64),Image.LANCZOS),dtype=np.float32).transpose(2,0,1)/255.0

def augment(im,k=16):
    out=[]
    for j in range(k):
        a=ImageEnhance.Brightness(im).enhance(random.uniform(0.8,1.25))
        a=ImageEnhance.Contrast(a).enhance(random.uniform(0.8,1.2))
        w,h=a.size; r=random.uniform(0.82,1.0)
        nw,nh=int(w*r),int(h*r)
        x=random.randint(0,w-nw); y=random.randint(0,h-nh)
        a=a.crop((x,y,x+nw,y+nh)).resize((w,h),Image.LANCZOS)
        out.append(to_np(a))
    return out

class Net(nn.Module):
    def __init__(self,n):
        super().__init__()
        self.c1=nn.Conv2d(3,24,3,padding=1); self.c2=nn.Conv2d(24,48,3,padding=1)
        self.c3=nn.Conv2d(48,64,3,padding=1); self.fc=nn.Linear(64*8*8,n)
    def forward(self,x):
        x=F.relu(F.max_pool2d(self.c1(x),2)); x=F.relu(F.max_pool2d(self.c2(x),2))
        x=F.relu(F.max_pool2d(self.c3(x),2))
        return self.fc(x.reshape(x.size(0),-1))

# 5-fold stratified by class
folds=[[] for _ in range(3)]
byclass={}
for i,l in enumerate(labels): byclass.setdefault(name2id[l['name']],[]).append(i)
for c,inds in byclass.items():
    for j,i in enumerate(inds): folds[j%3].append(i)

accs=[]
for f in range(3):
    te_idx=set(folds[f]); tr_idx=[i for i in range(len(labels)) if i not in te_idx]
    X=[];y=[]
    for i in tr_idx:
        for a in augment(crop(labels[i])): X.append(a); y.append(name2id[labels[i]['name']])
    xt=torch.tensor(np.array(X)); yt=torch.tensor(y)
    net=Net(N); opt=torch.optim.Adam(net.parameters(),lr=1e-3,weight_decay=1e-4)
    for ep in range(35):
        net.train()
        for k in range(0,len(xt),32):
            opt.zero_grad(); loss=F.cross_entropy(net(xt[k:k+32]),yt[k:k+32]); loss.backward(); opt.step()
    net.eval(); hits=0
    for i in folds[f]:
        t=torch.tensor(to_np(crop(labels[i]))[None])
        with torch.no_grad(): pred=int(net(t)[0].argmax())
        gt=name2id[labels[i]['name']]
        hits+= pred==gt
    a=hits/len(folds[f]); accs.append(a)
    print('fold%d: %d/%d = %.3f'%(f,hits,len(folds[f]),a))
print('\n同源3折平均准确率: %.3f'%(sum(accs)/len(accs)))

# 全量训练最终模型
X=[];y=[]
for l in labels:
    for a in augment(crop(l),24): X.append(a); y.append(name2id[l['name']])
xt=torch.tensor(np.array(X)); yt=torch.tensor(y)
net=Net(N); opt=torch.optim.Adam(net.parameters(),lr=1e-3,weight_decay=1e-4)
for ep in range(40):
    net.train()
    for k in range(0,len(xt),32):
        opt.zero_grad(); loss=F.cross_entropy(net(xt[k:k+32]),yt[k:k+32]); loss.backward(); opt.step()
torch.save(net.state_dict(),BASE+'/net_real_final.pt')
json.dump({'name2id':name2id,'inv':{str(k):v for k,v in inv.items()},'classes':N},open(BASE+'/real_classes.json','w'),ensure_ascii=False,indent=1)
# 训练集自测
net.eval(); hits=0
for l in labels:
    t=torch.tensor(to_np(crop(l))[None])
    with torch.no_grad(): pred=int(net(t)[0].argmax())
    hits += pred==name2id[l['name']]
print('全量训练自测: %d/%d = %.3f'%(hits,len(labels),hits/len(labels)))
print('模型已存 net_real_final.pt')
