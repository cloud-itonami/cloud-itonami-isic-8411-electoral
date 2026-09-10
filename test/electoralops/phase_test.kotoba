(ns electoralops.phase-test
  "The phase table as executable tests. この repo が退行してはならない不変条件:
  `:actuation/publish-receipt` はどの phase の `:auto` にも入らない。"
  (:require [clojure.test :refer [deftest is testing]]
            [electoralops.phase :as phase]))

(deftest publish-receipt-never-auto-at-any-phase
  (testing "受理台帳への登載は候補者の地位に法的効果を持つ —— 常に人間の判断"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/publish-receipt))
          (str "phase " n " must not auto-commit :actuation/publish-receipt")))))

(deftest formal-review-never-auto-at-any-phase
  (testing "auto で通る形式審査は、誰も読んでいない形式審査である"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :review/formal))
          (str "phase " n " must not auto-commit :review/formal")))))

(deftest deficiency-notice-never-auto-at-any-phase
  (testing "不備通知は候補者に補正の負担を課す外向きの行為 —— 誤って出せば取り消せない"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :deficiency/notice))
          (str "phase " n " must not auto-commit :deficiency/notice")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-the-record-keeping-op
  (testing "受付記録そのものは候補者の地位を左右しない —— 唯一の auto 対象"
    (is (= #{:filing/receive} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :filing/receive} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= {:disposition :escalate :reason :phase-approval}
         (phase/gate 3 {:op :review/formal} :commit))))

(deftest gate-holds-a-write-not-yet-enabled
  (is (= {:disposition :hold :reason :phase-disabled}
         (phase/gate 1 {:op :review/formal} :commit)))
  (is (= {:disposition :hold :reason :phase-disabled}
         (phase/gate 2 {:op :actuation/publish-receipt} :commit))))

(deftest every-write-op-is-known-to-the-table
  (doseq [op phase/write-ops]
    (is (contains? (:writes (get phase/phases 3)) op)
        (str op " は phase 3 の :writes に無い"))))
