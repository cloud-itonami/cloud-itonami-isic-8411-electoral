# ADR-0001 — 受領側 actor のアーキテクチャ

- Status: accepted
- Date: 2026-08-07
- Supersedes: —
- Upstream: `com-junkawasaki/root` の ADR-2608079400（3 本立ての設計判断）

## Context

このワークスペースには政治団体側の actor（`cloud-itonami-isic-9492`）が
既にあったが、**その届出を受け取る側**が無かった。実測（2026-08-07）:

- `cloud-itonami-isic-8411`（一般行政）は汎用の case intake で、選挙管理を
  含まない。
- `cloud-itonami-isic-8412` / `8413` は規制官庁の事務支援だが、
  「許認可の決定はしない」を hard boundary にしており、選挙の受領も含まない。
- `kotoba-lang/ooyake` は選挙管理機関を **63 件**（世界 ~190 法域中）
  atlas に載せているが、read-only の civic wayfinding map であって
  手続きも様式も持たない。
- `cloud-itonami/moushibumi` は市民の民主参加 actor だが、選挙に関しては
  **INFO-ONLY**（G3 が campaigning / GOTV / endorsement を禁止）で、
  受領側でもない。

つまり「届出を出す側」と「その届出が置かれる法域の規制」は在ったが、
**受け取る側の governed な実装が無かった**。

## Decision

ISIC 8411 の electoral-administration サブ主題として、受領側 actor を
独立の repo で起こす。命名は既存の suffix 付き ISIC 命名先例
（`cloud-itonami-isic-8129-facade`、`-6910-legalsupport`、
`-6311-chainexplorer`、`-6611-cryptoexchange`）に従い
`cloud-itonami-isic-8411-electoral` とする。

### D1. 手続きカタログを 2 つ持たない

提出側（9492）と受領側（この repo）は **`kotoba-lang/senkyo` の同じ
`senkyo.procedure` entry** を読む。片方が「出すもの」として、もう片方が
「受け取るもの」として読むだけで、表そのものは 1 つ。

理由: 別々のカタログを持つと、陣営が教わる提出物と選管が受け取ると
決めているものが静かに食い違い、その食い違いは**候補者が技術的な理由で
権利を失う形でしか表面化しない**。テスト
`intake-test/same-table-as-the-filing-side` が `identical?` で同一 entry で
あることを固定する。

### D2. 期限は独立に再計算し、『確認できなかった』を『期限内』に丸めない

`senkyo.procedure/due-epoch-day` は 3 つの異なる値を返す ——
整数 / `:no-deadline`（この層では決まらない。米国の ballot access は州法）
/ `:unknown-anchor`（投票日が与えられていない）。

`electoralops.intake/blocking-deadline-statuses` は
`:past` `:unresolved` `:unknown-filing` `:no-basis` を**すべて**止め、
`:within` と `:no-deadline` だけを通す。

理由: `:unknown-anchor` を `:no-deadline` に丸めると「確認しなかった」が
「確認すべきものが無かった」に変わり、届出はそのまま台帳に載る。選挙の
期限は 1 日の誤りが失権に直結するので、ここは fail-closed にする。

基準日（`:anchors`）は actor の設定ではなく**実行の文脈**として渡す。
選挙の日付は選挙ごとに違い、前回の日付を抱えた actor は自信を持って
間違える。

### D3. 形式審査と実質判断の境界を語彙で切る

`electoralops.intake/out-of-scope-intents` は
`senkyo.screen/out-of-scope-intents` を**拡張**し（置換ではない）、
受領側に固有の裁定系 3 つを足す:

- `:acceptance-refusal` — 受理しない、という決定そのもの
- `:candidate-assessment` — 候補者・政党の実質評価
- `:result-determination` — 開票・当落の確定

これらは risk level で緩和されるゲートではなく、governor が他の何よりも
先に閉じた集合として検査する。**選挙の結果を左右する判断は人間の選挙
管理機関のもの**であり、この actor はその語彙を持たない。

### D4. 6 つの HARD check

`:no-procedure-basis` / `:wrong-receiving-authority` /
`:filing-deadline-not-satisfied` / `:formal-review-incomplete` /
`:out-of-scope-intent` / `:already-published`。人間の承認で通せない。

`:wrong-receiving-authority` は日本の供託（法務局が受ける）が具体的な
根拠になっている —— 受領者を間違えた届出は無効になりうるので、
「選管が受け取るもの」を `senkyo` の `:proc/received-by` から引く。

### D5. 台帳登載は auto にしない（恒久）

`:actuation/publish-receipt` はどの phase の `:auto` にも入らない。
`:review/formal` と `:deficiency/notice` も同様 —— auto で通る形式審査は
誰も読んでいない形式審査であり、不備通知は候補者に補正の負担を課す
外向きの行為で誤送は取り消せない。

auto 対象は `:filing/receive`（書類が届いたという記録）だけ。これは
誰の地位も変えない。

## Consequences

- 提出側と受領側の食い違いが**構造的に起きない**。ただしその代償として、
  この repo は `senkyo` の carrier 側のカバレッジに縛られる（5 法域 /
  14 手続き）。未収録法域では何も受理できない —— それが正しい挙動である。
- 期限の fail-closed により、基準日を渡し忘れた運用は全部 HOLD になる。
  運用者にとっては煩わしいが、静かに受理されるより良い。
- 実質判断を持たないので、この actor だけでは選挙は運営できない。
  **それが意図**であり、欠落ではない。

## Verification

- 33 tests / 160 assertions green（`clojure -M:test`）
- clj-kondo 0 errors / 0 warnings（`clojure -M:lint`）
- `clojure -M:dev:run` で clean 3 件 + HARD hold 5 件が実際に期待どおりの
  disposition になることを確認
- 落ちることの確認: `blocking-deadline-statuses` を `#{:past}` に縮め、
  `:actuation/publish-receipt` を phase 3 の `:auto` に入れると **7 件**
  失敗する
