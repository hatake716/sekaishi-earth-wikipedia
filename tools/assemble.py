#!/usr/bin/env python3
"""エージェント出力 out/{i}.json と修正 out/{i}.fix.json を統合し、検証して entries.json を出力する。"""
import json, os, sys, glob, re
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
OUT = sys.argv[1] if len(sys.argv) > 1 else f'{SC}/entries.json'
terms = {t['id']: t for t in json.load(open(f'{SC}/terms_full.json'))}
CATS = {'EVENT': 0, 'PERSON': 1, 'POLITY': 2, 'PLACE': 3, 'CULTURE': 4, 'CONCEPT': 5}
results = {}
for f in sorted(glob.glob(f'{SC}/out/*.json')):
    if f.endswith('.fix.json'): continue
    try:
        data = json.load(open(f))
    except Exception as e:
        print('bad json', f, e, file=sys.stderr); continue
    items = data.get('items', data) if isinstance(data, dict) else data
    for it in items:
        if not isinstance(it, dict) or 'id' not in it: continue
        results[int(it['id'])] = it
fixes = 0
for f in sorted(glob.glob(f'{SC}/out/*.fix.json')):
    try:
        data = json.load(open(f))
    except Exception as e:
        print('bad fix json', f, e, file=sys.stderr); continue
    for fx in (data.get('fixes', data) if isinstance(data, dict) else data):
        i = int(fx['id'])
        if i not in results: continue
        for k, v in (fx.get('set') or {}).items():
            results[i][k] = v; fixes += 1
print('results', len(results), 'of', len(terms), 'fixes applied', fixes, file=sys.stderr)
missing = [i for i in terms if i not in results]
print('missing', len(missing), missing[:20], file=sys.stderr)

chapters, sections = [], []
ci, si = {}, {}
for t in sorted(terms.values(), key=lambda x: x.get('order', 10**6)):
    c = t.get('chapter') or 'その他'; s = t.get('section') or ''
    if c not in ci: ci[c] = len(chapters); chapters.append(c)
    if s not in si: si[s] = len(sections); sections.append(s)

def to_int(v):
    if v is None or v == '': return None
    try: return int(v)
    except Exception: return None

entries = []
problems = []
for i, t in sorted(terms.items()):
    r = results.get(i)
    if r is None:
        problems.append((i, 'missing')); continue
    try:
        lat = float(r['lat']); lon = float(r['lon'])
    except Exception:
        problems.append((i, 'coord')); continue
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        problems.append((i, 'coord range')); continue
    cat = r.get('category')
    cat = CATS.get(cat, cat) if isinstance(cat, str) else cat
    if not isinstance(cat, int) or not 0 <= cat <= 5: cat = 5
    imp = to_int(r.get('importance')) or 2
    imp = min(3, max(1, imp))
    year = to_int(r.get('year')); year_end = to_int(r.get('yearEnd'))
    if year == 0: year = -1
    if year_end == 0: year_end = 1
    title = (r.get('wikiTitle') or '').strip()
    if r.get('titleMatch') == 'none': title = ''
    entries.append([i, t['term'], '|'.join(t.get('aliases', [])), title, round(lat, 4), round(lon, 4),
                    (r.get('place') or '').strip(), year, year_end, (r.get('era') or '').strip(), cat, imp,
                    (r.get('desc') or '').strip(), ci[t.get('chapter') or 'その他'], si[t.get('section') or ''],
                    t.get('sub') or '', t.get('order', 10**6), t['href'],
                    0 if (title and r.get('titleMatch') == 'related') else 1])
print('entries', len(entries), 'problems', len(problems), problems[:20], file=sys.stderr)
json.dump({'chapters': chapters, 'sections': sections, 'entries': entries}, open(OUT, 'w'), ensure_ascii=False, separators=(',', ':'))
print('wrote', OUT, os.path.getsize(OUT), file=sys.stderr)
