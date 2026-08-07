(ns electoralops.phase
  "Phase 0->3 staged rollout —— 受領側の段階的な権限付与。

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- filing の受付記録のみ。全書き込みが人手承認。
    Phase 2  assisted-review  -- 形式審査 + 不備通知を追加。なお承認必須。
    Phase 3  supervised auto  -- governor-clean な `:filing/receive`
                                （受付記録そのものは候補者の地位を左右しない）
                                だけが auto-commit しうる。
                                `:actuation/publish-receipt` は**どの phase でも**
                                auto にならない。

  `:actuation/publish-receipt` はどの phase の `:auto` にも入っていない。
  これは今後のロールアウトの milestone ではなく**恒久的な構造**である ——
  受理台帳への登載は候補者の地位に法的効果を持つ実世界の行政行為であり、
  常に人間の選挙管理職員の判断による。`electoralops.governor` の
  high-stakes ゲートが独立に同じ不変条件を強制する（2 層が合意している）。

  `:review/formal` も同様にどの phase でも auto にならない。**auto で通る
  形式審査は、誰も読んでいない形式審査である。** `:deficiency/notice`
  （不備の通知）も auto にしない —— 通知は候補者に補正の負担を課す外向きの
  行為で、誤って出せば取り消せない。"
  )

(def read-ops  #{})
(def write-ops #{:filing/receive :review/formal :deficiency/notice
                 :actuation/publish-receipt})

;; NOTE the invariant: `:actuation/publish-receipt` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                      :auto #{}}
   1 {:label "assisted-intake"  :writes #{:filing/receive}                       :auto #{}}
   2 {:label "assisted-review"  :writes #{:filing/receive :review/formal
                                          :deficiency/notice}                    :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:filing/receive}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Electoral Administration Governor verdict to a base
  disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
