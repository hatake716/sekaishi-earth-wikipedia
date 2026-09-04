# リリース手順(Google Play)

## 署名鍵

リリース署名はローカルの鍵で行います(**リポジトリには含めない**。紛失すると Play への更新ができなくなるため厳重に保管)。

- 鍵: `sekaishi-earth-release.jks`(プロジェクト直下、`.gitignore` 済み)
- 設定: `keystore.properties`(同、`.gitignore` 済み)
  ```
  storeFile=sekaishi-earth-release.jks
  storePassword=...
  keyAlias=sekaishiearth
  keyPassword=...
  ```
- 鍵の再生成が必要な場合:
  ```bash
  keytool -genkeypair -v -keystore sekaishi-earth-release.jks \
    -alias sekaishiearth -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass <pass> -keypass <pass> -dname "CN=hatake716, C=JP"
  ```

Google Play アプリ署名(Play App Signing)を利用する場合、この鍵は「アップロード鍵」として使えます。

## ビルド

```bash
# バージョンを更新(app/build.gradle.kts の versionCode/versionName)
./gradlew bundleRelease      # 署名済み AAB: app/build/outputs/bundle/release/app-release.aab
./gradlew assembleRelease    # 動作確認用の署名済み APK
```

R8 縮小・リソース縮小を有効にしています(`isMinifyEnabled = true` / `isShrinkResources = true`)。
JSON は `android.util.JsonReader` の手書きパーサのみ使用しており、リフレクション依存の keep ルールは不要です。

## Play Console 提出

### アプリの内容
- アプリ名: 地球儀で見る世界史wikipedia
- カテゴリ: 教育
- 対象年齢: 全年齢(暴力・性的表現なし)

### データセーフティ(Data safety)の記入
- **データ収集: なし**(個人情報・識別子・位置情報の収集・送信をしない)
- **データ共有: なし**
- 端末内保存(既読・ブックマーク)は「収集」に該当しない(端末外へ出ないため)
- Wikipedia / YouTube の記事・動画はアプリ内ブラウザで表示するため、それらの閲覧は各サービスへの通信になる旨を「アプリの機能」欄で説明できる

### プライバシーポリシー
- `docs/PRIVACY.md` の内容を公開 URL(GitHub Pages 等)に置き、その URL を Play Console に登録する。

### 権限
- `INTERNET`, `ACCESS_NETWORK_STATE` のみ。いずれも記事・動画のアプリ内表示に必要。機微な権限(位置情報・連絡先・カメラ等)は使用しない。

### コンテンツの出典
- 地図画像: NASA Blue Marble(パブリックドメイン)
- 解説・記事: Wikipedia 日本語版(CC BY-SA 4.0、アプリには本文を同梱せずブラウザで表示)
- 詳細は `NOTICE.md` を参照。

## 動作確認

- `assembleRelease` の APK を実機・エミュレータへインストールし、R8 縮小後もクラッシュしないこと、地球儀・検索・詳細・ブックマーク・年代フィルタが動くことを確認する。
