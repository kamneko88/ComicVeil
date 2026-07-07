# Comic Veil

Android向けのマンガ・コミックビューワーアプリです。
NAS（自宅サーバー）上のファイルをストリーミングで直接閲覧できることを軸に、
「触らなくても快適、触ればもっと自分好みに」をコンセプトに開発しています。

iOS用ビューワーアプリ **Comic Glass** の操作性・設計思想に強くインスパイアされています。
本アプリはその世界観をAndroidでも実現することを目指した個人開発プロジェクトです。

> 🚧 開発中（Phase 1）：Google Play未公開。現在は野良APKでの個人利用を想定した段階です。

---

## スクリーンショット

準備中（実機での撮影後に追加予定）

---

## 主な機能

| 機能 | 内容 |
|---|---|
| ファイルブラウザ | ローカルフォルダ / NAS（SMB接続）を「場所」として統一的に表示 |
| ストリーミング閲覧（STRモード） | NASのファイルをダウンロードせずに直接閲覧。ZIPは1ページ目から即表示 |
| ダウンロードモード（DLモード） | 複数選択してまとめてローカルに保存 |
| ページ送り・ズーム | スワイプ・ピンチズーム・ダブルタップズーム（倍率設定可） |
| ブックマーク | 任意のページを登録・一覧からジャンプ |
| 読書状態管理 | 未読／読書中／既読を自動判定・手動変更にも対応 |
| レーティング・カラーラベル | Lightroomライクな★評価・5色のカラーラベル |
| ソート・フィルター | 名前／更新日／レーティングでソート、状態・ラベルで絞り込み |
| サムネイル自動生成 | ローカル・NASどちらのファイルもキャッシュ付きで高速表示 |
| 他アプリからの受け取り | ファイルマネージャー等から「Comic Veilで開く」で直接起動 |

---

## 対応フォーマット

| 形式 | 拡張子 | 状態 |
|---|---|---|
| ZIP / CBZ | `.zip` `.cbz` | ✅ 対応（Shift-JIS・Progressive Loading対応） |
| RAR / CBR | `.rar` `.cbr` | ✅ 対応 |
| 7-Zip | `.7z` | ✅ 対応 |
| PDF | `.pdf` | ✅ 対応 |
| パスワード付きZIP | `.zip` | ⚠️ ローカルファイルのみ対応（NAS経由は現状未対応） |

---

## 技術スタック

| 用途 | ライブラリ・技術 |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| 画像表示 | Coil 3 |
| ZIP / 7z 展開 | Apache Commons Compress |
| RAR展開 | junrar |
| パスワード付きZIP | zip4j |
| SMB接続（NAS） | SMBJ |
| データベース | Room（SQLite） |
| 最小SDK | Android 8.0（API 26） |

---

## プロジェクト構成

```
app/src/main/java/com/kamneko88/comicveil/
├── data/            # データ層（Room・NAS・ファイル・設定）
│   ├── db/          # 読書位置・ブックマーク・ファイルメタデータ
│   └── nas/         # SMB接続・NASサーバー設定
├── ui/
│   ├── home/        # ファイルブラウザ画面
│   ├── viewer/      # 閲覧画面（ページ送り・ズーム）
│   ├── settings/    # 設定画面
│   └── transfer/    # ダウンロード転送状況画面
└── MainActivity.kt
```

---

## ビルド方法

1. Android Studio でこのリポジトリを開く
2. Gradle Sync を実行（`org.tukaani:xz` など依存ライブラリが自動取得されます）
3. 実機または API 26 以上のエミュレータで実行

```bash
git clone https://github.com/kamneko88/ComicVeil.git
```

開発時のコミット規約・バージョニングルールは [`CONTRIBUTING.md`](./CONTRIBUTING.md) を参照してください。

---

## 開発状況

現在 Phase 1（マンガビューワーとしての完成）を進行中です。

**実装済み**：ファイルブラウザ・NAS接続・ZIP/RAR/7z/PDF対応・ズーム機能・ブックマーク・レーティング・ソート/フィルター・読書状態管理 など

**未実装・既知の課題**：

- プリセット機能（設定の一括切り替え）
- 見開き表示・余白トリミング
- 圧縮ファイル内のサブフォルダ非対応
- パスワード付きZIPのNAS経由ストリーミング非対応
- Google Play向けのスコープドストレージ対応（SAF移行）

より詳細な仕様は [`spec.md`](./spec.md) を参照してください。

---

## 謝辞

本アプリはiOS用ビューワーアプリ **Comic Glass** のUI/UXから大きな影響を受けています。
素晴らしい操作体験を提供してくださった開発者様に敬意を表します。

以下のOSSライブラリを使用させていただいています：
[Coil](https://github.com/coil-kt/coil) ·
[Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) ·
[junrar](https://github.com/junrar/junrar) ·
[zip4j](https://github.com/srikanth-lingala/zip4j) ·
[SMBJ](https://github.com/hierynomus/smbj)

---

## ライセンス

[`LICENSE`](./LICENSE) を参照してください。ポートフォリオ・デモンストレーション目的での公開であり、
著作権者の明示的な許可なく本ソフトウェアを使用・複製・改変・再配布することはできません。

---

最終更新日：2026年7月7日
