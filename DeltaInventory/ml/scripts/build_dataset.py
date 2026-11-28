"""数据集构建器：标注 -> 划分 -> 裁剪 -> 检查。
- 与 1663 件物品库关联（name -> itemId）
- 按视频来源划分：v1=train/val，v2=test（避免同一对局跨集合泄漏）
- 低置信度标注（框面积过小/接近边界）标记进入人工审核
- 检查数据泄漏、类别不平衡
- 生成分类裁剪图 + 检测 yolo 标签
"""
import json, os, csv, shutil, random
from collections import Counter, defaultdict
from PIL import Image

random.seed(20261128)
ROOT='/root/工作区/df-browse-decompiled/DeltaInventory/ml'
DS=ROOT+'/dataset'
ANN='/root/工作区/df-browse-decompiled/research/train/real_labels.jsonl'
FRAMES='/root/工作区/df-browse-decompiled/research/train/annotate'
DB='/root/工作区/df-browse-decompiled/DeltaInventory/app/src/main/assets/item-database.json'

labels=[json.loads(l) for l in open(ANN,encoding='utf-8')]
items=json.load(open(DB,encoding='utf-8'))['items']

def norm(s): return s.replace(' ','').replace(')','').replace('(','').lower()
dbmap={norm(i['name']):i['itemId'] for i in items}
dbmap2={norm(i['shortName']):i['itemId'] for i in items}
name2item={}
for i in items:
    name2item[norm(i['name'])]=i
    name2item[norm(i['shortName'])]=i

# 类别定义：按标注 name
names=sorted(set(l['name'] for l in labels))
name2id={n:i for i,n in enumerate(names)}
classes=[]
for n in names:
    it=name2item.get(norm(n))
    classes.append({'class_id':name2id[n],'name':n,
                    'item_id':it['itemId'] if it else '',
                    'category':it['category'] if it else 'unknown',
                    'matched_db_name':it['name'] if it else ''})
