#!/usr/bin/env bash
# NASA Blue Marble (21600x10800) → 正距円筒タイル tiles/{level}/{x}_{y}.jpg (1024px)
set -euo pipefail
SRC="$1"; OUT="$2"
mkdir -p "$OUT"
for L in 0 1 2 3; do
  COLS=$((2 << L)); ROWS=$((1 << L))
  W=$((COLS * 1024)); H=$((ROWS * 1024))
  mkdir -p "$OUT/$L"
  TMP="$OUT/level$L.ppm"
  echo "level $L: ${W}x${H} ($COLS x $ROWS)"
  ffmpeg -v error -y -i "$SRC" -vf "scale=${W}:${H}:flags=lanczos" -pix_fmt rgb24 "$TMP"
  for ((y=0; y<ROWS; y++)); do
    for ((x=0; x<COLS; x++)); do
      echo "$TMP $((x*1024)) $((y*1024)) $OUT/$L/${x}_${y}.jpg"
    done
  done | xargs -P 8 -n 4 sh -c 'ffmpeg -v error -y -i "$0" -vf "crop=1024:1024:$1:$2" -q:v 3 "$3"'
  rm -f "$TMP"
done
du -sh "$OUT"/*
find "$OUT" -name '*.jpg' | wc -l
