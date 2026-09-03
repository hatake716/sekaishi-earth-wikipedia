#!/usr/bin/env python3
"""エージェント用バッチ入力 batches/{i}.json を作る(Wikipedia ベース)。
世界史の窓の解説は使わず、Wikipedia の記事冒頭(extract)・Wikidata の座標/年代を材料にする。"""
import json, os, sys
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
BATCH = int(sys.argv[1]) if len(sys.argv) > 1 else 40
terms = json.load(open(f'{SC}/terms_full.json'))
auto = {int(k): v for k, v in json.load(open(f'{SC}/auto_map.json')).items()}
extracts = json.load(open(f'{SC}/wiki_extracts.json')) if os.path.exists(f'{SC}/wiki_extracts.json') else {}


def slim_wd(wd):
    if not wd:
        return None
    o = {}
    for k in ('coord', 'birthCoord', 'locCoord', 'admCoord', 'countryCoord', 'formCoord', 'hqCoord', 'capCoord', 'originCoord', 'citizenCoord'):
        if wd.get(k):
            o[k] = [round(wd[k][0], 3), round(wd[k][1], 3)]
    for k in ('start', 'end', 'pointInTime', 'inception', 'dissolved', 'born', 'died'):
        if wd.get(k):
            o[k] = wd[k]
    if wd.get('classes'):
        o['classes'] = wd['classes'][:120]
    return o or None


def extract_for(title):
    """記事名の extract を返す(リダイレクトを辿る)。"""
    e = extracts.get(title)
    if not e:
        return None
    if e.get('missing') or e.get('disambig'):
        return {'title': title, 'disambig': bool(e.get('disambig')), 'missing': bool(e.get('missing')),
                'redirect': e.get('redirect')}
    return {'title': e.get('redirect') or title, 'extract': (e.get('extract') or '')[:300],
            'coord': e.get('coord')}


items = []
for t in terms:
    a = auto.get(t['id'], {})
    item = {
        'id': t['id'], 'term': t['term'], 'aliases': t.get('aliases', []),
        # chapter/section は編集著作物なので用語の主題判断のヒントとしてのみ渡す(desc に転記しない)
        'hint': ' / '.join(x for x in (t.get('section'), t.get('sub')) if x),
    }
    # 候補記事名: exact(用語一致) と search(全文検索) の題名。各題名の extract を添える。
    cand = []
    seen = set()
    for e in a.get('exact', [])[:4]:
        title = e['title']
        if title in seen:
            continue
        seen.add(title)
        ex = extract_for(title)
        cand.append({
            'title': title, 'match': 'exact', 'disambig': e.get('disambig', False),
            'wdDesc': e.get('desc'), 'coord': e.get('coord'), 'wd': slim_wd(e.get('wd')),
            'extract': (ex or {}).get('extract') if ex else None,
        })
    for s in (a.get('search') or [])[:3]:
        title = s['title']
        if title in seen:
            continue
        seen.add(title)
        ex = extract_for(title)
        cand.append({
            'title': title, 'match': 'search', 'snippet': s['snippet'][:80],
            'extract': (ex or {}).get('extract') if ex else None,
        })
    # 用語名そのものの記事(候補に無ければ)
    if t['term'] not in seen:
        ex = extract_for(t['term'])
        if ex and ex.get('extract'):
            cand.append({'title': t['term'], 'match': 'termname', 'extract': ex['extract'], 'coord': ex.get('coord')})
    item['candidates'] = cand
    items.append(item)

os.makedirs(f'{SC}/batches', exist_ok=True)
for f in os.listdir(f'{SC}/batches'):
    os.remove(f'{SC}/batches/{f}')
n = 0
for i in range(0, len(items), BATCH):
    json.dump(items[i:i + BATCH], open(f'{SC}/batches/{n}.json', 'w'), ensure_ascii=False, indent=1)
    n += 1
withext = sum(1 for x in items if any(c.get('extract') for c in x['candidates']))
print('batches', n, 'items', len(items), 'withExtract候補', withext)
