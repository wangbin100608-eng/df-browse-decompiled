"""通用工具：版本、日志、断点、指标、混淆矩阵。"""
import json, os, time, hashlib, datetime
import numpy as np

def dataset_version(ds_dir):
    p=os.path.join(ds_dir,'manifest.json')
    h=hashlib.sha256(open(p,'rb').read()).hexdigest()[:12]
    return 'ds-'+h

def ensure_dir(p): os.makedirs(p,exist_ok=True); return p

class Logger:
    def __init__(self,path):
        ensure_dir(os.path.dirname(path)); self.path=path
        self.f=open(path,'a',encoding='utf-8')
    def log(self,msg):
        line=f'[{datetime.datetime.now().isoformat(timespec="seconds")}] {msg}'
        print(line); self.f.write(line+'\n'); self.f.flush()
    def close(self): self.f.close()

def save_checkpoint(path,model,optimizer,epoch,metrics,extra=None):
    import torch
    ck={'model':model.state_dict(),'optimizer':optimizer.state_dict() if optimizer else None,
        'epoch':epoch,'metrics':metrics,'extra':extra or {}}
    torch.save(ck,path)

def load_checkpoint(path,model,optimizer=None):
    import torch
    ck=torch.load(path,map_location='cpu')
    model.load_state_dict(ck['model'])
    if optimizer and ck.get('optimizer'): optimizer.load_state_dict(ck['optimizer'])
    return ck

def classification_metrics(y_true,y_pred,labels):
    """自实现，不依赖 sklearn。labels 为类名列表。"""
    n=len(labels); li={c:i for i,c in enumerate(labels)}
    tp=np.zeros(n); fp=np.zeros(n); fn=np.zeros(n)
    for t,p in zip(y_true,y_pred):
        ti=li[t] if t in li else -1; pi=li[p] if p in li else -1
        if ti>=0 and pi>=0:
            if ti==pi: tp[ti]+=1
            else: fp[pi]+=1; fn[ti]+=1
    prec=np.where(tp+fp>0,tp/(tp+fp+1e-12),0)
    rec=np.where(tp+fn>0,tp/(tp+fn+1e-12),0)
    f1=np.where(prec+rec>0,2*prec*rec/(prec+rec+1e-12),0)
    acc=float(tp.sum()/max(1,len(y_true)))
    return {'accuracy':acc,
            'per_class':{labels[i]:{'precision':float(prec[i]),'recall':float(rec[i]),'f1':float(f1[i]),
                                    'support':int(tp[i]+fn[i])} for i in range(n)},
            'macro_precision':float(prec.mean()),'macro_recall':float(rec.mean()),
            'macro_f1':float(f1.mean())}

def confusion(y_true,y_pred,labels):
    n=len(labels); li={c:i for i,c in enumerate(labels)}
    m=np.zeros((n,n),dtype=int)
    for t,p in zip(y_true,y_pred):
        if t in li and p in li: m[li[t],li[p]]+=1
    return m.tolist()
