# CLAUDE.md - ComicVeil Project Guidelines

**ComicVeil（コミックヴェール）** は、自炊・購入した蔵書を★評価とカラーラベルで整理できる
Android向けコミックビューアです。ローカルフォルダに加えてNAS（SMB）上のコミックファイルを
直接閲覧でき、ZIP/CBZ/RAR/CBR/7z/PDFなど複数の圧縮形式に対応します。

開発者ブランドは **Kamneko Labo（かむねこラボ）**。

---

## 作業フロー（重要・必ず守る）

このプロジェクトは以下の分担で進行します。

1. 統括のClaude（別チャット・あつのりさんと直接対話する担当）が設計・指示をまとめ、
   Claude Code用のプロンプトを作成する
2. **Claude Code（あなた）は、指示を受けたら実装方針を簡潔に提示し、承認を得てから実装する**
3. 実装後は動作確認の上、コミット・プッシュまでをClaude Code側で行う
   （`git push` はリモート資格情報の都合で失敗する環境がある。その場合はコミットまで済ませ、
   あつのりさんにpushを依頼すること）
4. 進捗ドキュメント（`_local/ComicVeil_Dev_note.md` および
   `D:\Data\10_Projects\dev\開発状況サマリー.md`）の更新は統括のClaude側が行う。
   **Claude Codeはこれらのドキュメントを編集しない**

**指示を受けたら即座にコードを書き始めないこと。**
不明点・矛盾・より良い代案があれば、実装前に必ず指摘してください。

### ★指示と実際のコードが食い違うとき

> **指示が実際のコードと矛盾する場合、回避策を実装して辻褄を合わせない。**
> **実装を止め**、根拠（該当ファイルと行番号）を添えて報告する。

**「報告する」と「手を止める」は別物です。** 報告しながら自分の判断で進めてしまうと、
指示側は誤りに気づけず、**動くけれど意図と違うものが出来上がります。**
しかもテストは通ることが多いため、後から発見するのが困難になります。

**force push・git履歴の書き換え・ファイル削除・破壊的操作は、必ず事前に確認を取ってから実行すること。**

応答は**日本語**で行ってください。

---

## 共通ドキュメント（`D:\Data\10_Projects\dev\` 直下）

| ファイル | いつ見るか |
|---|---|
| `_CROSS_PROJECT_TASKS.md` | **セッション開始時**（横断タスクの回覧板） |
| `_WORKFLOW.md` | 作業サイクルごと（役割分担・Claude Codeへの指示・レビュー） |
| `_SAFETY.md` | 常に守る衛生・安全 |
| `_TESTING.md` | テスト設計の指針 |
| `_PUBLISH_CHECKLIST.md` | 公開の直前 |
| `_PROJECT_SETUP.md` | 立ち上げ時に一度だけ |
| `_IDEAS.md` | アイデア置き場 |
| `開発状況サマリー.md` | 全プロジェクト横断の状況一覧 |

※ 旧 `dev/開発運用ルール.md` は削除され、内容は `_WORKFLOW.md` / `_SAFETY.md` /
`_PUBLISH_CHECKLIST.md` の3つへ分割されています。

---

## Environment

- OS: Windows 11
- プロジェクトルート: `D:\Data\10_Projects\dev\ComicVeil`
- `_local/` はGit管理対象外（開発ログ・引き継ぎメモ置き場。`ComicVeil_Dev_note.md`・`ComicVeil_Handoff.md`を格納）
- `spec.md` は公開リポジトリのトラッキング対象（`_local/`には移動しない）
- GitHub: `kamneko88/ComicVeil`（**Public**。README.md / LICENSE / CONTRIBUTING.md / spec.md 整備済み）
- パッケージ名: `com.kamneko88.comicveil`

---

## 技術スタック

| 項目 | 内容 |
|------|------|
| 言語・UI | Kotlin / Jetpack Compose（Material3） |
| データベース | Room |
| NAS接続（SMB） | SMBJ（`com.hierynomus:smbj`） |
| 共有フォルダ一覧取得 | rapid7 dcerpc（srvsvc/MSRPC） |
| ZIP・7z展開 | Apache Commons Compress + tukaani xz |
| RAR展開 | junrar |
| パスワード付きZIP対応 | zip4j |
| libarchive | RAR5対応調査のPoC検証用 |
| ローカルファイルアクセス | SAF（Storage Access Framework） / DocumentFile |
| minSdk | 26 |
| compileSdk / targetSdk | 36 |
| 署名鍵 | `comicveil-release.jks`（CalenDaiとは別の専用鍵。**絶対にコミットしない**） |

