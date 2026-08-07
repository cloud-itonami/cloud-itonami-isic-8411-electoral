(ns electoralops.operation
  "OperationActor —— 1 つの受領事務 = 1 回の監督された actor 実行を、
  langgraph-clj の StateGraph で表す。助言者（ElectoralOps-LLM）は
  `:advise` の 1 ノードに封じ込められ、その提案は必ず Electoral
  Administration Governor（`:govern`）とロールアウト phase ゲート
  （`:decide`）を通ってから SSoT に commit される。

  依存は全部注入されるので、それぞれが差し替えであって書き直しではない:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam)
    - the Advisor  (mock | real LLM)                     - :advisor opt
    - the Phase    (0->3 rollout)                        - :phase in ctx
    - the Anchors  (選挙ごとの基準日 epoch-day)           - :anchors in ctx

  `:anchors` を context に置くのは意図的である。**選挙の日付は選挙ごとに
  違う**ので、期限の再計算に使う基準日は actor の設定ではなく、その実行の
  文脈として渡す。渡し忘れた場合はデモ用の既定値に落ちるのではなく、
  governor 側で `:unresolved` として HOLD になる（`electoralops.intake/
  deadline-status`）。

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` が actor を停止し、判断を人間の
  選挙管理職員に渡す。承認者は `{:approval {:status :approved}}` で再開する。
  `:actuation/publish-receipt` は governor が clean でも**必ず**ここに来る。"
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [electoralops.electoralopsllm :as electoralopsllm]
            [electoralops.governor :as governor]
            [electoralops.phase :as phase]
            [electoralops.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `electoralops.store/Store`).
  opts:
    :advisor      -- an `electoralops.electoralopsllm/Advisor` (default: mock)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (electoralopsllm/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase/anchors
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ElectoralOps-LLM inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (electoralopsllm/-advise advisor store request)]
            {:proposal p :audit [(electoralopsllm/trace request p)]})))

      ;; Electoral Administration Governor -- independent censor.
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before; a human electoral
      ;; officer resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
