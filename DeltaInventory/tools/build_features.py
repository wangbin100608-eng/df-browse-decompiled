#!/usr/bin/env python3
"""生成 item-features.json —— 与 Android 端 StepHash.java 逐位一致的特征库。

算法（必须与 StepHash.java 完全一致，否则识别会错乱）：
- 从 item-database.json 读取 1663 件物品的 iconPath。
- 下载图标（CDN: https://df-assets.lza11111.com + iconPath），缓存在 --cache 目录。
- phash: 步长采样到 9x8，灰度 = (R*77+G*150+B*29)>>8，
  透明像素按 Android Bitmap.getPixels 的预乘语义 r=r*a//255 等处理，
  再对每行相邻像素做 64 次 (a>b) 比较得到 64bit dHash，写成无符号十进制字符串。
- hist: 步长采样 32x32，亮度归一化 16 桶（avgR/avgG 为样本均值，
  ri=clamp((r-avgR+256)//64,0,3), gi 同理，hist[ri*4+gi]+=1/1024）。

依赖: Pillow (pip install pillow)。

用法:
    python3 tools/build_features.py --db app/src/main/assets/item-database.json \
        --cache /tmp/df-icons --out app/src/main/assets/item-features.json
"""
import argparse, json, os, sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.request import urlretrieve
from PIL import Image

CDN = "https://df-assets.lza11111.com"


def load_items(db_path):
    db = json.load(open(db_path, encoding="utf-8"))
    return db["items"] if isinstance(db, dict) else db


def download(item, cache):
    iid = str(item["itemId"])
    path = item.get("iconPath")
    fp = os.path.join(cache, iid + ".webp")
    if not path:
        return iid, None
    if not (os.path.exists(fp) and os.path.getsize(fp) > 0):
        try:
            urlretrieve(CDN + path, fp)
        except Exception:
            return iid, None
    return iid, fp


def phash(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    sx, sy = w / 9.0, h / 8.0
    grid = []
    for dy in range(8):
        sy0 = min(int(dy * sy), h - 1)
        row = []
        for dx in range(9):
            sx0 = min(int(dx * sx), w - 1)
            r, g, b, a = px[sx0, sy0]
            r, g, b = r * a // 255, g * a // 255, b * a // 255  # Android 预乘
            row.append((r * 77 + g * 150 + b * 29) >> 8)
        grid.append(row)
    hv = 0
    for y in range(8):
        for x in range(8):
            hv = (hv << 1) | (1 if grid[y][x] > grid[y][x + 1] else 0)
    return str(hv & 0xFFFFFFFFFFFFFFFF)


def hist(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    n = 32
    sx, sy = w / float(n), h / float(n)
    rs, gs = [], []
    for dy in range(n):
        sy0 = min(int(dy * sy), h - 1)
        for dx in range(n):
            sx0 = min(int(dx * sx), w - 1)
            r, g, b, a = px[sx0, sy0]
            rs.append(r * a // 255)
            gs.append(g * a // 255)
    avgR, avgG = sum(rs) / len(rs), sum(gs) / len(gs)
    out = [0.0] * 16
    for r, g in zip(rs, gs):
        ri = max(0, min(3, int((r - avgR + 256) // 64)))
        gi = max(0, min(3, int((g - avgG + 256) // 64)))
        out[ri * 4 + gi] += 1.0 / 1024
    return [round(x, 4) for x in out]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="app/src/main/assets/item-database.json")
    ap.add_argument("--cache", default="/tmp/df-icons")
    ap.add_argument("--out", default="app/src/main/assets/item-features.json")
    ap.add_argument("--threads", type=int, default=24)
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)

    items = load_items(args.db)
    paths = {}
    with ThreadPoolExecutor(max_workers=args.threads) as ex:
        futs = [ex.submit(download, it, args.cache) for it in items]
        for f in as_completed(futs):
            iid, fp = f.result()
            paths[iid] = fp

    # 保持与 item-database.json 相同顺序与字段；从旧特征库取 id/name/w/h/cat/price（若存在）
    old = {}
    old_feat_path = args.out
    if os.path.exists(old_feat_path):
        try:
            old = {x["id"]: x for x in json.load(open(old_feat_path, encoding="utf-8"))["features"]}
        except Exception:
            pass

    features = []
    missing = 0
    for it in items:
        iid = str(it["itemId"])
        fp = paths.get(iid)
        if not fp:
            missing += 1
            continue
        try:
            base = old.get(iid, {})
            feat = {
                "id": iid,
                "name": base.get("name") or it.get("name", ""),
                "w": base.get("w") or it.get("width", 0),
                "h": base.get("h") or it.get("height", 0),
                "cat": base.get("cat") or it.get("category", ""),
                "price": base.get("price") or it.get("displayValueCents") or it.get("baseValueCents") or 0,
                "ph": phash(fp),
                "hist": hist(fp),
            }
            features.append(feat)
        except Exception as e:
            print(f"warn: {iid}: {e}", file=sys.stderr)
            missing += 1

    json.dump({"features": features}, open(args.out, "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {len(features)} features to {args.out} (missing {missing})")


if __name__ == "__main__":
    main()