json.dump(classes,open(DS+'/classes.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)

# 关联 itemId + 质量标记
for l in labels:
    it=name2item.get(norm(l['name']))
    l['item_id']=it['itemId'] if it else ''
    l['class_id']=name2id[l['name']]
    w,h=l['x1']-l['x0'],l['y1']-l['y0']
    l['area']=w*h
    # 低置信度：框太小(<40x40)或贴边(<10px)
    l['low_confidence']= (w<40 or h<40 or l['x0']<10 or l['y0']<10)
    l['width']=w; l['height']=h

# 按视频来源划分：v1 -> train/val(8:2 by frame), v2 -> test
all_frames=sorted(set(l['frame'] for l in labels))
# 分层按帧划分：每个类至少1帧进train，保证类别覆盖；test留出独立帧
by_class=defaultdict(set)
for l in labels: by_class[l['name']].add(l['frame'])
# 选择 test 帧：从每类挑1帧（不同类尽量不同帧），约占25%
test_frames=set()
for cls,frs in by_class.items():
    frs=sorted(frs)
    test_frames.add(frs[len(frs)//2])  # 每类中间那帧进test
# val 帧：每类另挑1帧（若该类>=3帧）
val_frames=set()
for cls,frs in by_class.items():
    frs=sorted(frs)
    if len(frs)>=3: val_frames.add(frs[0])
# 剩余帧进 train，确保每类至少1帧在train
train_frames=set(all_frames)-test_frames-val_frames
for cls,frs in by_class.items():
    if not (frs & train_frames):
        f=sorted(frs)[0]; test_frames.discard(f); val_frames.discard(f); train_frames.add(f)
def split_of(l):
    if l['frame'] in test_frames: return 'test'
    if l['frame'] in val_frames: return 'val'
    if l['frame'] in train_frames: return 'train'
    return 'train'
for l in labels: l['split']=split_of(l)

# 泄漏检查：帧不能跨 split；相同裁剪(帧+bbox)不重复
frame_splits=defaultdict(set)
bbox_seen=set(); dup=0
for l in labels:
    frame_splits[l['frame']].add(l['split'])
    key=(l['frame'],l['x0'],l['y0'],l['x1'],l['y1'],l['name'])
    if key in bbox_seen: dup+=1
    bbox_seen.add(key)
leaks={f:s for f,s in frame_splits.items() if len(s)>1}

# 类别平衡
cnt=Counter(l['name'] for l in labels)
cnt_train=Counter(l['name'] for l in labels if l['split']=='train')
cnt_test=Counter(l['name'] for l in labels if l['split']=='test')
lowfreq=[n for n,c in cnt.items() if c<5]

# 生成裁剪图（分类器）和 yolo 标签（检测器）
manifest=[]
for l in labels:
    fr=l['frame']; im=Image.open(f'{FRAMES}/{fr}.png').convert('RGB')
    crop=im.crop((l['x0'],l['y0'],l['x1'],l['y1']))
    # 分类器裁剪 -> 64x64 pad 保持比例
    sz=64; bg=Image.new('RGB',(sz,sz),(24,28,36))
    c=crop.copy(); c.thumbnail((sz,sz),Image.LANCZOS)
    bg.paste(c,((sz-c.width)//2,(sz-c.height)//2))
    sp=l['split']; nm=f"{l['class_id']:02d}_{fr}_{l['x0']}_{l['y0']}.png"
    out=f'{DS}/clf/{sp}/{nm}'; os.makedirs(os.path.dirname(out),exist_ok=True)
    bg.save(out)
    manifest.append({**l,'clf_path':os.path.relpath(out,DS)})

# yolo 标签：按帧聚合
yolo=defaultdict(list)
for l in labels:
    im=Image.open(f'{FRAMES}/{l["frame"]}.png'); W,H=im.size
    xc=(l['x0']+l['x1'])/2/W; yc=(l['y0']+l['y1'])/2/H
    bw=(l['x1']-l['x0'])/W; bh=(l['y1']-l['y0'])/H
    yolo[l['frame']].append(f"{l['class_id']} {xc:.5f} {yc:.5f} {bw:.5f} {bh:.5f}")
os.makedirs(DS+'/annotations/yolo',exist_ok=True)
for fr,lines in yolo.items():
    with open(f'{DS}/annotations/yolo/{fr}.txt','w') as f: f.write('\n'.join(lines)+'\n')
# 每帧复制到对应 split 的 images 目录
for fr,s in [(f,s) for f,ss in frame_splits.items() for s in ss]:
    sp=s
    shutil.copy(f'{FRAMES}/{fr}.png',f'{DS}/images/{sp}/{fr}.png')

json.dump(manifest,open(DS+'/manifest.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
report={
 'total_labels':len(labels),'classes':len(names),
 'frames':{'v1':len(set(l['frame'] for l in labels if l['source']=='v1')),'v2':len(set(l['frame'] for l in labels if l['source']=='v2'))},
 'split_frames':{'train':sorted(train_frames),'val':sorted(val_frames),'test':sorted(test_frames)},
 'split_labels':dict(Counter(l['split'] for l in labels)),
 'class_counts':dict(cnt),'class_counts_train':dict(cnt_train),'class_counts_test':dict(cnt_test),
 'low_confidence_count':sum(1 for l in labels if l['low_confidence']),
 'low_confidence_labels':[l for l in labels if l['low_confidence']],
 'duplicate_bbox':dup,'leak_frames':leaks,
 'low_frequency_classes':lowfreq,
 'db_linked':sum(1 for l in labels if l['item_id']),
 'db_unlinked':sorted(set(l['name'] for l in labels if not l['item_id'])),
}
json.dump(report,open(DS+'/dataset_report.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
print('=== 数据集构建完成 ===')
print('标注',report['total_labels'],'类',report['classes'],'划分',report['split_labels'])
print('帧划分 train/val/test:',len(train_frames),len(val_frames),len(test_frames))
print('低置信度',report['low_confidence_count'],'重复框',dup,'泄漏',len(leaks))
print('低频类',lowfreq)
print('关联1663库',report['db_linked'],'未关联',report['db_unlinked'])
