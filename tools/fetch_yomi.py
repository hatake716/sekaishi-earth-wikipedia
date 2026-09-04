#!/usr/bin/env python3
"""漢字・非かな始まりの用語について、Wikipedia 記事冒頭から読み仮名を取得する。
出力: yomi.json  { entry_id: "よみ(ひらがな)" }
読みは五十音順ソートのキーに使う。取得できない語は元の用語名でソートする(現状維持)。"""
import json, re, sys, time, urllib.request, urllib.parse, os
APP = '/home/takeshi/StudioProjects/sekaishi-earth-wikipedia'
S = '/tmp/claude-1000/-home-takeshi-StudioProjects/f07f0e1d-7d3d-42e8-b004-3f7d194dfb59/scratchpad'
UA = 'sekaishi-earth-yomi/0.1 (https://github.com/hatake716/sekaishi-earth-wikipedia; acesmash@gmail.com)'
API = 'https://ja.wikipedia.org/w/api.php'
d = json.load(open(f'{APP}/app/src/main/assets/entries.json'))
es = d['entries']

def is_kana(c):
    return ('ぁ' <= c <= 'ゖ') or ('ァ' <= c <= 'ヶ') or c == 'ー'

def needs_yomi(term):
    c = term[0] if term else ''
    return not (is_kana(c) or c.isascii())

# 読みが要る用語(記事名がある漢字始まり)。id → 記事名
targets = {}
for e in es:
    if needs_yomi(e[1]) and e[3]:  # e[1]=term, e[3]=wikiTitle
        targets[e[0]] = e[3]
print('読み対象', len(targets), file=sys.stderr)

out_path = f'{S}/yomi.json'
out = json.load(open(out_path)) if os.path.exists(out_path) else {}
todo = [(i, t) for i, t in targets.items() if str(i) not in out]
print('todo', len(todo), file=sys.stderr)

def kata_to_hira(s):
    return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in s)

def extract_yomi(text):
    # 「用語（よみ、...」または「用語（よみ）」パターン。読みはひらがな/カタカナ+中黒/空白。
    m = re.search(r'^[^（(]{0,40}[（(]([ぁ-んァ-ヶ・\s]+?)[、,；;）)]', text)
    if not m:
        return None
    y = re.sub(r'[\s]', '', m.group(1))
    y = kata_to_hira(y)
    if re.search(r'[ぁ-ん]', y) and len(y) >= 1:
        return y
    return None

def get(titles):
    params = {'action': 'query', 'format': 'json', 'formatversion': '2', 'titles': '|'.join(titles),
              'redirects': '1', 'prop': 'extracts', 'exintro': '1', 'explaintext': '1', 'exsentences': '1', 'exlimit': '20'}
    for i in range(5):
        try:
            req = urllib.request.Request(API + '?' + urllib.parse.urlencode(params), headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            print('retry', e, file=sys.stderr); time.sleep(3 * (i + 1))
    return None

# 記事名→id の逆引き(複数idが同じ記事名を指すことがある)
title_to_ids = {}
for i, t in todo:
    title_to_ids.setdefault(t, []).append(i)
uniq_titles = list(title_to_ids.keys())
for k in range(0, len(uniq_titles), 20):
    batch = uniq_titles[k:k + 20]
    data = get(batch)
    if not data:
        continue
    q = data['query']
    norm = {n['from']: n['to'] for n in q.get('normalized', [])}
    redir = {r['from']: r['to'] for r in q.get('redirects', [])}
    pages = {p['title']: p for p in q.get('pages', [])}
    for t in batch:
        t2 = redir.get(norm.get(t, t), norm.get(t, t))
        p = pages.get(t2)
        yomi = extract_yomi(p.get('extract', '')) if p else None
        if yomi:
            for i in title_to_ids[t]:
                out[str(i)] = yomi
    if k % 200 == 0:
        print(k, '/', len(uniq_titles), 'got', len(out), file=sys.stderr)
        json.dump(out, open(out_path, 'w'), ensure_ascii=False)
    time.sleep(0.25)
json.dump(out, open(out_path, 'w'), ensure_ascii=False)
print('done', len(out), '/', len(targets), file=sys.stderr)
