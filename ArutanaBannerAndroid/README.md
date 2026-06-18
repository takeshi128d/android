# ARUTANA Banner Android Sample（枠ID 1339 / 99999）

ARUTANA API連携ガイド（iAEON向け）に基づく、**SDK非利用**のAndroidサンプルアプリです。
トップ面に2つのバナー枠（placementId **1339** と **99999**）を登録し、上部メニューバーと下部ナビを備えています。

接続先はテスト環境（staging）。枠1339は配信あり（バナー表示）、枠99999は設定が無ければ「配信なし」と表示され、空配信ハンドリングの確認になります。

## 動作フロー（各バナー枠で実行）

1. 広告枠設定YAML取得（`GET /placement-setting/{id}.yaml`、同日キャッシュ）
2. フリークエンシーキャップ判定（設定がある場合）
3. 広告取得API（`POST /v1/ad/1`、siteId等はYAMLの値）
4. `placements[0].ads[0]` を採用（空配列は配信なし→枠を隠す）
5. `creative.mainImageUrl` を表示（アスペクト比保持）
6. 表示反映後に `trackers.imp` を全件・1回だけ発火（viewable/inviewは未送信）
7. タップで `link.url` をアプリ内ブラウザ（Chrome Custom Tabs）で開く

トップ面アクセス時（`onResume`）と、上部の更新ボタン／下部「ホーム」タップで再取得します。

## APKの入手（GitHub Actionsでクラウドビルド）

ローカルにAndroid SDKは不要です。GitHubにこのプロジェクトを上げると、自動でAPKがビルドされます。

1. GitHubで新しいリポジトリを作成（Private可）
2. このフォルダ一式をアップロード
   - 簡単な方法: リポジトリ画面の「Add file ▸ Upload files」で、このフォルダの中身をドラッグ＆ドロップ（`.github` フォルダも含めること）
   - または git に慣れていれば: `git init && git add . && git commit -m init && git branch -M main && git remote add origin <repoのURL> && git push -u origin main`
3. リポジトリの「Actions」タブを開く（初回は緑のボタンでワークフロー実行を有効化）
4. 「Build APK」ワークフローが自動で走る（数分）。手動なら「Run workflow」でも可
5. 完了後、その実行結果ページ下部の「Artifacts」から `arutana-sample-debug-apk` をダウンロード
6. zipを解凍すると `app-debug.apk` が入っています

### スマホへのインストール

1. `app-debug.apk` をAndroid端末に転送（メール/クラウド/USB等）
2. ファイルをタップ。初回は「提供元不明のアプリ」のインストール許可を求められるので許可
3. インストール後アプリを起動 → トップ面で枠1339のバナーが表示され、枠99999は配信状況に応じて表示/「配信なし」

> デバッグ版APKは署名や開発者アカウント不要でそのままインストールできます。

## ローカルでビルドする場合（任意）

Android Studio（無料）で本フォルダを開き、SDKセットアップ後に「Run」。または:

```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# 生成物: app/build/outputs/apk/debug/app-debug.apk
```

## 主な構成

- `ArutanaClient.kt` … YAML取得・広告取得API・imp発火・画像取得（HttpURLConnection＋coroutines、外部HTTPライブラリ不使用）
- `BannerView.kt` … バナー表示View（imp発火1回制御・Custom Tabsでクリック遷移）
- `MainActivity.kt` … トップ面（メニューバー＋下部ナビ＋2バナー枠）
- `FrequencyCapManager.kt` … 表示回数制御
- `.github/workflows/build-apk.yml` … クラウドビルド設定

## 設定の変更

- 枠IDは `MainActivity.kt` の `bind(..., 1339, ...)` / `bind(..., 99999, ...)` を変更
- 本番接続は `ArutanaClient.kt` の `CONTENTS_BASE` / `AD_BASE` を本番URLへ
- `uid` は `MainActivity.kt` の `uid` を変更（未指定時は端末ID(ANDROID_ID)をdid/uidに使用）
