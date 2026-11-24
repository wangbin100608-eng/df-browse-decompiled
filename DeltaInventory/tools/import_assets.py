#!/usr/bin/env python3
"""
DeltaInventory 数据导入工具（离线，仅开发/构建时使用）
把 WASM 导出的 item_table.json 转为 Android SQLite 可导入的 item-database.json。
不猜测任何字段；无法确认的标记 null。
Python 仅用于离线数据处理，不在 Android 运行时使用。
"""
import json, sys, argparse

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', default='item_table.json')
    ap.add_argument('--output', default='item-database.json')
    ap.add_argument('--include-weapons', action='store_true', help='是否包含枪械/配件/弹药（默认排除，符合项目范围）')
    args = ap.parse_args()

    items = json.load(open(args.input, encoding='utf-8'))
    excluded = {'gun', 'ammo', 'acc'} if not args.include_weapons else set()
    out = []
    for it in items:
        pc = it.get('primaryClass', '')
        if pc in excluded:
            continue
        pre = it.get('prePic') or ''
        out.append({
            'itemId': str(it['objectID']),
            'name': it.get('objectName') or it.get('shortName') or 'unknown',
            'shortName': it.get('shortName'),
            'width': int(it.get('width') or 0),
            'height': int(it.get('length') or 0),
            'category': pc or 'unknown',
            'baseValueCents': None,  # 价格来自 DFPB v6，不猜测基础价值
            'displayValue': it.get('displayValue'),  # v6 快照，来自 WASM
            'marketPrice': it.get('marketPrice'),
            'iconPath': pre[:-4] + '.webp' if pre.endswith('.png') else pre,
            'grade': it.get('grade'),
            'source': 'wasm_get_all_items',
        })
    json.dump({
        'version': 1,
        'totalItems': len(out),
        'scope': 'normal non-weapon items' if not args.include_weapons else 'all',
        'source': 'df-browse WASM wasm_get_all_items + DFPB price v6',
        'items': out,
    }, open(args.output, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'wrote {args.output}: {len(out)} items (from {len(items)})')

if __name__ == '__main__':
    main()
