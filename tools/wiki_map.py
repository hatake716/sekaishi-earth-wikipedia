#!/usr/bin/env python3
"""用語 → ja.wikipedia 記事の自動マッピング + Wikidata 座標/年代取得。
出力: auto_map.json (term id → 候補・完全一致・座標など)
"""
import json, re, sys, time, urllib.request, urllib.parse, unicodedata, threading
from concurrent.futures import ThreadPoolExecutor
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
UA = 'sekaishi-earth-wikipedia-builder/0.1 (https://github.com/hatake716/sekaishi-earth-wikipedia; acesmash@gmail.com)'
API = 'https://ja.wikipedia.org/w/api.php'
lock = threading.Lock()

def get(url, params=None, retries=6):
    if params: url = url + '?' + urllib.parse.urlencode(params)
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode('utf-8'))
        except Exception as e:
            wait = 5 * (i + 1) if '429' in str(e) else 2 * (i + 1)
            print('retry', i, url[:120], e, file=sys.stderr); time.sleep(wait)
    return None

def z2h(s):  # 全角英数字→半角
    return unicodedata.normalize('NFKC', s)

def candidates(term):
    c = []
    def add(x):
        x = x.strip()
        if x and x not in c: c.append(x)
    base = [term]
    if '／' in term: base += [p for p in term.split('／')]
    for b in list(base):
        s = re.sub(r'（.*?）', '', b).strip()
        if s != b: base.append(s)
    for b in base:
        add(b)
        add(z2h(b))
        add(b.replace('＝', '・'))
        add(z2h(b).replace('＝', '・').replace('=', '・'))
        add(z2h(b).replace('＝', '='))
        add(b.replace('ヴァ', 'バ').replace('ヴィ', 'ビ').replace('ヴェ', 'ベ').replace('ヴォ', 'ボ').replace('ヴ', 'ブ').replace('＝', '・'))
        add(z2h(b).replace('ヴァ', 'バ').replace('ヴィ', 'ビ').replace('ヴェ', 'ベ').replace('ヴォ', 'ボ').replace('ヴ', 'ブ').replace('＝', '・'))
    return c[:8]

terms = json.load(open(f'{SC}/terms_full.json'))
result = {}
try:
    result = json.load(open(f'{SC}/auto_map.json'))
    result = {int(k): v for k, v in result.items()}
    print('resuming with', len(result), file=sys.stderr)
except Exception:
    pass

# ---------- Phase 1: exact title lookup ----------
def lookup_titles(titles):
    """titles(<=50) → {normalized-input-title: page info}"""
    out = {}
    data = get(API, {'action': 'query', 'format': 'json', 'formatversion': '2', 'titles': '|'.join(titles), 'redirects': '1',
                     'prop': 'pageprops|coordinates|pageterms', 'ppprop': 'disambiguation|wikibase_item', 'coprimary': 'primary',
                     'wbptterms': 'description'})
    if not data or 'query' not in data: return out
    q = data['query']
    norm = {}
    for n in q.get('normalized', []): norm[n['from']] = n['to']
    redir = {}
    for r in q.get('redirects', []): redir[r['from']] = r['to']
    pages = {}
    for p in q.get('pages', []):
        pages[p['title']] = p
    for t in titles:
        t2 = norm.get(t, t)
        t3 = redir.get(t2, t2)
        p = pages.get(t3)
        if not p or p.get('missing'): continue
        pp = p.get('pageprops', {})
        coord = None
        if p.get('coordinates'):
            c0 = p['coordinates'][0]; coord = [c0['lat'], c0['lon']]
        out[t] = {'title': p['title'], 'pageid': p.get('pageid'), 'redirectedFrom': t2 if t2 != t3 else None,
                  'disambig': 'disambiguation' in pp, 'qid': pp.get('wikibase_item'), 'coord': coord,
                  'desc': (p.get('terms', {}).get('description') or [None])[0]}
    return out

