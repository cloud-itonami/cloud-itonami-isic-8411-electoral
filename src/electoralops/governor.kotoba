(ns electoralops.governor
  "Electoral Administration Governor —— 受領側の独立した審査層。

  ElectoralOps-LLM は、その法域でその届出を**誰が受け取ることになっているか**、
  法定期限が**いつ**か、形式審査で**何を見る**ことになっているかを知らない。
  だからこれは、提案を**拒否して HOLD に落とせる別系統**でなければならない
  —— 政治団体側 `cloud-itonami-isic-9492` の Political Organization
  Governance Governor と同じ構造である。

  受領側に固有の危うさは 2 つある。

  **1. 期限を『確認できなかった』ことを『期限内』に丸める誘惑。** 選挙の
  手続きは期限徒過が失権に直結するので、基準日が与えられていない・提出日が
  記録されていない・手続きが表に無い、のいずれも受理を止める。
  `senkyo.procedure` が `:unknown-anchor` を `:no-deadline` と別の値にして
  いるのは、まさにここで丸められないようにするためである。

  **2. 受領を実質判断へ滑らせる誘惑。** 形式審査（書類が揃っているか）と
  実質判断（この人が候補者になれるか、この票が有効か、誰が当選したか）は
  違う。後者は人間の選挙管理機関のものであって、この actor はその語彙を
  そもそも持たない。

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them.

    1. Procedure spec-basis     -- `senkyo.procedure` にその法域・その
                                   手続きがあるか。無ければ受領要件を
                                   創作してはならない。
    2. Wrong receiving authority -- その手続きを受け取るのは選管か。
                                   日本の供託（法務局）のように、
                                   選管が受領者でない手続きをこの actor が
                                   受理してはならない。
    3. Deadline                 -- filing 自身の `:submitted-epoch-day` と
                                   `senkyo` から**再計算した**期限を突き合わせる。
                                   `:past` はもちろん `:unresolved` /
                                   `:unknown-filing` / `:no-basis` も HOLD。
                                   提案が主張した期限は読まない。
    4. Formal review incomplete -- 受理台帳への登載提案は、その手続きの
                                   形式審査項目が**全部**満たされて
                                   いなければ通さない。
    5. Out-of-scope intent      -- 受理拒否の決定・候補者の実質評価・
                                   当落の確定は、risk level で緩和される
                                   ゲートではなく構造的に不在。
    6. Already published        -- 同じ filing の二重受理を、`:status` 値で
                                   なく専用の `:published?` boolean で防ぐ。

  The confidence/actuation gate is SOFT: `:actuation/publish-receipt`
  (entering a filing in the public register is a real administrative
  act with legal effect for a candidate) always escalates to a human,
  and `electoralops.phase` independently never auto-commits it. Two
  layers agree, deliberately."
  (:require [electoralops.intake :as intake]
            [electoralops.registry :as registry]
            [electoralops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Entering a filing in the public register is the ONE real-world
  actuation event this actor performs."
  #{:actuation/publish-receipt})

;; ----------------------------- checks -----------------------------

(defn- procedure-basis-violations
  "その法域・その手続きが `senkyo.procedure` に無ければ HARD。
  **未収録は「要件が無い」ではない。**"
  [{:keys [op subject]} st]
  (when (contains? #{:review/formal :deficiency/notice :actuation/publish-receipt} op)
    (let [f (store/filing st subject)]
      (when (nil? (intake/procedure-basis (:jurisdiction f) (:procedure-id f)))
        [{:rule :no-procedure-basis
          :detail (str (:jurisdiction f) "/" (:procedure-id f)
                       " は senkyo.procedure に未収録。受領要件を創作しない")}]))))

(defn- receiving-authority-violations
  "選挙管理機関・returning officer が受領者でない手続きを、この actor が
  受理してはならない（日本の供託は法務局が受ける）。"
  [{:keys [op subject]} st]
  (when (contains? #{:review/formal :actuation/publish-receipt} op)
    (let [f (store/filing st subject)]
      (when (and (some? (intake/procedure-basis (:jurisdiction f) (:procedure-id f)))
                 (not (intake/receivable-here? (:jurisdiction f) (:procedure-id f))))
        [{:rule :wrong-receiving-authority
          :detail (str (:procedure-id f) " の受領者は選挙管理機関ではない"
                       " —— この actor の受理対象ではない")}]))))

(defn- deadline-violations
  "filing 自身の提出日と、`senkyo` から再計算した法定期限を突き合わせる。
  提案が主張した期限は読まない —— 読んだら再計算の意味が無い。"
  [{:keys [op subject]} context st]
  (when (contains? #{:review/formal :actuation/publish-receipt} op)
    (let [f (store/filing st subject)
          anchors (:anchors context store/demo-anchors)
          status (intake/deadline-status f anchors)]
      (when (intake/deadline-blocking? status)
        [{:rule :filing-deadline-not-satisfied
          :detail (str subject " の期限判定が " status
                       "（提出日=" (:submitted-epoch-day f) "）。"
                       "**確認できないことを『期限内』として受理しない**")}]))))

(defn- formal-review-violations
  "受理台帳への登載は、形式審査項目が全部満たされてから。"
  [{:keys [op subject]} st]
  (when (= op :actuation/publish-receipt)
    (let [f (store/filing st subject)]
      (when-not (intake/formal-review-complete? f)
        [{:rule :formal-review-incomplete
          :detail (str subject " の未充足の形式審査項目: "
                       (pr-str (intake/outstanding-review-items f)))}]))))

(defn- out-of-scope-violations
  "受理拒否の決定・候補者の実質評価・当落の確定は構造的に不在。"
  [_request proposal]
  (let [oos (intake/any-out-of-scope (set (get-in proposal [:value :intents] [])))]
    (when (seq oos)
      [{:rule :out-of-scope-intent
        :detail (str "扱わない意図: " (pr-str oos)
                     " —— 選挙の結果を左右する判断は人間の選挙管理機関のもの")}])))

(defn- already-published-violations
  "同じ filing を二度受理台帳に載せない。専用の `:published?` boolean を見る。"
  [{:keys [op subject]} st]
  (when (= op :actuation/publish-receipt)
    (when (store/filing-already-published? st subject)
      [{:rule :already-published
        :detail (str subject " は既に受理台帳へ登載済み")}])))

(defn check
  "Censors an ElectoralOps-LLM proposal against the governor rules.
   Returns {:ok? bool :violations [..] :confidence c :escalate? bool
            :high-stakes? bool :hard? bool}."
  [request context proposal st]
  (let [hard (into []
                   (concat (procedure-basis-violations request st)
                           (receiving-authority-violations request st)
                           (deadline-violations request context st)
                           (formal-review-violations request st)
                           (out-of-scope-violations request proposal)
                           (already-published-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn deadline-ground-truth
  "governor が使うのと同じ再計算を、外からも呼べるように公開する
  （テストと運用の照会が governor の内部実装に依存しないため）。"
  [filing anchors]
  {:status (intake/deadline-status filing anchors)
   :within? (registry/submitted-within-deadline? filing anchors)})

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
