#!/usr/bin/env python3
"""era をパースして year/yearEnd を推定し、period_index を再計算して entries.json を更新する。
- year が既に有る項目は触らない(=元々時代が分かっている)。
- period_index=6(時代不明)かつ era パース可の項目に year/yearEnd/period を付与する。
- パース不可(era空・「-」)は yomi と同じく別途エージェント補完(era_fix.json があれば適用)。"""
import json, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from parse_era import parse_era
APP = '/home/takeshi/StudioProjects/sekaishi-earth-wikipedia/app/src/main/assets/entries.json'
S = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PERIODS = ['先史', '古代', '中世', '近世', '近代', '現代', '時代不明']

def period_index(year):
    if year is None:
        return 6
    if year < -1000:
        return 0
    if year < 476:
        return 1
    if year < 1453:
        return 2
    if year < 1789:
        return 3
    if year < 1914:
        return 4
    return 5

d = json.load(open(APP))
es = d['entries']
# エージェント補完(id→(year,yearEnd,era) or 世紀)があれば読み込む
fix = {}
fp = f'{S}/era_fix.json'
if os.path.exists(fp):
    fix = {int(k): v for k, v in json.load(open(fp)).items()}

updated = 0
for e in es:
    # index: 7=year, 8=yearEnd, 9=era, 13=periodIdx
    if e[13] != 6:
        continue  # 時代不明のみ対象
    if e[7] is not None:
        # year があるのに時代不明は稀。period を再計算だけする
        e[13] = period_index(e[7]); continue
    yr = None; yre = None; era = e[9]
    if e[0] in fix:
        v = fix[e[0]]
        yr, yre = v.get('year'), v.get('yearEnd')
        if v.get('era'):
            era = v['era']
    if yr is None:
        p = parse_era(e[9])
        if p:
            yr, yre = p
    if yr is None:
        continue  # まだ不明(era空など)。エージェント補完待ち
    if yr == 0:
        yr = -1
    if yre == 0:
        yre = 1
    e[7] = yr
    if yre is not None:
        e[8] = yre
    if era and not e[9]:
        e[9] = era
    e[13] = period_index(yr)
    updated += 1

json.dump(d, open(APP, 'w'), ensure_ascii=False, separators=(',', ':'))
from collections import Counter
print('更新', updated, file=sys.stderr)
print('period分布:', {PERIODS[i]: c for i, c in sorted(Counter(e[13] for e in es).items())}, file=sys.stderr)
print('残り時代不明:', sum(1 for e in es if e[13] == 6), file=sys.stderr)