todo = [x for x in terms if x['id'] not in result]
print('phase1 todo', len(todo), file=sys.stderr)
allc = []
for x in todo:
    cs = candidates(x['term'])
    for al in x.get('aliases', []): cs += candidates(al)
    x['_cands'] = cs[:10]
    allc += x['_cands']
uniq = list(dict.fromkeys(allc))
print('unique candidate titles', len(uniq), file=sys.stderr)
hits = {}
def work(batch):
    r = lookup_titles(batch)
    with lock: hits.update(r)
batches = [uniq[i:i + 50] for i in range(0, len(uniq), 50)]
with ThreadPoolExecutor(max_workers=4) as ex:
    for i, _ in enumerate(ex.map(work, batches)):
        if i % 20 == 0: print('phase1 batch', i, '/', len(batches), file=sys.stderr)
for x in todo:
    r = {'term': x['term'], 'exact': [], 'search': []}
    for c in x['_cands']:
        if c in hits:
            h = dict(hits[c]); h['via'] = c
            if not any(e['title'] == h['title'] for e in r['exact']): r['exact'].append(h)
    result[x['id']] = r
json.dump(result, open(f'{SC}/auto_map.json', 'w'), ensure_ascii=False)
print('phase1 done; with exact hit:', sum(1 for v in result.values() if v['exact']), file=sys.stderr)

# ---------- Phase 2: search for all (to give agents candidates) ----------
def search(term, extra=''):
    data = get(API, {'action': 'query', 'format': 'json', 'formatversion': '2', 'list': 'search', 'srsearch': term + (' ' + extra if extra else ''),
                     'srlimit': '6', 'srprop': 'snippet', 'srnamespace': '0'})
    if not data or 'query' not in data: return None
    return [{'title': s['title'], 'snippet': re.sub(r'<[^>]+>', '', s.get('snippet', ''))} for s in data['query']['search']]
todo2 = [x for x in terms if not result[x['id']].get('_searched')]
print('phase2 todo', len(todo2), file=sys.stderr)
def work2(x):
    t = re.sub(r'（.*?）', '', x['term'].split('／')[0]).strip()
    s = search(z2h(t).replace('＝', '・'))
    if s is not None and not s: s = search(x['term'])
    time.sleep(0.15)
    if s is None: return
    with lock:
        result[x['id']]['search'] = s; result[x['id']]['_searched'] = True
with ThreadPoolExecutor(max_workers=2) as ex:
    for i, _ in enumerate(ex.map(work2, todo2)):
        if i % 200 == 0:
            print('phase2', i, '/', len(todo2), file=sys.stderr)
            with lock: json.dump(result, open(f'{SC}/auto_map.json', 'w'), ensure_ascii=False)
json.dump(result, open(f'{SC}/auto_map.json', 'w'), ensure_ascii=False)
print('phase2 done', file=sys.stderr)

