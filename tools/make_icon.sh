#!/usr/bin/env bash
# NASA Blue Marble から正射投影のリアルな地球儀アプリアイコンを生成する。
# 依存: ffmpeg, python3(標準ライブラリのみ)。SRC=元画像(21600x10800), OUT=res ディレクトリ。
set -euo pipefail
SRC="${1:?元画像パス}"; RES="${2:?res ディレクトリ}"; TMP=$(mktemp -d)
ffmpeg -v error -y -i "$SRC" -vf "scale=4096:2048,format=rgb24" -f rawvideo "$TMP/src.rgb"
python3 - "$TMP" <<'PY'
import math,sys
T=sys.argv[1]; SW,SH=4096,2048
src=open(f'{T}/src.rgb','rb').read()
def s(lon,lat):
    u=(lon+math.pi)/(2*math.pi); v=(math.pi/2-lat)/math.pi
    x=min(SW-1,max(0,int(u*SW))); y=min(SH-1,max(0,int(v*SH))); i=(y*SW+x)*3
    return src[i],src[i+1],src[i+2]
N=720; R=N/2; lat0=math.radians(20); lon0=math.radians(110)
o=bytearray(N*N*4)
for py in range(N):
    for px in range(N):
        dx=(px-R+.5)/R; dy=-(py-R+.5)/R; rho=math.hypot(dx,dy); idx=(py*N+px)*4
        if rho>1: o[idx:idx+4]=bytes((0,0,0,0)); continue
        c=math.asin(min(1,rho)) if rho>0 else 0; sc=math.sin(c); cc=math.cos(c)
        if rho==0: lat,lon=lat0,lon0
        else:
            lat=math.asin(cc*math.sin(lat0)+dy*sc*math.cos(lat0)/rho)
            lon=lon0+math.atan2(dx*sc, rho*math.cos(lat0)*cc-dy*math.sin(lat0)*sc)
        r,g,b=s(lon,lat); sh=.60+.40*cc; a=255
        if rho>.985: a=int(255*(1-(rho-.985)/.015))
        o[idx]=min(255,int(r*sh)); o[idx+1]=min(255,int(g*sh)); o[idx+2]=min(255,int(b*sh)); o[idx+3]=max(0,a)
open(f'{T}/globe.rgba','wb').write(bytes(o))
PY
ffmpeg -v error -y -f rawvideo -pix_fmt rgba -s 720x720 -i "$TMP/globe.rgba" "$TMP/globe.png"
# foreground(地球中央), background(宇宙), legacy(合成)
ffmpeg -v error -y -i "$TMP/globe.png" -vf "pad=1024:1024:152:152:color=0x00000000" "$TMP/fg.png"
ffmpeg -v error -y -f lavfi -i "color=0x060B1A:s=1024x1024" -vf "geq=r='clip(8+18*(1-hypot(X-512,Y-440)/720),4,40)':g='clip(12+24*(1-hypot(X-512,Y-440)/720),6,50)':b='clip(28+40*(1-hypot(X-512,Y-440)/620),18,90)'" "$TMP/bg.png"
ffmpeg -v error -y -i "$TMP/bg.png" -i "$TMP/globe.png" -filter_complex "[0][1]overlay=152:152" "$TMP/legacy.png"
# 配置
declare -A D=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
for d in "${!D[@]}"; do mkdir -p "$RES/mipmap-$d"; ffmpeg -v error -y -i "$TMP/legacy.png" -vf "scale=${D[$d]}:${D[$d]}:flags=lanczos" "$RES/mipmap-$d/ic_launcher.png"; cp "$RES/mipmap-$d/ic_launcher.png" "$RES/mipmap-$d/ic_launcher_round.png"; done
mkdir -p "$RES/drawable"
ffmpeg -v error -y -i "$TMP/fg.png" -vf scale=432:432:flags=lanczos "$RES/drawable/ic_launcher_globe_fg.png"
ffmpeg -v error -y -i "$TMP/bg.png" -vf scale=432:432:flags=lanczos "$RES/drawable/ic_launcher_globe_bg.png"
rm -rf "$TMP"; echo "icon generated into $RES"
