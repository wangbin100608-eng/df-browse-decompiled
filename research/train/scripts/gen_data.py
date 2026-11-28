"""把 CDN 纯图标合成到游戏格子背景，生成检测训练数据。
每次随机：缩放、旋转、深色背景、边框、数量角标文字、位置（生成 bbox）。
输出：train/gen/images/*.png + train/gen/labels.jsonl（每图 bbox 列表）
"""
import json, os, random, math
from PIL import Image, ImageDraw, ImageFont, ImageEnhance

random.seed(20261128)
BASE='/root/盘点/train'
GEN=BASE+'/gen'
os.makedirs(GEN+'/images',exist_ok=True)
classes=json.load(open(BASE+'/classes.json',encoding='utf-8'))
N_PER=120  # 每类生成 120 张
W,H=640,480

# 尝试加载中文字体
FONT=None
for fp in ['/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc','/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf']:
    if os.path.exists(fp):
        try: FONT=ImageFont.truetype(fp,20); break
        except: pass

def dark_bg(w,h):
    base=random.choice([(22,26,34),(28,32,42),(18,22,28),(32,36,46)])
    im=Image.new('RGB',(w,h),base)
    d=ImageDraw.Draw(im)
    # 网格线模拟背包格子
    step=random.choice([80,100,120])
    grid=random.choice([(40,44,54),(30,34,42)])
    for x in range(0,w,step): d.line([(x,0),(x,h)],fill=grid,width=1)
    for y in range(0,h,step): d.line([(0,y),(w,y)],fill=grid,width=1)
    return im

def make_one(cls, idx):
    icon=Image.open(BASE+'/icons/'+cls['itemId']+'.webp').convert('RGBA')
    # 随机缩放（图标在格子里占比）
    box=random.randint(160,300)  # 目标格子边长
    ratio=random.uniform(0.6,0.95)
    tw=int(box*ratio); th=int(box*ratio)
    icon.thumbnail((tw,th),Image.LANCZOS)
    # 轻微旋转
    if random.random()<0.4:
        icon=icon.rotate(random.uniform(-8,8),expand=True,resample=Image.BICUBIC)
    # 亮度扰动
    if random.random()<0.5:
        r,g,b,a=icon.split()
        rgb=Image.merge('RGB',(r,g,b))
        rgb=ImageEnhance.Brightness(rgb).enhance(random.uniform(0.75,1.15))
        r,g,b=rgb.split(); icon=Image.merge('RGBA',(r,g,b,a))
    # 画布
    im=dark_bg(W,H)
    # 随机位置，图标居中于一个格子
    gx=random.randint(0,(W-box)//max(1,box))
    gy=random.randint(0,(H-box)//max(1,box))
    ox=gx*box+(box-icon.width)//2
    oy=gy*box+(box-icon.height)//2
    im.paste(icon,(ox,oy),icon)
    d=ImageDraw.Draw(im)
    # 格子边框
    d.rectangle([gx*box,gy*box,(gx+1)*box,(gy+1)*box],outline=(55,60,72),width=2)
    # 数量角标（随机数字）
    if random.random()<0.7:
        q=random.randint(1,99)
        d.text(((gx+1)*box-46,(gy+1)*box-26),'+%d'%q,fill=(240,240,240),font=FONT)
    # 名称标签（短名）
    if random.random()<0.7 and FONT:
        nm=cls['shortName'][:8]
        d.text((gx*box+3,gy*box+2),nm,fill=(200,205,215),font=FONT)
    # bbox: 图标本体 [x,y,w,h] 归一化（围绕实际图标区域，稍扩）
    pad=10
    x0=max(0,ox-pad); y0=max(0,oy-pad)
    x1=min(W,ox+icon.width+pad); y1=min(H,oy+icon.height+pad)
    bbox=[x0/W,y0/H,(x1-x0)/W,(y1-y0)/H]
    return im,bbox

labels=[]
cid=0
for ci,cls in enumerate(classes):
    for k in range(N_PER):
        im,bbox=make_one(cls,k)
        fn='%03d_%03d.png'%(ci,k)
        im.save(GEN+'/images/'+fn)
        labels.append({'image':fn,'bbox':bbox,'class':ci,'name':cls['name']})
        cid+=1
with open(GEN+'/labels.jsonl','w',encoding='utf-8') as f:
    for l in labels: f.write(json.dumps(l,ensure_ascii=False)+'\n')
print('generated',cid,'images; classes',len(classes))
print('classes:',[c['name'] for c in classes])
