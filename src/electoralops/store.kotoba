(ns electoralops.store
  "SSoT for the electoral-administration actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store. Pure `.cljc`, so it runs offline AND
                        can be pointed at a real Datomic Local or a
                        kotoba-server pod.

  Both implement the same protocol and pass the same contract
  (test/electoralops/store_contract_test.clj).

  A FILING is acted on directly by the ONE actuation op
  (`:actuation/publish-receipt`), and the double-actuation guard checks
  a dedicated `:published?` boolean rather than a `:status` value --
  the same discipline every prior governor's guards establish.

  The ledger stays append-only on every backend: 'which filing was
  received, which formal-review items were satisfied, which deficiency
  notice went out, which receipt was entered in the register, and on
  whose approval' is always a query over an immutable log. For an
  electoral authority this is not a nice-to-have -- an election's
  administrative record is the thing a losing candidate is entitled to
  contest, and a register nobody can audit is a register nobody should
  trust."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [electoralops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (filing [s id])
  (all-filings [s])
  (formal-review-of [s filing-id] "committed formal-review result for a filing, or nil")
  (deficiency-of [s filing-id] "committed deficiency notice for a filing, or nil")
  (ledger [s])
  (receipt-history [s] "the append-only filing-receipt history (electoralops.registry drafts)")
  (next-sequence [s jurisdiction] "next receipt-number sequence for a jurisdiction")
  (filing-already-published? [s filing-id] "has this filing already been entered in the register?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-filings [s filings] "replace/seed the filing directory (map id->filing)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained filing set so the actor + tests run offline.

  Anchor days are epoch-days: poll-day 20500, announcement-day 20488."
  []
  {:filings
   {"filing-1" {:id "filing-1" :filing-name "candidacy-filing-A"
                :jurisdiction "JPN" :procedure-id :jp-candidacy-filing
                :submitted-epoch-day 20488
                :review-items-satisfied ["書類の欠落" "供託の有無" "被選挙権の要件"
                                         "重複立候補の制限"]
                :published? false :status :intake}
    "filing-2" {:id "filing-2" :filing-name "expense-return-late"
                :jurisdiction "JPN" :procedure-id :jp-expense-return
                :submitted-epoch-day 20600            ; 投票日 +100 日 = 期限徒過
                :review-items-satisfied ["提出期限の遵守" "法定限度額との照合"
                                         "領収書の添付" "収支の計算の整合"]
                :published? false :status :intake}
    "filing-3" {:id "filing-3" :filing-name "candidacy-filing-incomplete"
                :jurisdiction "JPN" :procedure-id :jp-candidacy-filing
                :submitted-epoch-day 20488
                :review-items-satisfied ["書類の欠落"]  ; 4 項目中 1 項目のみ
                :published? false :status :intake}
    "filing-4" {:id "filing-4" :filing-name "atlantis-filing"
                :jurisdiction "ATL" :procedure-id :atl-unknown
                :submitted-epoch-day 20488
                :review-items-satisfied []
                :published? false :status :intake}
    "filing-5" {:id "filing-5" :filing-name "deposit-at-legal-affairs-bureau"
                :jurisdiction "JPN" :procedure-id :jp-deposit
                :submitted-epoch-day 20488
                :review-items-satisfied []
                :published? false :status :intake}}})

(def demo-anchors
  "デモ用の基準日（epoch-day）。実運用では選挙ごとに与える。"
  {:poll-day 20500 :announcement-day 20488 :calendar-year-end 20454
   :nomination-close 20488 :result-declaration 20502})

;; ----------------------------- shared commit logic -----------------------------

(defn- enter-receipt!
  "Backend-agnostic `:filing/enter-receipt` -- looks the filing up via
  the protocol, drafts the receipt record, and returns
  {:result .. :filing-patch ..} for the caller to persist."
  [s filing-id]
  (let [f (filing s filing-id)
        seq-n (next-sequence s (:jurisdiction f))
        result (registry/register-receipt filing-id (:jurisdiction f) (:procedure-id f) seq-n)]
    {:result result
     :filing-patch {:published? true
                    :receipt-number (get result "receipt_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (filing [_ id] (get-in @a [:filings id]))
  (all-filings [_] (sort-by :id (vals (:filings @a))))
  (formal-review-of [_ id] (get-in @a [:formal-reviews id]))
  (deficiency-of [_ id] (get-in @a [:deficiencies id]))
  (ledger [_] (:ledger @a))
  (receipt-history [_] (:receipts @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (filing-already-published? [_ id] (boolean (get-in @a [:filings id :published?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :filing/upsert
      (swap! a update-in [:filings (:id value)] merge value)

      :formal-review/set
      (swap! a assoc-in [:formal-reviews (first path)] payload)

      :deficiency/set
      (swap! a assoc-in [:deficiencies (first path)] payload)

      :filing/enter-receipt
      (let [filing-id (first path)
            {:keys [result filing-patch]} (enter-receipt! s filing-id)
            jurisdiction (:jurisdiction (filing s filing-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:filings filing-id] merge filing-patch)
                       (update :receipts registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-filings [s filings] (when (seq filings) (swap! a assoc :filings filings)) s))

(defn seed-db
  "A MemStore seeded with the demo filing set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :formal-reviews {} :deficiencies {} :ledger []
                           :sequences {} :receipts []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  {:filing/id                  {:db/unique :db.unique/identity}
   :formal-review/filing-id    {:db/unique :db.unique/identity}
   :deficiency/filing-id       {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :receipt/seq                {:db/unique :db.unique/identity}
   :sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- filing->tx [{:keys [id filing-name jurisdiction procedure-id submitted-epoch-day
                          review-items-satisfied published? status receipt-number]}]
  (cond-> {:filing/id id}
    filing-name                    (assoc :filing/filing-name filing-name)
    jurisdiction                   (assoc :filing/jurisdiction jurisdiction)
    procedure-id                   (assoc :filing/procedure-id (enc procedure-id))
    submitted-epoch-day            (assoc :filing/submitted-epoch-day submitted-epoch-day)
    (some? review-items-satisfied) (assoc :filing/review-items-satisfied (enc (vec review-items-satisfied)))
    (some? published?)             (assoc :filing/published? published?)
    status                         (assoc :filing/status status)
    receipt-number                 (assoc :filing/receipt-number receipt-number)))

(def ^:private filing-pull
  [:filing/id :filing/filing-name :filing/jurisdiction :filing/procedure-id
   :filing/submitted-epoch-day :filing/review-items-satisfied :filing/published?
   :filing/status :filing/receipt-number])

(defn- pull->filing [m]
  (when (:filing/id m)
    {:id (:filing/id m)
     :filing-name (:filing/filing-name m)
     :jurisdiction (:filing/jurisdiction m)
     :procedure-id (dec* (:filing/procedure-id m))
     :submitted-epoch-day (:filing/submitted-epoch-day m)
     :review-items-satisfied (or (dec* (:filing/review-items-satisfied m)) [])
     :published? (boolean (:filing/published? m))
     :status (:filing/status m)
     :receipt-number (:filing/receipt-number m)}))

(defrecord DatomicStore [conn]
  Store
  (filing [_ id]
    (pull->filing (d/pull (d/db conn) filing-pull [:filing/id id])))
  (all-filings [_]
    (->> (d/q '[:find [?id ...] :where [?e :filing/id ?id]] (d/db conn))
         (map #(pull->filing (d/pull (d/db conn) filing-pull [:filing/id %])))
         (sort-by :id)))
  (formal-review-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?fid
                :where [?k :formal-review/filing-id ?fid] [?k :formal-review/payload ?p]]
              (d/db conn) id)))
  (deficiency-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?fid
                :where [?k :deficiency/filing-id ?fid] [?k :deficiency/payload ?p]]
              (d/db conn) id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (receipt-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :receipt/seq ?s] [?e :receipt/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (filing-already-published? [s id] (boolean (:published? (filing s id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :filing/upsert
      (d/transact! conn [(filing->tx value)])

      :formal-review/set
      (d/transact! conn [{:formal-review/filing-id (first path) :formal-review/payload (enc payload)}])

      :deficiency/set
      (d/transact! conn [{:deficiency/filing-id (first path) :deficiency/payload (enc payload)}])

      :filing/enter-receipt
      (let [filing-id (first path)
            {:keys [result filing-patch]} (enter-receipt! s filing-id)
            jurisdiction (:jurisdiction (filing s filing-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(filing->tx (assoc filing-patch :id filing-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:receipt/seq (count (receipt-history s)) :receipt/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-filings [s filings]
    (when (seq filings) (d/transact! conn (mapv filing->tx (vals filings)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [filings]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-filings s filings))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo filing set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
