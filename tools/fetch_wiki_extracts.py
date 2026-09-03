#!/usr/bin/env python3
"""各用語の見込み記事名について、Wikipedia の記事冒頭(extract、プレーンテキスト)と
座標・カテゴリを取得する。desc・年代・分類はこの Wikipedia 素材から生成する。
出力: wiki_extracts.json  { title: {extract, coord:[lat,lon]|None, categories:[...], missing:bool, disambig:bool, redirect:正規題名|None} }
"""
import json, sys, time, urllib.request, urllib.parse, glob, os
S = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
UA = 'sekaishi-earth-wikipedia-builder/0.2 (https://github.com/hatake716/sekaishi-earth-wikipedia; acesmash@gmail.com)'
API = 'https://ja.wikipedia.org/w/api.php'

# 見込み記事名を集める(curate確定 > exact hit > 用語名)
titles = set()
cur = {}
for f in glob.glob(f'{S}/out/*.json'):
    if 'fix' in f: continue
    try: d = json.load(open(f))
    except: continue
    for x in d.get('items', []):
        if x.get('wikiTitle') and x.get('titleMatch') != 'none':
            titles.add(x['wikiTitle'])
auto = {int(k): v for k, v in json.load(open(f'{S}/auto_map.json')).items()}
for i, v in auto.items():
    for e in v.get('exact', []):
        if not e.get('disambig'): titles.add(e['title'])
    for s in (v.get('search') or [])[:1]:
        titles.add(s['title'])
terms = json.load(open(f'{S}/terms_full.json'))
for t in terms:
    titles.add(t['term'])
titles = sorted(t for t in titles if t)
print('候補記事名', len(titles), file=sys.stderr)

out_path = f'{S}/wiki_extracts.json'
out = json.load(open(out_path)) if os.path.exists(out_path) else {}
todo = [t for t in titles if t not in out]
print('todo', len(todo), file=sys.stderr)

def get(params):
    for i in range(5):
        try:
            req = urllib.request.Request(API + '?' + urllib.parse.urlencode(params), headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            print('retry', e, file=sys.stderr); time.sleep(3 * (i + 1))
    return None

# 20件ずつ(extract は exlimit=20 まで)
for k in range(0, len(todo), 20):
    batch = todo[k:k + 20]
    d = get({
        'action': 'query', 'format': 'json', 'formatversion': '2',
        'titles': '|'.join(batch), 'redirects': '1',
        'prop': 'extracts|coordinates|pageprops', 'ppprop': 'disambiguation',
        'exintro': '1', 'explaintext': '1', 'exsentences': '3', 'exlimit': '20',
        'coprop': 'type', 'colimit': '20',
    })
    if not d:
        for t in batch: out[t] = {'missing': True}
        continue
    q = d.get('query', {})
    norm = {n['from']: n['to'] for n in q.get('normalized', [])}
    redir = {r['from']: r['to'] for r in q.get('redirects', [])}
    pages = {p['title']: p for p in q.get('pages', [])}
    for t in batch:
        t2 = redir.get(norm.get(t, t), norm.get(t, t))
        p = pages.get(t2)
        rec = {'redirect': t2 if t2 != t else None}
        if not p or p.get('missing'):
            rec['missing'] = True
        else:
            rec['missing'] = False
            rec['disambig'] = 'disambiguation' in p.get('pageprops', {})
            rec['extract'] = (p.get('extract') or '')[:600]
            co = p.get('coordinates')
            rec['coord'] = [co[0]['lat'], co[0]['lon']] if co else None
        out[t] = rec
    if k % 200 == 0:
        print(k, '/', len(todo), file=sys.stderr)
        json.dump(out, open(out_path, 'w'), ensure_ascii=False)
    time.sleep(0.2)
json.dump(out, open(out_path, 'w'), ensure_ascii=False)
miss = sum(1 for v in out.values() if v.get('missing'))
dis = sum(1 for v in out.values() if v.get('disambig'))
withext = sum(1 for v in out.values() if v.get('extract'))
print(f'done {len(out)} missing {miss} disambig {dis} withExtract {withext}', file=sys.stderr)
