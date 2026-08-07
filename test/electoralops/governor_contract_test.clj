(ns electoralops.governor-contract-test
  "受領側の governor 契約を実行可能なテストにしたもの。守る不変条件:

    ElectoralOps-LLM は Electoral Administration Governor が拒否する
    受理を通せない。`:actuation/publish-receipt` はどの phase でも
    auto-commit しない。`:filing/receive`（受付記録そのものは候補者の
    地位を左右しない）は clean なら auto-commit しうる。commit でも
    hold でも ledger にはちょうど 1 事実残る。"
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [electoralops.governor :as governor]
            [electoralops.operation :as op]
            [electoralops.store :as store]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator
  {:actor-id "clerk-1" :actor-role :electoral-officer :phase 3
   :anchors store/demo-anchors})

(defn- exec-op
  ([actor tid request] (exec-op actor tid request operator))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "clerk-1"}} {:thread-id tid :resume? true}))

(defn- dispo [res] (get-in res [:state :disposition]))

(defn- basis [db] (into #{} (mapcat :basis (store/ledger db))))

;; ------------------------------------------------ clean paths

(deftest clean-receive-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                     {:op :filing/receive :subject "filing-1"
                      :patch {:id "filing-1" :filing-name "candidacy-filing-A"}})]
    (is (= :commit (dispo res)))
    (is (= "candidacy-filing-A" (:filing-name (store/filing db "filing-1"))))
    (is (= 1 (count (store/ledger db))))))

(deftest formal-review-always-needs-approval
  (testing "形式審査はどの phase でも auto にならない —— auto で通る審査は誰も読んでいない審査"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :review/formal :subject "filing-1"})]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (dispo r2)))
        (is (some? (store/formal-review-of db "filing-1")))))))

(deftest clean-receipt-escalates-then-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t3" {:op :actuation/publish-receipt :subject "filing-1"})]
    (is (= :interrupted (:status res)) "受理台帳への登載は clean でも必ず人を通す")
    (let [r2 (approve! actor "t3")]
      (is (= :commit (dispo r2)))
      (is (true? (:published? (store/filing db "filing-1"))))
      (is (= "JPN-RCPT-000000" (get (first (store/receipt-history db)) "record_id")))
      (is (= 1 (store/next-sequence db "JPN"))))))

;; ------------------------------------------------ HARD holds

(deftest missing-procedure-basis-is-held
  (testing "未収録の手続きの審査項目を推測で作らせない"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :review/formal :subject "filing-4"})]
      (is (= :hold (dispo res)))
      (is (contains? (basis db) :no-procedure-basis))
      (is (nil? (store/formal-review-of db "filing-4"))))))

(deftest wrong-receiving-authority-is-held
  (testing "供託は法務局が受ける —— 選管の受領対象でない手続きをこの actor が受理しない"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :actuation/publish-receipt :subject "filing-5"})]
      (is (= :hold (dispo res)))
      (is (contains? (basis db) :wrong-receiving-authority))
      (is (empty? (store/receipt-history db))))))

(deftest past-deadline-filing-is-held
  (testing "期限徒過の提出を期限内として受理しない"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :actuation/publish-receipt :subject "filing-2"})]
      (is (= :hold (dispo res)))
      (is (contains? (basis db) :filing-deadline-not-satisfied)))))

(deftest unresolved-deadline-is-held-not-waved-through
  (testing "基準日を渡さなければ期限は解けない —— それを『期限内』に丸めない"
    (let [[db actor] (fresh)
          ;; :anchors を渡さない context
          res (exec-op actor "t7" {:op :actuation/publish-receipt :subject "filing-1"}
                       {:actor-id "clerk-1" :phase 3 :anchors {}})]
      (is (= :hold (dispo res)))
      (is (contains? (basis db) :filing-deadline-not-satisfied)))))

(deftest incomplete-formal-review-blocks-the-register
  (testing "形式審査が 4 項目中 1 項目しか満たされていない届出は登載できない"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :actuation/publish-receipt :subject "filing-3"})]
      (is (= :hold (dispo res)))
      (is (contains? (basis db) :formal-review-incomplete))
      (is (empty? (store/receipt-history db))))))

(deftest double-receipt-is-held
  (let [[db actor] (fresh)]
    (exec-op actor "t9" {:op :actuation/publish-receipt :subject "filing-1"})
    (approve! actor "t9")
    (let [res (exec-op actor "t9b" {:op :actuation/publish-receipt :subject "filing-1"})]
      (is (= :hold (dispo res)))
      (is (contains? (basis db) :already-published))
      (is (= 1 (count (store/receipt-history db)))))))

(deftest out-of-scope-intents-are-held
  (doseq [intent [:acceptance-refusal :candidate-assessment :result-determination
                  :eligibility-adjudication :voter-targeting]]
    (let [[db actor] (fresh)
          res (exec-op actor (str "t10-" (name intent))
                       {:op :review/formal :subject "filing-1" :intents [intent]})]
      (is (= :hold (dispo res)) (str intent " が素通りした"))
      (is (contains? (basis db) :out-of-scope-intent)))))

;; ------------------------------------------------ ledger discipline

(deftest every-decision-leaves-exactly-one-ledger-fact
  (let [[db actor] (fresh)]
    (exec-op actor "t11" {:op :review/formal :subject "filing-4"})   ; hold
    (is (= 1 (count (store/ledger db))))
    (exec-op actor "t12" {:op :filing/receive :subject "filing-1"
                          :patch {:id "filing-1" :status :received}}) ; commit
    (is (= 2 (count (store/ledger db))))))

(deftest governor-is-callable-directly
  (testing "graph 経由に依存せず governor 単体でも判定できる"
    (let [db (store/seed-db)
          v (governor/check {:op :actuation/publish-receipt :subject "filing-3"}
                            operator {:confidence 0.9 :stake :actuation/publish-receipt} db)]
      (is (true? (:hard? v)))
      (is (contains? (into #{} (map :rule (:violations v))) :formal-review-incomplete)))))

(deftest deadline-ground-truth-is-exposed
  (let [db (store/seed-db)]
    (is (= :within (:status (governor/deadline-ground-truth
                             (store/filing db "filing-1") store/demo-anchors))))
    (is (= :past (:status (governor/deadline-ground-truth
                           (store/filing db "filing-2") store/demo-anchors))))
    (is (false? (:within? (governor/deadline-ground-truth
                           (store/filing db "filing-2") store/demo-anchors))))
    (testing "基準日が無いときは :unresolved であって :no-deadline ではない"
      (is (= :unresolved (:status (governor/deadline-ground-truth
                                   (store/filing db "filing-1") {})))))))
