# Contributing / 開発規約

このリポジトリは個人開発のポートフォリオとして公開しています（[LICENSE](./LICENSE)参照）。
外部からのプルリクエストは想定していませんが、開発時に使用しているルールを記録として残しています。

---

## コミットメッセージ

[Conventional Commits](https://www.conventionalcommits.org/) をベースに、
日本語と英語を1行で併記する形式を採用しています。

```
<type>: <日本語の要約> / <English summary>
```

### 主な type

| type | 内容 |
|---|---|
| `feat` | 新機能の追加 |
| `fix` | バグ修正 |
| `chore` | バージョン更新・設定変更などの雑務 |
| `docs` | ドキュメントの変更 |
| `refactor` | 動作を変えないコード整理 |

### 例

```
feat: 7z形式の展開に対応 / Add support for 7z archive extraction
fix: 7z展開時のOOMクラッシュを修正 / Fix OOM crash during 7z extraction
chore: バージョンを0.19.0に更新 / Bump version to 0.19.0
docs: READMEの謝辞部分を修正 / Fix wording in README acknowledgments section
```

---

## バージョニング

セマンティックバージョニングをベースに、以下のルールで運用しています。

| 変更内容 | 更新箇所 | 例 |
|---|---|---|
| バグ修正のみ | 右端（PATCH） | `0.18.0` → `0.18.1` |
| 機能追加あり | 中央（MINOR） | `0.18.x` → `0.19.0` |
| Google Play配信準備完了 | `v1.0.0` | — |

---

最終更新日：2026年7月7日