---

## Google Play / ビルドに関する厳格なルール

### AABビルド手順（順番厳守）

```
1. app/build.gradle.kts の versionCode・versionName を更新
   （バグ修正のみ→右端+1／機能追加→中央+1／Play Store配信準備完了→v1.0.0）
2. Android Studioで「Generate Signed App Bundle or APK」→ AABを選択
   → comicveil-release.jks を指定 → release選択 → Create
3. エミュレーターまたは実機でバージョン表示・対象機能を確認
4. Play Console（クローズドテストトラック）にAABをアップロード → リリースノート記入 → 審査に送信
```

- 署名鍵 `*.jks`・`*.keystore`・`app/release/`・`**/build/` は絶対にコミットしない（`.gitignore`で除外済み）
- コミット時は `git add <対象ファイル>` で個別指定すること。
  `git add -A` / `git commit -a` は使わない
  （改行コード（CRLF/LF）だけの差分が大量に混入しているファイルが存在するため、
  意図しないファイルまでコミットしてしまうリスクがある）
- コミットメッセージ形式：`<type>: 日本語要約 / English summary`（詳細はCONTRIBUTING.md）

### バージョン管理ルール

| 種別 | 上げ方 |
|------|--------|
| バグ修正のみ | 右端（例: 0.38.0 → 0.38.1） |
| 機能追加 | 中央（例: 0.38.0 → 0.39.0） |
| Play Store配信準備完了 | v1.0.0 |

---

## 実機・審査で踏んだ落とし穴（必読）

- **LazyListStateの使い回し**：NAVスタックを使わず同一コンポーザブルでフォルダ遷移を扱う画面では、
  `rememberLazyListState()`をロケーションキーで`remember`しないと、フォルダ切り替え時に
  前のフォルダのスクロール位置が引き継がれてしまう（HomeScreen.ktで実際に発生・修正済み）
- **SAFの制約**：「ダウンロード」等の特殊フォルダは、ルート自体を`ACTION_OPEN_DOCUMENT_TREE`で
  選択できない（サブフォルダのみ選択可能）
- **`InputStream.readNBytes(int)`はAPI 33以降専用**：minSdk 26環境で使うとAPI 26〜32でクラッシュする。
  独自の`readUpTo()`拡張関数で代替すること
- **`UnusedMaterial3ScaffoldPaddingParameter`のLint警告**：Scaffoldのcontentラムダの引数名を`_`に
  リネームしても警告は消えない。`@Suppress("UnusedMaterial3ScaffoldPaddingParameter")`を
  該当コンポーザブル関数に付与するのが正しい対処
- **Samsung「マイファイル」アプリの.zip自動解凍**：.zip拡張子のファイルをタップすると、
  Intent選択（他のアプリで開く）を経由せず内蔵の解凍機能が直接起動してしまう
  （Galaxy機種共通の仕様）。外部配布するテスト用ファイル等は`.cbz`拡張子にすることで回避できる
- **フォアグラウンドサービス権限の申告**：Play Console審査で`FOREGROUND_SERVICE_DATA_SYNC`等を
  申告する場合、カテゴリ（ネットワーク処理／ローカル処理／その他）を問わずデモ動画のリンクが必須
- **Google Playアプリカテゴリ**：「コミック」というカテゴリは存在しない。ComicVeilは「ツール」で申請

---

## コーディング規約

- クリーンな設計・保守性・段階的な実装を重視する
- コードは常にファイル単位で完全に動作する形にする
- マジックナンバーを避け、意味を持つ数値は定数として定義する
- 未使用になったコンポーネント・importは放置せず、気づいた時点で整理する

---

## 現在の状態（2026-08-01時点）

- バージョン：v0.38.0（versionCode 58）でクローズドテスト審査中
- v0.38.1（Lint修正・`readNBytes`クラッシュ修正含む）のビルド・アップロードが次回作業として保留中
- クローズドテスター12名中、全員のオプトインはまだ完了していない
- 詳細な作業履歴・引き継ぎ事項は `_local/ComicVeil_Dev_note.md` を参照
