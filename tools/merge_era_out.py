#!/usr/bin/env python3
"""era_out/*.json(推定)と era_out/*.fix.json(検証修正)を統合して era_fix.json を作る。
apply_era.py がこの era_fix.json を読んで entries.json に反映する。"""
import json, glob, os, sys
S = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = {}
# 推定本体
for f in sorted(glob.glob(f'{S}/era_out/*.json')):
    if f.endswith('.fix.json'):
        continue
    try:
        data = json.load(open(f))
    except Exception as e:
        print('bad', f, e, file=sys.stderr); continue
    for it in data.get('items', data if isinstance(data, list) else []):
        if not isinstance(it, dict) or 'id' not in it:
            continue
        out[str(it['id'])] = {'year': it.get('year'), 'yearEnd': it.get('yearEnd'), 'era': it.get('era', '')}
# 検証修正を上書き
fixes = 0
for f in sorted(glob.glob(f'{S}/era_out/*.fix.json')):
    try:
        data = json.load(open(f))
    except Exception:
        continue
    for fx in data.get('fixes', data if isinstance(data, list) else []):
        if not isinstance(fx, dict) or 'id' not in fx:
            continue
        k = str(fx['id'])
        if k in out:
            out[k].update(fx.get('set', {}))
            fixes += 1
json.dump(out, open(f'{S}/era_fix.json', 'w'), ensure_ascii=False)
print('era_fix.json', len(out), 'entries,', fixes, 'fixes applied', file=sys.stderr)
