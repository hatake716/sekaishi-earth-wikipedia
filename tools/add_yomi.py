#!/usr/bin/env python3
"""既存の entries.json に yomi フィールドを追加する。yomi.json から id→読みを引く。
各エントリ配列の末尾(exactTitle の後)に読み文字列を追加する。"""
import json, sys
APP = '/home/takeshi/StudioProjects/sekaishi-earth-wikipedia'
S = '/tmp/claude-1000/-home-takeshi-StudioProjects/f07f0e1d-7d3d-42e8-b004-3f7d194dfb59/scratchpad'
d = json.load(open(f'{APP}/app/src/main/assets/entries.json'))
yomi = json.load(open(f'{S}/yomi.json'))
es = d['entries']
added = 0
for e in es:
    eid = e[0]
    # 既に yomi 追加済み(末尾が文字列)ならスキップ判定: 長さ16=未追加, 17=追加済み
    y = yomi.get(str(eid), '')
    if len(e) == 16:
        e.append(y)
    else:
        e[16] = y
    if y:
        added += 1
json.dump(d, open(f'{APP}/app/src/main/assets/entries.json', 'w'), ensure_ascii=False, separators=(',', ':'))
print('yomi付与', added, '/', len(es), file=sys.stderr)
import os
print('サイズ', os.path.getsize(f'{APP}/app/src/main/assets/entries.json'), file=sys.stderr)
