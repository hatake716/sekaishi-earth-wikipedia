# データ生成の手順

アプリに同梱する `app/src/main/assets/entries.json`(用語 → Wikipedia 記事・座標・年代など)と
`app/src/main/assets/tiles/`(地球テクスチャ)の作り方。

用語の見出し語(名称)以外の内容 — 解説文・座標・年代・分類 — はすべて **Wikipedia / Wikidata**
の情報から生成する。

## 1. 用語一覧の取得(`tools/build_terms.py`)

収録する用語(見出し語)の名称の一覧を作る。用語名は歴史上の事物・人物・出来事の一般的な名称。
表記ゆれ(別名)を統合し、`terms_full.json` を出力する。

## 2. Wikipedia / Wikidata の自動対応付け(`tools/wiki_map.py`)

- 用語名の表記ゆれ候補(全角→半角、「＝」→「・」、「ヴ」→「バ」、括弧除去、「／」分割)で
  Wikipedia API に問い合わせ、完全一致記事・曖昧さ回避フラグ・Wikidata ID・座標を得る。
- 全文検索(`list=search`)の上位候補も集める。**検索 API は 2 並列・0.15 秒待ち**(並列を上げると 429)。
- Wikidata SPARQL で座標(P625 と、出生地/所在地/国/首都などの経由座標)と年代(P580/P582/P585/P571/P576/P569/P570)、分類(P31)を取得する。
  出力: `auto_map.json`

## 3. Wikipedia 記事冒頭の取得(`tools/fetch_wiki_extracts.py`)

各用語の見込み記事について、Wikipedia API の `extracts`(記事冒頭のプレーンテキスト)と
記事座標・曖昧さ回避フラグ・リダイレクト先を取得する。**この記事冒頭が解説文(desc)・分類・年代の一次資料**になる。
出力: `wiki_extracts.json`

## 4. エージェントによる確定(Claude Code の Workflow: `curate-wiki`)

`tools/prepare_batches.py` で 40 語ずつのバッチ(`batches/{i}.json`)を作る。各用語には
候補記事名とその Wikipedia 記事冒頭(extract)、Wikidata の座標・年代を添える。バッチごとに

1. 編集者エージェント: extract を読んで主題に合う記事を選び、記事名 (`wikiTitle`, `titleMatch`)、
   座標 (`lat`, `lon`, `precision`, `place`)、年代 (`year`, `yearEnd`, `era`)、分類 (`category`)、
   重要度 (`importance`)、一行要約 (`desc`, Wikipedia の内容を自分の言葉で 60 字以内) を決めて `out/{i}.json` に書く。
2. 検証エージェント × 2(記事名の実在・曖昧さ回避・主題一致 / 座標・地名・年代・分類・要約の妥当性)が
   独立に監査し、修正を `out/{i}.title.fix.json` `out/{i}.geo.fix.json` に書く。

座標の規則: 出来事=発生地、戦い=戦場、条約=締結地、人物=主な活動地、国家=代表的な首都、
制度・概念=代表国の首都、民族=主な居住地。

## 5. 統合と最終検証

- `tools/assemble.py` で修正を適用し、時代(`year` から機械分類)と地域(座標から機械分類)を計算して `entries.json` を生成する。
- `tools/validate_titles.py` で全記事名を API で再確認し、リダイレクトを正規題名へ置換、存在しない/曖昧さ回避の題名を `title_problems.json` に出す。
  問題が残った語は追加のワークフローで修正する。

## 6. 地球テクスチャ(`tools/make_tiles.sh`)

NASA Blue Marble Next Generation(`world.topo.bathy.200412.3x21600x10800.jpg`、パブリックドメイン)を
ffmpeg で 2048×1024 / 4096×2048 / 8192×4096 / 16384×8192 に縮小し、1024px 四方に切り出す。
`tiles/{level}/{x}_{y}.jpg`、level L は 2^(L+1) 列 × 2^L 行(合計 170 枚、約 23MB)。

## entries.json の形式

```json
{
  "periods": ["先史", "古代", "中世", "近世", "近代", "現代", "時代不明"],
  "regions": ["東アジア", "東南アジア", "南アジア", "中央アジア", "西アジア", "ヨーロッパ", "アフリカ", "南北アメリカ", "オセアニア", "その他"],
  "entries": [
    [id, "用語", "別名1|別名2", "Wikipedia記事名", lat, lon, "地名", year, yearEnd, "年代表示",
     category(0=出来事,1=人物,2=国家,3=地名,4=文化,5=概念), importance(1-3), "要約(Wikipedia由来)",
     periodIndex, regionIndex, exactTitle(1=用語そのものの記事,0=関連記事)]
  ]
}
```
