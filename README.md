# 地球儀で見る世界史wikipedia

Google Earth 風の 3D 地球儀の上に、**世界史の窓**の用語集(約 6,500 語)を
出来事・人物・国家などにゆかりのある座標へピンとして配置し、
タップすると **Wikipedia** の記事と世界史の窓の解説をアプリ内で読める Android アプリです。

## 特徴
- OpenGL ES による自前の地球儀レンダラ(NASA Blue Marble を 4 段階のタイルで同梱、オフラインで動作)
- ドラッグ回転・慣性・ピンチズーム・ダブルタップ拡大(Google Earth 風の「指の下の地点を保つ」操作)
- 6,000 語以上のピンを画面上で自動間引き・ラベル配置。ズームすると同じ場所の項目を円状に展開
- 用語検索(表記ゆれ・別名・地名に対応)、年代レンジ・分類・章による絞り込み、目次順/五十音順の一覧
- 詳細カードから Wikipedia(モバイル版)と世界史の窓の解説をアプリ内 WebView で表示

## ビルド
```bash
./gradlew assembleDebug
```
`local.properties` に `sdk.dir` を設定してください(compileSdk 36 / minSdk 30)。

## データ生成
`tools/` に用語一覧の取得・Wikipedia 記事の対応付け・タイル生成のスクリプトがあります。
`docs/DATA.md` を参照してください。

## ライセンス
- アプリ本体: MIT License(`LICENSE`)
- 同梱データ・画像の出典と条件: `NOTICE.md`
