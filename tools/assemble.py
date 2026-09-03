#!/usr/bin/env python3
"""エージェント出力 out/{i}.json と修正 out/{i}.*.fix.json を統合し、検証して entries.json を出力する。
periods(時代)は year から、regions(地域)は座標から機械分類する。世界史の窓の章節構成は使わない。"""
import json, os, sys, glob, math
SC = '/tmp/claude-1000/-home-takeshi-StudioProjects/ceef8fed-07ec-4c9e-95ce-fb1e0348866f/scratchpad'
OUT = sys.argv[1] if len(sys.argv) > 1 else f'{SC}/entries.json'
terms = {t['id']: t for t in json.load(open(f'{SC}/terms_full.json'))}
CATS = {'EVENT': 0, 'PERSON': 1, 'POLITY': 2, 'PLACE': 3, 'CULTURE': 4, 'CONCEPT': 5}

results = {}
for f in sorted(glob.glob(f'{SC}/out/*.json')):
    if '.fix.json' in f:
        continue
    try:
        data = json.load(open(f))
    except Exception as e:
        print('bad json', f, e, file=sys.stderr); continue
    items = data.get('items', data) if isinstance(data, dict) else data
    for it in items:
        if not isinstance(it, dict) or 'id' not in it:
            continue
        results[int(it['id'])] = it

fixes = 0
for f in sorted(glob.glob(f'{SC}/out/*.fix.json')):
    try:
        data = json.load(open(f))
    except Exception as e:
        print('bad fix json', f, e, file=sys.stderr); continue
    for fx in (data.get('fixes', data) if isinstance(data, dict) else data):
        if not isinstance(fx, dict) or 'id' not in fx:
            continue
        i = int(fx['id'])
        if i not in results:
            continue
        for k, v in (fx.get('set') or {}).items():
            results[i][k] = v; fixes += 1
print('results', len(results), 'of', len(terms), 'fixes applied', fixes, file=sys.stderr)
missing = [i for i in terms if i not in results]
print('missing outputs', len(missing), missing[:20], file=sys.stderr)


def to_int(v):
    if v is None or v == '':
        return None
    try:
        return int(v)
    except Exception:
        return None


# ---- 時代の機械分類(西暦年 → 区分) ----
PERIODS = ['先史', '古代', '中世', '近世', '近代', '現代', '時代不明']


def period_index(year):
    if year is None:
        return 6
    if year < -1000:
        return 0        # 先史(前1000年より前)
    if year < 476:
        return 1        # 古代(〜西ローマ滅亡)
    if year < 1453:
        return 2        # 中世(〜東ローマ滅亡)
    if year < 1789:
        return 3        # 近世(〜フランス革命)
    if year < 1914:
        return 4        # 近代(〜第一次世界大戦)
    return 5            # 現代


# ---- 地域の機械分類(緯度経度 → 区分) ----
REGIONS = [
    '東アジア', '東南アジア', '南アジア', '中央アジア', '西アジア',
    'ヨーロッパ', 'アフリカ', '南北アメリカ', 'オセアニア', 'その他',
]


def region_index(lat, lon):
    # 経度は -180..180。おおまかな矩形分類。
    if -60 <= lat <= 85:
        if 100 <= lon <= 150 and 20 <= lat <= 55:
            return 0    # 東アジア
        if 90 <= lon <= 145 and -12 <= lat <= 25:
            return 1    # 東南アジア
        if 65 <= lon <= 92 and 5 <= lat <= 37:
            return 2    # 南アジア
        if 45 <= lon <= 100 and 35 <= lat <= 55:
            return 3    # 中央アジア
        if 25 <= lon <= 65 and 12 <= lat <= 45:
            return 4    # 西アジア
        if -25 <= lon <= 45 and 34 <= lat <= 72:
            return 5    # ヨーロッパ
        if -20 <= lon <= 52 and -38 <= lat <= 38:
            return 6    # アフリカ
    if -170 <= lon <= -30 and -57 <= lat <= 72:
        return 7        # 南北アメリカ
    if 110 <= lon <= 180 and -50 <= lat <= 0:
        return 8        # オセアニア
    return 9            # その他


entries = []
problems = []
for i, t in sorted(terms.items()):
    r = results.get(i)
    if r is None:
        # 出力が無い用語は用語名だけで最小限のエントリにする(座標不明 → 表示座標は付けない=除外)
        problems.append((i, 'missing')); continue
    try:
        lat = float(r['lat']); lon = float(r['lon'])
    except Exception:
        problems.append((i, 'coord')); continue
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        problems.append((i, 'coord range')); continue
    cat = r.get('category')
    cat = CATS.get(cat, cat) if isinstance(cat, str) else cat
    if not isinstance(cat, int) or not 0 <= cat <= 5:
        cat = 5
    imp = to_int(r.get('importance')) or 2
    imp = min(3, max(1, imp))
    year = to_int(r.get('year'))
    year_end = to_int(r.get('yearEnd'))
    if year == 0:
        year = -1
    if year_end == 0:
        year_end = 1
    title = (r.get('wikiTitle') or '').strip()
    if r.get('titleMatch') == 'none':
        title = ''
    exact = 1 if not (title and r.get('titleMatch') == 'related') else 0
    entries.append([
        i, t['term'], '|'.join(t.get('aliases', [])), title, round(lat, 4), round(lon, 4),
        (r.get('place') or '').strip(), year, year_end, (r.get('era') or '').strip(), cat, imp,
        (r.get('desc') or '').strip(),
        period_index(year), region_index(lat, lon), exact,
    ])
print('entries', len(entries), 'problems', len(problems), problems[:20], file=sys.stderr)
json.dump({'periods': PERIODS, 'regions': REGIONS, 'entries': entries},
          open(OUT, 'w'), ensure_ascii=False, separators=(',', ':'))
print('wrote', OUT, os.path.getsize(OUT), file=sys.stderr)