# ---------- Phase 3: Wikidata (coords, dates, instance-of) for all exact-hit QIDs ----------
SPARQL = 'https://query.wikidata.org/sparql'
def wd_batch(qids):
    vals = ' '.join('wd:' + q for q in qids)
    query = f"""
SELECT ?item (SAMPLE(?c) AS ?coord) (SAMPLE(?bc) AS ?birthCoord) (SAMPLE(?lc) AS ?locCoord) (SAMPLE(?ac) AS ?admCoord)
 (SAMPLE(?cc) AS ?countryCoord) (SAMPLE(?fc) AS ?formCoord) (SAMPLE(?hc) AS ?hqCoord) (SAMPLE(?capc) AS ?capCoord) (SAMPLE(?oc) AS ?originCoord) (SAMPLE(?czc) AS ?citizenCoord)
 (SAMPLE(?st) AS ?start) (SAMPLE(?en) AS ?end) (SAMPLE(?pit) AS ?pointInTime) (SAMPLE(?inc) AS ?inception) (SAMPLE(?dis) AS ?dissolved) (SAMPLE(?b) AS ?born) (SAMPLE(?d) AS ?died)
 (GROUP_CONCAT(DISTINCT ?clsLabel; separator="|") AS ?classes)
WHERE {{
  VALUES ?item {{ {vals} }}
  OPTIONAL {{ ?item wdt:P625 ?c }}
  OPTIONAL {{ ?item wdt:P19/wdt:P625 ?bc }}
  OPTIONAL {{ ?item wdt:P276/wdt:P625 ?lc }}
  OPTIONAL {{ ?item wdt:P131/wdt:P625 ?ac }}
  OPTIONAL {{ ?item wdt:P17/wdt:P625 ?cc }}
  OPTIONAL {{ ?item wdt:P740/wdt:P625 ?fc }}
  OPTIONAL {{ ?item wdt:P159/wdt:P625 ?hc }}
  OPTIONAL {{ ?item wdt:P36/wdt:P625 ?capc }}
  OPTIONAL {{ ?item wdt:P495/wdt:P625 ?oc }}
  OPTIONAL {{ ?item wdt:P27/wdt:P625 ?czc }}
  OPTIONAL {{ ?item wdt:P580 ?st }}
  OPTIONAL {{ ?item wdt:P582 ?en }}
  OPTIONAL {{ ?item wdt:P585 ?pit }}
  OPTIONAL {{ ?item wdt:P571 ?inc }}
  OPTIONAL {{ ?item wdt:P576 ?dis }}
  OPTIONAL {{ ?item wdt:P569 ?b }}
  OPTIONAL {{ ?item wdt:P570 ?d }}
  OPTIONAL {{ ?item wdt:P31 ?cls . ?cls rdfs:label ?clsLabel FILTER(LANG(?clsLabel)="en") }}
}} GROUP BY ?item"""
    data = get(SPARQL, {'query': query, 'format': 'json'})
    out = {}
    if not data: return out
    for b in data['results']['bindings']:
        q = b['item']['value'].rsplit('/', 1)[1]
        def pc(k):
            v = b.get(k, {}).get('value')
            if not v: return None
            m = re.match(r'Point\(([-\d.eE+]+) ([-\d.eE+]+)\)', v)
            return [float(m.group(2)), float(m.group(1))] if m else None
        def pd(k):
            v = b.get(k, {}).get('value'); return v[:10] if v else None
        out[q] = {'coord': pc('coord'), 'birthCoord': pc('birthCoord'), 'locCoord': pc('locCoord'), 'admCoord': pc('admCoord'),
                  'countryCoord': pc('countryCoord'), 'formCoord': pc('formCoord'), 'hqCoord': pc('hqCoord'), 'capCoord': pc('capCoord'),
                  'originCoord': pc('originCoord'), 'citizenCoord': pc('citizenCoord'),
                  'start': pd('start'), 'end': pd('end'), 'pointInTime': pd('pointInTime'), 'inception': pd('inception'), 'dissolved': pd('dissolved'),
                  'born': pd('born'), 'died': pd('died'), 'classes': b.get('classes', {}).get('value')}
    return out
qids = []
for v in result.values():
    for e in v['exact']:
        if e.get('qid') and 'wd' not in e: qids.append(e['qid'])
qids = list(dict.fromkeys(qids))
print('phase3 qids', len(qids), file=sys.stderr)
wd = {}
bs = [qids[i:i + 80] for i in range(0, len(qids), 80)]
for i, b in enumerate(bs):
    r = wd_batch(b); wd.update(r)
    if i % 10 == 0: print('phase3', i, '/', len(bs), len(wd), file=sys.stderr)
    time.sleep(0.5)
for v in result.values():
    for e in v['exact']:
        if e.get('qid') in wd: e['wd'] = wd[e['qid']]
json.dump(result, open(f'{SC}/auto_map.json', 'w'), ensure_ascii=False)
print('phase3 done', file=sys.stderr)
