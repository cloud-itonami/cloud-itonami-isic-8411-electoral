(ns electoralops.intake
  "**受領側から見た手続き表** —— `kotoba-lang/senkyo` の `senkyo.procedure` を
  受け取る側の語彙で読む層。

  ## 提出側と受領側が同じ表を読む

  `cloud-itonami-isic-9492`（政治団体側）は同じ `senkyo.procedure` を
  「**何を出すか**」として読む。この repo は同じ entry を
  「**何を受け取り、何を形式審査し、いつが期限か**」として読む。

  **表を 2 つ持たない。** 提出側と受領側で別々のカタログを持つと、
  「陣営が出すべきと教わったもの」と「選管が受け取ると決めているもの」が
  静かに食い違い、その食い違いは誰にも見えない。片方を直してもう片方を
  忘れる、という事故の構造をそもそも作らない。

  ## この層が持たないもの

  規制・手続きの中身は持たない（`senkyo.procedure` の仕事）。ここは:

  1. その法域・その手続きが表にあるか
  2. 形式審査で何を見るか
  3. 期限を**独立に再計算**する

  だけを行う。**advisor が主張した期限や審査結果は読まない。**"
  (:require [senkyo.procedure :as procedure]
            [senkyo.screen :as screen]))

(def out-of-scope-intents
  "共有ライブラリ側の境界に、**受領側に固有の裁定系**を足したもの。

  `senkyo.screen/out-of-scope-intents` は運動側の境界（有権者ターゲティング等）
  で、そこには既に `:eligibility-adjudication`（被選挙権・有効投票・当落の裁定）が
  入っている。受領側で追加が要るのはそれ以外の**実質判断**である:

  - `:acceptance-refusal`   受理しない、という決定そのもの
  - `:candidate-assessment` 候補者・政党の実質評価
  - `:result-determination` 開票・当落の確定

  これらは risk level で緩和されるゲートではない。**選挙の結果を左右する判断は
  人間の選挙管理機関のものであって、この actor のものではない。**"
  (into screen/out-of-scope-intents
        #{:acceptance-refusal :candidate-assessment :result-determination}))

(defn procedure-basis
  "`iso3` / `procedure-id` の手続き entry。無ければ `nil`（spec-basis 無し）。"
  [iso3 procedure-id]
  (procedure/procedure iso3 procedure-id))

(defn receivable
  "この法域で**選挙管理機関・returning officer が受け取る側になる**手続き。
  未収録法域は `nil`。"
  [iso3]
  (procedure/received-by-election-authority iso3))

(defn receivable-here?
  "その手続きが、この actor が受領を扱ってよいものか。
  `:proc/received-by` が選管 / returning officer でないもの（日本の供託は
  法務局）は、この actor の受領対象ではない。"
  [iso3 procedure-id]
  (boolean (get (receivable iso3) procedure-id)))

(defn formal-review-items
  "その手続きの形式審査項目。表に無ければ `nil`。
  **空ベクタと nil を同じにしない** —— 「審査項目が無い」と「手続きを知らない」は
  違う。"
  [iso3 procedure-id]
  (:proc/formal-review (procedure-basis iso3 procedure-id)))

(defn due
  "提出期限を**独立に再計算**する。戻り値は `senkyo.procedure/due-epoch-day`
  そのまま: 整数 / `:no-deadline` / `:unknown-anchor`。

  **`:unknown-anchor` を `:no-deadline` に丸めない。** 基準日を知らないだけで
  あって、期限が無いわけではない。"
  [iso3 procedure-id anchor-epoch-days]
  (when-let [p (procedure-basis iso3 procedure-id)]
    (procedure/due-epoch-day p anchor-epoch-days)))

(defn deadline-status
  "提出の期限適合を判定する。

  - `:within`        期限内
  - `:past`          期限後
  - `:no-deadline`   この層では期限が決まらない（州法等）
  - `:unresolved`    基準日が与えられておらず解けない
  - `:unknown-filing` 提出日が記録されていない
  - `:no-basis`      手続きが表に無い"
  [{:keys [jurisdiction procedure-id submitted-epoch-day]} anchor-epoch-days]
  (let [d (due jurisdiction procedure-id anchor-epoch-days)]
    (cond
      (nil? d) :no-basis
      (= :no-deadline d) :no-deadline
      (= :unknown-anchor d) :unresolved
      (nil? submitted-epoch-day) :unknown-filing
      (<= submitted-epoch-day d) :within
      :else :past)))

(def blocking-deadline-statuses
  "受理を止めるべき期限判定。

  `:unresolved` / `:unknown-filing` / `:no-basis` も止める —— **期限を確認
  できないまま受理する**経路を作らない。`:no-deadline` は止めない（この層で
  決まらないことが判明している、という確定した答えだから）。"
  #{:past :unresolved :unknown-filing :no-basis})

(defn deadline-blocking? [status]
  (boolean (blocking-deadline-statuses status)))

(defn formal-review-complete?
  "形式審査が全項目埋まっているか。表に無い手続きは**never satisfied**
  （知らない手続きの審査が完了したとは言えない）。"
  [{:keys [jurisdiction procedure-id review-items-satisfied]}]
  (when-let [items (formal-review-items jurisdiction procedure-id)]
    (let [sat (set review-items-satisfied)]
      (every? sat items))))

(defn outstanding-review-items
  "まだ満たされていない形式審査項目。表に無ければ `nil`。"
  [{:keys [jurisdiction procedure-id review-items-satisfied]}]
  (when-let [items (formal-review-items jurisdiction procedure-id)]
    (let [sat (set review-items-satisfied)]
      (vec (remove sat items)))))

(defn any-out-of-scope
  "意図の集合から扱えないものを返す（空なら健全）。"
  [intents]
  (vec (sort (filter out-of-scope-intents intents))))

(defn coverage
  "senkyo 側の手続きカバレッジをそのまま返す（自前で数え直さない）。"
  []
  (procedure/coverage))
