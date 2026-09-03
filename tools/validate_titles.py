#!/usr/bin/env python3
"""entries.json の wikiTitle が ja.wikipedia に存在し曖昧さ回避でないことを一括確認する。
リダイレクトは正規題名へ置換する。結果を entries.json に書き戻し、問題一覧を title_problems.json に出す。"""
import json, sys, time, urllib.request, urllib.parse
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
P = sys.argv[1] if len(sys.argv) > 1 else f'{SC}/entries.json'
UA = 'sekaishi-earth-wikipedia-builder/0.1 (https://github.com/hatake716/sekaishi-earth-wikipedia; acesmash@gmail.com)'
API = 'https://ja.wikipedia.org/w/api.php'
data = json.load(open(P))
titles = sorted({e[3] for e in data['entries'] if e[3]})
print('titles', len(titles), file=sys.stderr)
info = {}
def get(params):
    for i in range(6):
        try:
            req = urllib.request.Request(API + '?' + urllib.parse.urlencode(params), headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read().decode())
        except Exception as e:
            print('retry', e, file=sys.stderr); time.sleep(5 * (i + 1))
    return None
for i in range(0, len(titles), 50):
    b = titles[i:i + 50]
    d = get({'action': 'query', 'format': 'json', 'formatversion': '2', 'titles': '|'.join(b), 'redirects': '1', 'prop': 'pageprops', 'ppprop': 'disambiguation'})
    if not d: continue
    q = d['query']
    norm = {n['from']: n['to'] for n in q.get('normalized', [])}
    redir = {r['from']: r['to'] for r in q.get('redirects', [])}
    pages = {p['title']: p for p in q.get('pages', [])}
    for t in b:
        t2 = redir.get(norm.get(t, t), norm.get(t, t))
        p = pages.get(t2)
        if not p or p.get('missing'): info[t] = {'status': 'missing'}
        elif 'disambiguation' in p.get('pageprops', {}): info[t] = {'status': 'disambig', 'title': t2}
        else: info[t] = {'status': 'ok', 'title': t2}
    time.sleep(0.3)
problems = []
fixed = 0
for e in data['entries']:
    t = e[3]
    if not t: problems.append({'id': e[0], 'term': e[1], 'title': t, 'status': 'empty'}); continue
    s = info.get(t)
    if not s: problems.append({'id': e[0], 'term': e[1], 'title': t, 'status': 'unchecked'}); continue
    if s['status'] == 'ok':
        if s['title'] != t: e[3] = s['title']; fixed += 1
    else:
        problems.append({'id': e[0], 'term': e[1], 'title': t, 'status': s['status']})
json.dump(data, open(P, 'w'), ensure_ascii=False, separators=(',', ':'))
json.dump(problems, open(f'{SC}/title_problems.json', 'w'), ensure_ascii=False, indent=1)
from collections import Counter
print('redirects resolved', fixed, 'problems', Counter(p['status'] for p in problems), file=sys.stderr)
