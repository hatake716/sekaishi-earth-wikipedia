#!/usr/bin/env python3
"""世界史の窓の用語ページを礼儀的な速度で取得し、カードごとの見出し・リード文・本文冒頭を抽出する。
出力: yh_cards.json  { "wh1103_2-026.html": [ {id, title, lead, body}, ... ], ... }
本文は文脈把握(エージェントの参考)にのみ使い、アプリには同梱しない。"""
import json, re, html, sys, time, urllib.request, os
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
UA = 'sekaishi-earth-wikipedia-builder/0.1 (+https://github.com/hatake716/sekaishi-earth-wikipedia; acesmash@gmail.com)'
terms = json.load(open(f'{SC}/terms_full.json'))
files = sorted({t['href'].split('#')[0] for t in terms})
out_path = f'{SC}/yh_cards.json'
out = json.load(open(out_path)) if os.path.exists(out_path) else {}
clean = lambda x: html.unescape(re.sub(r'<[^>]+>', '', x)).replace('　', ' ').replace('\xa0', ' ')
clean = lambda x, c=clean: re.sub(r'\s+', ' ', c(x)).strip()

def parse(t):
    cards = []
    for m in re.finditer(r'<div class="card"[^>]*id="([^"]+)"[^>]*>(.*?)(?=<div class="card"|<!-- 章節別リストへ戻る|<div class="non_print">)', t, flags=re.S):
        cid, body = m.group(1), m.group(2)
        h = re.search(r'<h3[^>]*>(.*?)</h3>', body, flags=re.S)
        lead = re.search(r'<p class="lead">(.*?)</p>', body, flags=re.S)
        note = re.search(r'<div class="note-style">(.*?)$', body, flags=re.S)
        cards.append({'id': cid, 'title': clean(h.group(1)) if h else '', 'lead': clean(lead.group(1)) if lead else '',
                      'body': clean(note.group(1))[:500] if note else ''})
    return cards

todo = [f for f in files if f not in out]
print('files', len(files), 'todo', len(todo), file=sys.stderr)
fail = 0
for i, f in enumerate(todo):
    url = 'https://www.y-history.net/appendix/' + f
    try:
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            t = r.read().decode('utf-8', errors='replace')
        out[f] = parse(t)
    except Exception as e:
        fail += 1
        out[f] = None
        print('fail', f, e, file=sys.stderr)
        time.sleep(2)
    if i % 100 == 0:
        print(i, '/', len(todo), 'fail', fail, file=sys.stderr)
        json.dump(out, open(out_path, 'w'), ensure_ascii=False)
    time.sleep(0.35)
json.dump(out, open(out_path, 'w'), ensure_ascii=False)
print('done', len(out), 'fail', fail, file=sys.stderr)
