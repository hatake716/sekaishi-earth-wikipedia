# データ生成の手順

アプリに同梱する `app/src/main/assets/entries.json`(用語 → Wikipedia 記事・座標・年代など)と
`app/src/main/assets/tiles/`(地球テクスチャ)の作り方。

## 1. 用語一覧の取得(`tools/build_terms.py`)

1. 世界史の窓「アイウエオ順リスト」 `appendix-list_aiueo.html` と章別目次 `appendix-list.html` を取得する。
2. 50 音順リストの `<li><a href="whXXXX-NNN.html">用語</a></li>` を全件抽出する(6,476 語)。
   「イワン雷帝→イヴァン４世」のような転送項目は本項目の別名(`aliases`)として統合する。
3. 章別目次から各 href の章・節・小見出し(「ア．十字軍とその影響」など)を紐付ける。
   出力: `terms_full.json`

## 2. Wikipedia / Wikidata の自動対応付け(`tools/wiki_map.py`)

- Phase 1: 用語名の表記ゆれ候補(全角→半角、「＝」→「・」、「ヴ」→「バ」、括弧除去、「／」分割)で
  `action=query&titles=...&redirects=1` を 50 件ずつ問い合わせ、完全一致記事・曖昧さ回避フラグ・Wikidata ID・座標を得る。
- Phase 2: 全用語について全文検索(`list=search`)の上位候補を集める。
  **検索 API は並列度を上げると 429 になる**ため 2 並列・0.15 秒待ちで実行する。
- Phase 3: Wikidata SPARQL で座標(P625 と、出生地/所在地/国/首都などの経由座標)と年代(P580/P582/P585/P571/P576/P569/P570)、分類(P31)を 80 件ずつ取得する。
  出力: `auto_map.json`

## 3. 世界史の窓の解説冒頭の取得(`tools/fetch_yhistory.py`)

各用語ページ(約 5,000 ページ)を 0.35 秒間隔で取得し、`<p class="lead">` と本文冒頭を抽出する。
**エージェントが主題を判断するための文脈にのみ使い、アプリには同梱しない。**

## 4. エージェントによる確定(Claude Code の Workflow)

`tools/prepare_batches.py` で 40 語ずつのバッチ(`batches/{i}.json`)を作り、バッチごとに

1. 編集者エージェント: 記事名 (`wikiTitle`, `titleMatch`)、座標 (`lat`, `lon`, `precision`, `place`)、
   年代 (`year`, `yearEnd`, `era`)、分類 (`category`)、重要度 (`importance`)、一行要約 (`desc`) を決定し `out/{i}.json` に書く。
   記事の存在は Wikipedia API で確認する。
2. 検証エージェント × 2(記事名の実在・曖昧さ回避・主題一致 / 座標・地名・年代・分類・要約の妥当性)が
   独立に監査し、修正を `out/{i}.title.fix.json` `out/{i}.geo.fix.json` に書く。

座標の規則: 出来事=発生地、戦い=戦場、条約=締結地、人物=主な活動地、国家=代表的な首都、
制度・概念=代表国の首都、民族=主な居住地。

## 5. 統合と最終検証

- `tools/assemble.py` で修正を適用して `entries.json` を生成する。
- `tools/validate_titles.py` で全記事名を API で再確認し、リダイレクトを正規題名へ置換、存在しない/曖昧さ回避の題名を `title_problems.json` に出す。
  問題が残った語は追加のワークフローで修正する。

## 6. 地球テクスチャ(`tools/make_tiles.sh`)

NASA Blue Marble Next Generation(`world.topo.bathy.200412.3x21600x10800.jpg`、パブリックドメイン)を
ffmpeg で 2048×1024 / 4096×2048 / 8192×4096 / 16384×8192 に縮小し、1024px 四方に切り出す。
`tiles/{level}/{x}_{y}.jpg`、level L は 2^(L+1) 列 × 2^L 行(合計 170 枚、約 23MB)。

## entries.json の形式

```json
{
  "chapters": ["序章 先史の世界", "１章 オリエントと地中海世界", ...],
  "sections": ["１節 古代オリエント世界", ...],
  "entries": [
    [id, "用語", "別名1|別名2", "Wikipedia記事名", lat, lon, "地名", year, yearEnd, "年代表示",
     category(0=出来事,1=人物,2=国家,3=地名,4=文化,5=概念), importance(1-3), "要約",
     chapterIndex, sectionIndex, "小見出し", order, "whXXXX-NNN.html"]
  ]
}
```
