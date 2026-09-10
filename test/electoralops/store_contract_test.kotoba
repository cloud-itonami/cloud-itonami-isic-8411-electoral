(ns electoralops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [electoralops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "candidacy-filing-A" (:filing-name (store/filing s "filing-1"))))
      (is (= "JPN" (:jurisdiction (store/filing s "filing-1"))))
      (is (= :jp-candidacy-filing (:procedure-id (store/filing s "filing-1")))
          "keyword な procedure-id が両バックエンドで往復する")
      (is (= 20488 (:submitted-epoch-day (store/filing s "filing-1"))))
      (is (= 4 (count (:review-items-satisfied (store/filing s "filing-1")))))
      (is (= 1 (count (:review-items-satisfied (store/filing s "filing-3")))))
      (is (false? (:published? (store/filing s "filing-1"))))
      (is (= ["filing-1" "filing-2" "filing-3" "filing-4" "filing-5"]
             (mapv :id (store/all-filings s))))
      (is (nil? (store/formal-review-of s "filing-1")))
      (is (nil? (store/deficiency-of s "filing-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/receipt-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/filing-already-published? s "filing-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :filing/upsert
                                 :value {:id "filing-1" :status :received}})
        (is (= :received (:status (store/filing s "filing-1"))))
        (is (= "candidacy-filing-A" (:filing-name (store/filing s "filing-1")))
            "unrelated field preserved")
        (is (= :jp-candidacy-filing (:procedure-id (store/filing s "filing-1")))
            "procedure-id preserved through a partial upsert"))
      (testing "formal-review / deficiency payloads commit and read back"
        (store/commit-record! s {:effect :formal-review/set :path ["filing-1"]
                                 :payload {:filing-id "filing-1" :items ["a" "b"]}})
        (is (= {:filing-id "filing-1" :items ["a" "b"]} (store/formal-review-of s "filing-1")))
        (store/commit-record! s {:effect :deficiency/set :path ["filing-3"]
                                 :payload {:filing-id "filing-3" :outstanding ["供託の有無"]}})
        (is (= {:filing-id "filing-3" :outstanding ["供託の有無"]}
               (store/deficiency-of s "filing-3")))
        (is (nil? (store/deficiency-of s "filing-1"))))
      (testing "receipt entry drafts a record and advances the sequence"
        (store/commit-record! s {:effect :filing/enter-receipt :path ["filing-1"]})
        (is (= "JPN-RCPT-000000" (get (first (store/receipt-history s)) "record_id")))
        (is (= "filing-receipt-draft" (get (first (store/receipt-history s)) "kind")))
        (is (true? (:published? (store/filing s "filing-1"))))
        (is (= 1 (count (store/receipt-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/filing-already-published? s "filing-1")))
        (is (false? (store/filing-already-published? s "filing-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (= [] (store/all-filings s)))
    (is (nil? (store/filing s "nope")))
    (is (zero? (store/next-sequence s "JPN")))))
