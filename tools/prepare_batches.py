#!/usr/bin/env python3
"""エージェント用バッチ入力 batches/{i}.json を作る。"""
import json, os, sys
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
BATCH = int(sys.argv[1]) if len(sys.argv) > 1 else 40
terms = json.load(open(f'{SC}/terms_full.json'))
auto = {int(k): v for k, v in json.load(open(f'{SC}/auto_map.json')).items()}
cards = json.load(open(f'{SC}/yh_cards.json')) if os.path.exists(f'{SC}/yh_cards.json') else {}

def card_for(href):
    f, _, anchor = href.partition('#')
    cs = cards.get(f) or []
    if not cs: return None
    if anchor:
        for c in cs:
            if c['id'] == anchor: return c
    return cs[0]

def slim_wd(wd):
    if not wd: return None
    o = {}
    for k in ('coord', 'birthCoord', 'locCoord', 'admCoord', 'countryCoord', 'formCoord', 'hqCoord', 'capCoord', 'originCoord', 'citizenCoord'):
        if wd.get(k): o[k] = [round(wd[k][0], 3), round(wd[k][1], 3)]
    for k in ('start', 'end', 'pointInTime', 'inception', 'dissolved', 'born', 'died'):
        if wd.get(k): o[k] = wd[k]
    if wd.get('classes'): o['classes'] = wd['classes'][:120]
    return o or None

items = []
for t in terms:
    a = auto.get(t['id'], {})
    c = card_for(t['href'])
    item = {'id': t['id'], 'term': t['term'], 'aliases': t.get('aliases', []), 'chapter': t.get('chapter'), 'section': t.get('section'), 'sub': t.get('sub'),
            'url': t['url']}
    if c: item['yh'] = {'title': c['title'], 'lead': c['lead'], 'body': c['body'][:220]}
    ex = []
    for e in a.get('exact', [])[:4]:
        ex.append({'title': e['title'], 'desc': e.get('desc'), 'disambig': e.get('disambig', False), 'coord': e.get('coord'), 'wd': slim_wd(e.get('wd'))})
    item['exact'] = ex
    item['search'] = [{'title': s['title'], 'snippet': s['snippet'][:90]} for s in (a.get('search') or [])[:3]]
    items.append(item)
os.makedirs(f'{SC}/batches', exist_ok=True)
n = 0
for i in range(0, len(items), BATCH):
    json.dump(items[i:i + BATCH], open(f'{SC}/batches/{n}.json', 'w'), ensure_ascii=False, indent=1)
    n += 1
print('batches', n, 'items', len(items), 'with yh', sum(1 for x in items if 'yh' in x), 'with exact', sum(1 for x in items if x['exact']))
