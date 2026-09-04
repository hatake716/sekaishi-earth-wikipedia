#!/usr/bin/env bash
# level4 (32x16 tiles) を元画像から直接生成。中間画像なし。
# 元21600x10800を32列x16行に分割 → 各タイルは元の675x675領域を1024pxに拡大。
set -euo pipefail
SRC="$1"; OUT="$2"
L=4
COLS=32; ROWS=16
SW=21600; SH=10800
TW=$((SW / COLS)); TH=$((SH / ROWS))  # 675 x 675
mkdir -p "$OUT/$L"
echo "level4: 元${TW}x${TH}領域 → 1024px, $((COLS*ROWS)) tiles"
for ((y=0; y<ROWS; y++)); do
  for ((x=0; x<COLS; x++)); do
    echo "$((x*TW)) $((y*TH)) $TW $TH $OUT/$L/${x}_${y}.jpg"
  done
done | xargs -P 8 -n 5 sh -c 'ffmpeg -v error -y -i "'"$SRC"'" -vf "crop=$2:$3:$0:$1,scale=1024:1024:flags=lanczos" -q:v 4 "$4"'
echo "level4 tiles: $(find "$OUT/$L" -name '*.jpg' | wc -l)"
du -sh "$OUT/$L"
