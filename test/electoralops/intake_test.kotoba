(ns electoralops.intake-test
  "受領側から見た `senkyo.procedure` の読み方の契約。

  最も重要な不変条件は **提出側と受領側が同じ表を読む**こと ——
  `cloud-itonami-isic-9492` が『何を出すか』として読む entry と、この repo が
  『何を受け取るか』として読む entry が同一であることを、テストで固定する。"
  (:require [clojure.test :refer [deftest is testing]]
            [electoralops.intake :as intake]
            [senkyo.procedure :as procedure]
            [senkyo.screen :as screen]))

(deftest same-table-as-the-filing-side
  (testing "受領側が読む entry は senkyo.procedure の entry そのもの（写しを作らない）"
    (is (identical? (procedure/procedure "JPN" :jp-candidacy-filing)
                    (intake/procedure-basis "JPN" :jp-candidacy-filing)))))

(deftest receiving-authority-is-part-of-the-table
  (testing "選管が受け取るもの"
    (is (true? (intake/receivable-here? "JPN" :jp-candidacy-filing)))
    (is (true? (intake/receivable-here? "JPN" :jp-expense-return))))
  (testing "選管が受け取らないもの —— 供託は法務局"
    (is (false? (intake/receivable-here? "JPN" :jp-deposit))))
  (testing "未収録法域"
    (is (nil? (intake/receivable "IND")))
    (is (false? (intake/receivable-here? "IND" :whatever)))))

(deftest formal-review-items-distinguish-empty-from-unknown
  (testing "空ベクタと nil を同じにしない"
    (is (seq (intake/formal-review-items "JPN" :jp-candidacy-filing)))
    (is (= [] (intake/formal-review-items "JPN" :jp-deposit))
        "供託は審査項目を持たない（空）")
    (is (nil? (intake/formal-review-items "ATL" :nope))
        "未収録の手続きは nil であって空ではない")))

(deftest deadline-status-covers-every-case
  (let [f {:jurisdiction "JPN" :procedure-id :jp-expense-return}]
    (testing "期限内"
      (is (= :within (intake/deadline-status (assoc f :submitted-epoch-day 20510)
                                             {:poll-day 20500}))))
    (testing "期限後"
      (is (= :past (intake/deadline-status (assoc f :submitted-epoch-day 20520)
                                           {:poll-day 20500}))))
    (testing "基準日が無い —— :no-deadline に丸めない"
      (is (= :unresolved (intake/deadline-status (assoc f :submitted-epoch-day 20510) {}))))
    (testing "提出日が記録されていない"
      (is (= :unknown-filing (intake/deadline-status f {:poll-day 20500}))))
    (testing "手続きが表に無い"
      (is (= :no-basis (intake/deadline-status {:jurisdiction "ATL" :procedure-id :x}
                                               {:poll-day 20500}))))
    (testing "この層では期限が決まらない（米国の ballot access は州法）"
      (is (= :no-deadline (intake/deadline-status
                           {:jurisdiction "USA" :procedure-id :us-ballot-access
                            :submitted-epoch-day 20510}
                           {:poll-day 20500}))))))

(deftest only-confirmed-in-window-passes
  (testing ":within と :no-deadline 以外は全部受理を止める"
    (is (false? (intake/deadline-blocking? :within)))
    (is (false? (intake/deadline-blocking? :no-deadline)))
    (doseq [s [:past :unresolved :unknown-filing :no-basis]]
      (is (true? (intake/deadline-blocking? s)) (str s " が素通りする")))))

(deftest formal-review-completeness
  (let [items (intake/formal-review-items "JPN" :jp-candidacy-filing)]
    (is (true? (intake/formal-review-complete?
                {:jurisdiction "JPN" :procedure-id :jp-candidacy-filing
                 :review-items-satisfied items})))
    (is (false? (intake/formal-review-complete?
                 {:jurisdiction "JPN" :procedure-id :jp-candidacy-filing
                  :review-items-satisfied (drop 1 items)})))
    (is (= 1 (count (intake/outstanding-review-items
                     {:jurisdiction "JPN" :procedure-id :jp-candidacy-filing
                      :review-items-satisfied (drop 1 items)}))))
    (testing "未収録の手続きは never satisfied（知らない審査は完了しない）"
      (is (nil? (intake/formal-review-complete?
                 {:jurisdiction "ATL" :procedure-id :x :review-items-satisfied []}))))))

(deftest out-of-scope-extends-the-shared-boundary
  (testing "共有ライブラリ側の境界を全部含む"
    (is (every? intake/out-of-scope-intents screen/out-of-scope-intents)))
  (testing "受領側に固有の裁定系を足している"
    (is (contains? intake/out-of-scope-intents :acceptance-refusal))
    (is (contains? intake/out-of-scope-intents :candidate-assessment))
    (is (contains? intake/out-of-scope-intents :result-determination)))
  (testing "上書きでなく拡張である —— 運動側の境界も同時に効く"
    (is (contains? intake/out-of-scope-intents :voter-targeting))
    (is (contains? intake/out-of-scope-intents :eligibility-adjudication)))
  (is (= [:acceptance-refusal :result-determination]
         (intake/any-out-of-scope #{:acceptance-refusal :result-determination :something-fine}))))

(deftest coverage-is-not-recounted-here
  (testing "senkyo のカバレッジをそのまま返す（自前で数え直さない）"
    (is (= (procedure/coverage) (intake/coverage)))
    (is (seq (:needs-source-check (intake/coverage))))))
