#!/usr/bin/env python3
"""y-history.net の50音順リストと章別目次から用語テーブル(terms_full.json)を作る。"""
import re, html, json, sys
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
clean = lambda x: html.unescape(re.sub(r'<[^>]+>', '', x)).replace('　', ' ').replace('\xa0', ' ').strip()

# ---- 章別目次 → href ごとの文脈 ----
t = open(f'{SC}/applist.html', encoding='utf-8', errors='replace').read()
t = re.sub(r'<script.*?</script>', '', t, flags=re.S)
body = t[t.find('<!-- mokuji 終わり -->'):]
ctx = {}
chap = sec = sub = None
order = 0
pat = re.compile(r'<h3[^>]*id="(wh\d{4})"[^>]*>(.*?)</h3>'
                 r'|<div id="(wh\d{4}(?:_\d)?)" class="mokuji_setu"[^>]*>(.*?)</div>'
                 r'|<dt[^>]*>(.*?)</dt>'
                 r'|<a href="(wh[^"]+)"[^>]*>(.*?)</a>', re.S)
for m in pat.finditer(body):
    if m.group(1):
        chap = (m.group(1), clean(m.group(2))); sec = None; sub = None
    elif m.group(3):
        sec = (m.group(3), clean(re.sub(r'<a target="_self".*', '', m.group(4), flags=re.S))); sub = None
    elif m.group(5):
        sub = clean(m.group(5))
    else:
        h = m.group(6); name = clean(m.group(7)); order += 1
        ctx.setdefault(h, {'chapter': chap, 'section': sec, 'sub': sub, 'name': name, 'order': order})
by_file = {}
for h, v in ctx.items():
    by_file.setdefault(h.split('#')[0], v)

def context_for(href):
    if href in ctx: return ctx[href]
    f = href.split('#')[0]
    if f in by_file: return by_file[f]
    return None

# ---- 50音順リスト ----
a = open(f'{SC}/aiueo.txt', encoding='utf-8').read()
s = a.find('<div id="m_mokuji">'); e = a.find('<div id="footer"', s)
body = a[s:e if e > 0 else len(a)]
lis = re.findall(r'<li>(.*?)</li>', body, flags=re.S)
terms = []
aliases = []  # (alias, href)
for li in lis:
    li = li.strip()
    if not li or 'href="wh' not in li: continue
    m = re.match(r'^(.*?)→\s*<a href="(wh[^"]+)"[^>]*>(.*?)</a>', li, flags=re.S)
    if m:
        aliases.append((clean(m.group(1)), m.group(2), clean(m.group(3))))
        continue
    m = re.search(r'<a href="(wh[^"]+)"[^>]*>(.*?)</a>', li, flags=re.S)
    if not m: continue
    name = clean(m.group(2))
    if not name: continue
    terms.append({'term': name, 'href': m.group(1)})
print('terms', len(terms), 'aliases', len(aliases), file=sys.stderr)

# 重複 (同名同href) 除去、同名別href は残す
seen = set(); out = []
for x in terms:
    k = (x['term'], x['href'])
    if k in seen: continue
    seen.add(k); out.append(x)
terms = out
# alias を紐付け: 同じ href を持つ用語があればそこへ、なければ独立用語として追加
by_href = {}
for x in terms: by_href.setdefault(x['href'], []).append(x)
for al, h, target in aliases:
    tgt = None
    for x in by_href.get(h, []):
        if x['term'] == target: tgt = x; break
    if tgt is None and by_href.get(h): tgt = by_href[h][0]
    if tgt is None and by_href.get(h.split('#')[0]): tgt = by_href[h.split('#')[0]][0]
    if tgt is None:
        x = {'term': al, 'href': h}; terms.append(x); by_href.setdefault(h, []).append(x); continue
    tgt.setdefault('aliases', [])
    if al != tgt['term'] and al not in tgt['aliases']: tgt['aliases'].append(al)

for i, x in enumerate(terms):
    x['id'] = i
    c = context_for(x['href'])
    x['url'] = 'https://www.y-history.net/appendix/' + x['href']
    if c:
        x['chapter'] = c['chapter'][1] if c['chapter'] else None
        x['chapterCode'] = c['chapter'][0] if c['chapter'] else None
        x['section'] = c['section'][1] if c['section'] else None
        x['sectionCode'] = c['section'][0] if c['section'] else None
        x['sub'] = c['sub']
        x['order'] = c['order']
    else:
        x['chapter'] = x['section'] = x['sub'] = None; x['order'] = 10**6
        x['chapterCode'] = x['sectionCode'] = None
# 目次に無い用語は href の章コードから推定
codes = {}
for h, v in ctx.items():
    if v['section']: codes.setdefault(v['section'][0], v)
for x in terms:
    if x['chapter'] is None:
        m = re.match(r'(wh\d{4}(?:_\d)?)', x['href'])
        if m and m.group(1) in codes:
            v = codes[m.group(1)]
            x['chapter'] = v['chapter'][1]; x['chapterCode'] = v['chapter'][0]
            x['section'] = v['section'][1]; x['sectionCode'] = v['section'][0]
json.dump(terms, open(f'{SC}/terms_full.json', 'w'), ensure_ascii=False, indent=0)
nc = sum(1 for x in terms if x['chapter'] is None)
print('final terms', len(terms), 'no-context', nc, file=sys.stderr)
print('with aliases', sum(1 for x in terms if x.get('aliases')), file=sys.stderr)
