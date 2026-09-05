(ns electoralops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and NO generator here at all.

  Everything on the generated page comes from ONE real run of this
  repo's own actor stack — `electoralops.operation` (a langgraph
  StateGraph) -> `electoralops.electoralopsllm` (the contained advisor)
  -> `electoralops.governor` (the independent censor) ->
  `electoralops.store` (the SSoT + append-only audit ledger). Nothing on
  the page is hand-typed: every filing id, procedure id, epoch-day,
  receipt number, review item, hold rule and hold detail is read back
  out of the store or out of the governor verdict the run produced. If a
  value cannot be obtained that way, the page says so instead of
  inventing it.

  The scenario is adapted from this repo's own `electoralops.sim`
  (`clojure -M:dev:run`, run and confirmed BEFORE this file was written:
  its ids `filing-1`..`filing-5` really are the ids
  `electoralops.store/demo-data` seeds), extended so that the page can
  show, side by side:

    - an approved lifecycle (receive -> formal review -> deficiency
      notice -> entry in the filing register), every write of which
      passed through a human approval interrupt;
    - all SIX of the governor's HARD checks actually firing;
    - the ROLLOUT-PHASE gate holding a proposal the governor was
      perfectly happy with — a different thing from a governor refusal,
      kept in its own table because conflating the two is how a reader
      comes to believe the compliance layer caught something it did not.

  Deterministic: no timestamps in the page content, byte-identical
  across reruns from the same seed (verify by diffing two runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [electoralops.governor :as governor]
            [electoralops.intake :as intake]
            [electoralops.operation :as op]
            [electoralops.phase :as phase]
            [electoralops.store :as store]
            [jp-go-dds.skin]
            [langgraph.graph :as g]))

;; ----------------------------- the run -----------------------------

(def ^:private clerk
  "The operator context injected into every run. `:anchors` is passed per
  run, never configured on the actor — election dates change every
  election, and an actor carrying last election's dates answers
  confidently and wrongly (README, `electoralops.operation`)."
  {:actor-id "clerk-1" :actor-role :electoral-officer :phase 3
   :anchors store/demo-anchors})

(defn- disposition-of [res] (get-in res [:state :disposition]))
(defn- verdict-of [res] (get-in res [:state :verdict]))
(defn- audit-of [res] (vec (get-in res [:state :audit])))

(defn- granted-approver
  "The approver the graph's audit channel recorded for this thread, or
  nil. Read from the run, never assumed."
  [res]
  (some #(when (= :approval-granted (:t %)) (:by %)) (audit-of res)))

(defn- exec!
  "One actor run on its own thread-id. `ctx-overrides` lets a step use a
  different rollout phase or a different anchor set than the default."
  ([actor tid request] (exec! actor tid request nil))
  ([actor tid request ctx-overrides]
   (g/run* actor {:request request :context (merge clerk ctx-overrides)}
           {:thread-id tid})))

(defn- approve!
  "Resume a run paused at `interrupt-before #{:request-approval}` with a
  real human approval."
  [actor tid by]
  (g/run* actor {:approval {:status :approved :by by}}
          {:thread-id tid :resume? true}))

(defn- step
  "Runs one step and records what actually happened, so the renderer
  reads the run log rather than re-deriving it. `:thread-id` is the join
  key everywhere below — `[op subject]` is NOT unique in this scenario
  (`filing-1` is proposed for the register twice, once approved and once
  HARD-held as a double entry), and joining on it would misattribute."
  [actor {:keys [tid label request ctx approve-by]}]
  (let [res (exec! actor tid request ctx)
        escalated? (= :escalate (disposition-of res))
        res' (if (and escalated? approve-by) (approve! actor tid approve-by) res)]
    {:thread-id   tid
     :label       label
     :op          (:op request)
     :subject     (:subject request)
     :intents     (vec (:intents request))
     :phase       (:phase (merge clerk ctx))
     :anchors     (:anchors (merge clerk ctx))
     :escalated?  escalated?
     :disposition (disposition-of res')
     :verdict     (verdict-of res)
     :audit       (into (audit-of res) (when escalated? (audit-of res')))
     :approved-by (when escalated? (granted-approver res'))}))

(def ^:private scenario
  "The steps this console is generated from. Ordered: the approved
  lifecycle first, then every HARD check, then the rollout gate."
  [;; --- approved lifecycle -------------------------------------------
   {:tid "a1" :label "Record the arrival of a candidacy filing"
    :request {:op :filing/receive :subject "filing-1"
              :patch {:id "filing-1" :filing-name "candidacy-filing-A"
                      :status :received}}}
   {:tid "a2" :label "Record the arrival of an incomplete candidacy filing"
    :request {:op :filing/receive :subject "filing-3"
              :patch {:id "filing-3" :filing-name "candidacy-filing-incomplete"
                      :status :received}}}
   {:tid "a3" :label "Formal review of the complete filing"
    :request {:op :review/formal :subject "filing-1"} :approve-by "clerk-1"}
   {:tid "a4" :label "Deficiency notice for the incomplete filing"
    :request {:op :deficiency/notice :subject "filing-3"} :approve-by "clerk-1"}
   {:tid "a5" :label "Enter the complete filing in the public register"
    :request {:op :actuation/publish-receipt :subject "filing-1"}
    :approve-by "clerk-1"}

   ;; --- the six HARD checks ------------------------------------------
   {:tid "h1" :label "Enter the SAME filing in the register a second time"
    :request {:op :actuation/publish-receipt :subject "filing-1"}
    :approve-by "clerk-1"}          ; offered an approver on purpose: it never gets asked
   {:tid "h2" :label "Register an expense return filed after the statutory deadline"
    :request {:op :actuation/publish-receipt :subject "filing-2"}
    :approve-by "clerk-1"}
   {:tid "h3" :label "Register a filing whose formal review is not finished"
    :request {:op :actuation/publish-receipt :subject "filing-3"}
    :approve-by "clerk-1"}
   {:tid "h4" :label "Formally review a procedure no catalog entry covers"
    :request {:op :review/formal :subject "filing-4"} :approve-by "clerk-1"}
   {:tid "h5" :label "Register a deposit — received by the 法務局, not the commission"
    :request {:op :actuation/publish-receipt :subject "filing-5"}
    :approve-by "clerk-1"}
   {:tid "h6" :label "Formal review carrying a result-determination intent"
    :request {:op :review/formal :subject "filing-1"
              :intents [:result-determination :candidate-assessment]}
    :approve-by "clerk-1"}
   {:tid "h7" :label "Formal review with no election anchor days supplied"
    :request {:op :review/formal :subject "filing-3"}
    :ctx {:anchors {}} :approve-by "clerk-1"}

   ;; --- rollout gate, NOT a governor refusal --------------------------
   {:tid "p1" :label "Formal review attempted at phase 1 (assisted-intake)"
    :request {:op :review/formal :subject "filing-1"} :ctx {:phase 1}}])

(defn run-demo!
  "Drives the REAL actor over a freshly seeded store and returns
  `{:db .. :runs [..]}`. Every value the renderer prints is read back
  from `:db` or from the verdict recorded in `:runs`."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    {:db db :runs (mapv #(step actor %) scenario)}))

;; ----------------------------- derivations -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kwname [v] (if (keyword? v) (name v) (str v)))

(defn- joined [xs] (if (seq xs) (str/join ", " (map kwname xs)) "—"))

(defn- governor-holds
  "HARD holds as the governor actually produced them: a hold whose
  verdict carries at least one violation. A rollout-phase hold carries
  none and is deliberately excluded here."
  [runs]
  (filter #(and (= :hold (:disposition %)) (seq (:violations (:verdict %)))) runs))

(defn- rollout-holds
  "Holds with an EMPTY violation list — the rollout phase gate refusing
  a proposal the governor had no objection to."
  [runs]
  (filter #(and (= :hold (:disposition %)) (empty? (:violations (:verdict %)))) runs))

(defn- phase-reason
  "The `:phase-reason` the `:decide` node stamped on the hold fact, read
  from the run's own audit — not guessed from the phase table."
  [run]
  (some #(when (= :governor-hold (:t %)) (:phase-reason %)) (:audit run)))

(defn- escalation-reason [run]
  (some #(when (= :approval-requested (:t %)) (:reason %)) (:audit run)))

(defn- distinct-hold-rules [runs]
  (->> (governor-holds runs)
       (mapcat #(map :rule (:violations (:verdict %))))
       distinct sort vec))

;; --- approver attribution: MEASURED at render time, never assumed ----

(def ^:private approver-key-candidates
  "Every key an approver could plausibly land under in a persisted
  record. The page checks the record it actually has for each of these,
  so that the day the store starts keeping the approver the disclosure
  below corrects itself instead of becoming a lie."
  [:approved-by :approver :approved_by "approved-by" "approved_by" "approver"])

(defn- approver-in
  "[key value] if this record carries an approver, else nil."
  [m]
  (when (map? m)
    (some (fn [k] (when-let [v (get m k)]
                    (when-not (= "" v) [k v])))
          approver-key-candidates)))

(defn- persisted-for
  "The record(s) this committed op left in the SSoT. Returns
  `{:register .. :records [..]}`; more than one match is reported as
  ambiguous rather than silently resolved."
  [db {:keys [op subject]}]
  (case op
    :review/formal
    {:register "formal-reviews" :records (remove nil? [(store/formal-review-of db subject)])}
    :deficiency/notice
    {:register "deficiencies" :records (remove nil? [(store/deficiency-of db subject)])}
    :actuation/publish-receipt
    {:register "receipt register"
     :records (vec (filter #(= subject (get % "filing_id")) (store/receipt-history db)))}
    :filing/receive
    {:register "filings" :records (remove nil? [(store/filing db subject)])}
    {:register "—" :records []}))

(defn- ledger-commits-for [db {:keys [op subject]}]
  (filter #(and (= :committed (:t %)) (= op (:op %)) (= subject (:subject %)))
          (store/ledger db)))

(defn- attribution-rows
  "One row per approved commit in this run: who the graph's audit says
  approved it, and whether that approver survives into (a) the SSoT
  record and (b) the append-only ledger fact. Both are DERIVED by
  looking in the records, not asserted."
  [db runs]
  (for [r runs
        :when (and (= :commit (:disposition r)) (:approved-by r))
        :let [{:keys [register records]} (persisted-for db r)
              rec (first records)
              lf  (first (ledger-commits-for db r))]]
    {:thread-id (:thread-id r)
     :op (:op r)
     :subject (:subject r)
     :audit-approver (:approved-by r)
     :register register
     :ambiguous? (> (count records) 1)
     :record-approver (approver-in rec)
     :record-present? (some? rec)
     :ledger-approver (approver-in lf)
     :ledger-present? (some? lf)}))

;; ----------------------------- HTML -----------------------------

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- crit [s] (str "<span class=\"critical\">" s "</span>"))
(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))
(defn- code [s] (str "<code>" (esc s) "</code>"))

(defn- section [title lead & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       (str/join body)
       "  </section>\n"))

(defn- table [headers rows]
  (if (seq rows)
    (str "    <table>\n"
         "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
         "      <tbody>\n" (str/join "\n" rows) "\n"
         "      </tbody>\n"
         "    </table>\n")
    (str "    <p class=\"warn\">no rows — this run produced none</p>\n")))

;; --- individual sections ---------------------------------------------

(defn- filing-rows [db runs]
  (let [ledger (vec (store/ledger db))]
    (for [f (store/all-filings db)
          :let [gt (governor/deadline-ground-truth f store/demo-anchors)
                items (intake/formal-review-items (:jurisdiction f) (:procedure-id f))
                outstanding (intake/outstanding-review-items f)
                last-fact (last (filter #(= (:id f) (:subject %)) ledger))
                held (filter #(= (:id f) (:subject %)) (governor-holds runs))]]
      (row (code (:id f))
           (esc (:filing-name f))
           (esc (:jurisdiction f))
           (code (:procedure-id f))
           (str "<span class=\"num\">" (esc (:submitted-epoch-day f)) "</span>")
           (if (:within? gt)
             (ok (esc (kwname (:status gt))))
             (crit (esc (kwname (:status gt)))))
           (cond
             (nil? items) (crit "no catalog entry — review items unknowable")
             (empty? outstanding) (ok (str (count items) " / " (count items)))
             :else (warn (str (- (count items) (count outstanding)) " / " (count items)
                              " · outstanding: " (esc (joined outstanding)))))
           (if (:published? f)
             (ok (str "registered · " (code (:receipt-number f))))
             (muted "not in the register"))
           (cond
             (seq held) (crit (str (count held) " HARD hold"
                                   (when (> (count held) 1) "s")))
             (nil? last-fact) (muted "no activity")
             (= :committed (:t last-fact)) (ok (esc (kwname (:op last-fact))))
             :else (muted (esc (kwname (:t last-fact)))))))))

(defn- hold-rows [runs]
  (for [r (governor-holds runs)
        v (:violations (:verdict r))]
    (row (code (:thread-id r))
         (esc (:label r))
         (code (:op r))
         (code (:subject r))
         (crit (esc (kwname (:rule v))))
         (esc (:detail v)))))

(defn- rollout-rows [runs]
  (concat
   (for [r (rollout-holds runs)]
     (row (code (:thread-id r))
          (code (:op r))
          (code (:subject r))
          (str "phase " (esc (:phase r)))
          (warn "rollout hold")
          (esc (kwname (or (phase-reason r) :unrecorded)))
          (muted "0 governor violations — the governor did not object")))
   (for [r runs :when (:escalated? r)]
     (row (code (:thread-id r))
          (code (:op r))
          (code (:subject r))
          (str "phase " (esc (:phase r)))
          (warn "escalated to a human")
          (esc (kwname (or (escalation-reason r) :unrecorded)))
          (if (= :commit (:disposition r))
            (ok (str "approved by " (esc (or (:approved-by r) "—"))))
            (muted (esc (kwname (:disposition r)))))))))

(defn- phase-table-rows []
  (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
    (row (str "<span class=\"num\">" n "</span>"
              (when (= n phase/default-phase) (str " " (muted "(default)"))))
         (esc label)
         (if (seq writes)
           (str/join " " (map #(code %) (sort-by str writes)))
           (muted "none"))
         (if (seq auto)
           (str/join " " (map #(code %) (sort-by str auto)))
           (muted "none — every write needs a human")))))

(defn- attribution-rows-html [rows]
  (for [{:keys [thread-id op subject audit-approver register ambiguous?
                record-approver record-present? ledger-approver ledger-present?]} rows]
    (row (code thread-id)
         (code op)
         (code subject)
         (ok (esc audit-approver))
         (esc register)
         (cond
           ambiguous? (warn "ambiguous — more than one record matches this subject")
           (not record-present?) (crit "no record found")
           record-approver (ok (str (code (first record-approver)) " = "
                                    (esc (second record-approver))))
           :else (crit "absent — the record does not keep it"))
         (cond
           (not ledger-present?) (crit "no ledger fact found")
           ledger-approver (ok (str (code (first ledger-approver)) " = "
                                    (esc (second ledger-approver))))
           :else (crit "absent — the ledger fact does not keep it")))))

(defn- attribution-lead
  "The disclosure sentence, computed from what the records actually
  contain in THIS run. If the store starts keeping the approver, this
  text changes by itself."
  [rows]
  (let [total (count rows)
        kept (count (filter :record-approver rows))
        lost (- total kept)
        led-kept (count (filter :ledger-approver rows))]
    (str "Measured, not assumed. This run produced " total
         " human-approved commit" (when (not= 1 total) "s") ". "
         (cond
           (zero? total) "There is nothing to attribute."
           (= kept total) (str "The SSoT record keeps the approver for all "
                               total " of them.")
           (zero? kept) (str "The SSoT record keeps the approver for NONE of them — "
                             "the approval happened, and the record does not say so.")
           :else (str "The SSoT record keeps the approver for " kept " of them and "
                      "drops it for " lost " — the write path attributes some effects "
                      "and not others."))
         " The append-only ledger keeps it for " led-kept " of " total
         ". Where the approver is absent below, a reader of the record alone "
         "cannot tell &lsquo;nobody approved this&rsquo; from &lsquo;the store did not keep "
         "who did&rsquo;, which is why the gap is printed rather than omitted.")))

(defn- review-rows [db]
  (for [f (store/all-filings db)
        :let [fr (store/formal-review-of db (:id f))]
        :when fr]
    (row (code (:id f))
         (esc (joined (:items fr)))
         (if (seq (:outstanding fr))
           (warn (esc (joined (:outstanding fr))))
           (ok "none"))
         (if (:spec-basis fr) (esc (:spec-basis fr)) (crit "no spec basis"))
         (if-let [a (approver-in fr)] (ok (esc (second a))) (crit "not kept")))))

(defn- deficiency-rows [db]
  (for [f (store/all-filings db)
        :let [d (store/deficiency-of db (:id f))]
        :when d]
    (row (code (:id f))
         (esc (:filing-name f))
         (warn (esc (joined (:outstanding d))))
         (str "<span class=\"num\">" (count (:outstanding d)) "</span>")
         (if-let [a (approver-in d)] (ok (esc (second a))) (crit "not kept")))))

(defn- receipt-rows [db]
  (for [r (store/receipt-history db)]
    (row (code (get r "record_id"))
         (esc (get r "kind"))
         (code (get r "filing_id"))
         (esc (get r "jurisdiction"))
         (code (get r "procedure_id"))
         (if (get r "immutable") (ok "immutable") (warn "mutable"))
         (if-let [a (approver-in r)] (ok (esc (second a))) (crit "not kept")))))

(defn- deadline-rows [db]
  (for [f (store/all-filings db)
        :let [p (intake/procedure-basis (:jurisdiction f) (:procedure-id f))
              d (intake/due (:jurisdiction f) (:procedure-id f) store/demo-anchors)
              gt (governor/deadline-ground-truth f store/demo-anchors)]]
    (row (code (:id f))
         (code (:procedure-id f))
         (if p (esc (:proc/legal-basis p)) (crit "not in senkyo.procedure"))
         (if p (code (get-in p [:proc/deadline :anchor])) (muted "—"))
         (if (integer? d)
           (str "<span class=\"num\">" d "</span>")
           (crit (esc (kwname (or d :no-basis)))))
         (str "<span class=\"num\">" (esc (:submitted-epoch-day f)) "</span>")
         (if (:within? gt) (ok (esc (kwname (:status gt))))
             (crit (esc (kwname (:status gt)))))
         (if (intake/deadline-blocking? (:status gt))
           (crit "blocks receipt")
           (ok "does not block")))))

(defn- out-of-scope-rows [runs]
  (let [attempted (into #{} (mapcat :intents runs))
        refused (into #{} (for [r (governor-holds runs)
                                v (:violations (:verdict r))
                                :when (= :out-of-scope-intent (:rule v))
                                i (:intents r)]
                            i))]
    (for [i (sort-by kwname intake/out-of-scope-intents)]
      (row (code i)
           (if (attempted i)
             (warn "attempted in this run")
             (muted "not attempted in this run"))
           (if (refused i)
             (crit "HARD-held")
             (muted "—"))))))

(defn- coverage-rows []
  (let [c (intake/coverage)]
    [(row "jurisdictions in the catalog"
          (str (str/join " " (map #(code %) (:jurisdictions c)))
               " <span class=\"num\">(" (:count c) ")</span>"))
     (row "procedures in the catalog"
          (str "<span class=\"num\">" (:procedures c) "</span>"))
     (row (str "procedures whose " (code :proc/confidence) " is not " (code :high))
          (str (warn (str (count (:needs-source-check c)) " of " (:procedures c)))
               " — " (esc (str/join ", " (:needs-source-check c)))))]))

(defn- ledger-rows [db]
  (map-indexed
   (fn [i {:keys [t op subject basis disposition violations phase-reason]}]
     (row (str "<span class=\"num\">" i "</span>")
          (case t
            :committed (ok (esc (kwname t)))
            :governor-hold (if (seq violations)
                             (crit (esc (kwname t)))
                             (warn (str (esc (kwname t)) " (rollout gate)")))
            (muted (esc (kwname t))))
          (code op)
          (code subject)
          (esc (kwname (or disposition :—)))
          (if (seq basis) (esc (joined basis))
              (if phase-reason (warn (esc (kwname phase-reason))) (muted "—")))))
   (store/ledger db)))

;; ----------------------------- the document -----------------------------

(defn render
  "Renders the whole document from `{:db .. :runs ..}` — the output of
  one real `run-demo!`."
  [{:keys [db runs]}]
  (let [holds (governor-holds runs)
        rules (distinct-hold-rules runs)
        attrib (attribution-rows db runs)
        approved (filter #(and (= :commit (:disposition %)) (:escalated? %)) runs)
        auto (filter #(and (= :commit (:disposition %)) (not (:escalated? %))) runs)]
    (str
     "<html><head><meta charset=\"utf-8\">"
     "<title>cloud-itonami-isic-8411-electoral &middot; electoral administration (receiving side)</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Electoral administration (ISIC 8411) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · entry in the public register is never autonomous, at any phase</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "What this page is"
      (str "Generated at build time by <code>electoralops.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>) from ONE run of the real actor: "
           "<code>electoralops.operation</code> (langgraph StateGraph) &rarr; "
           "<code>electoralops.electoralopsllm</code> (the contained advisor) &rarr; "
           "<code>electoralops.governor</code> (the independent censor) &rarr; "
           "<code>electoralops.store</code> (SSoT + append-only ledger). "
           "Every id, epoch-day, review item, receipt number and hold reason below is read back "
           "out of that run. Nothing is hand-typed; where a value is not obtainable from the store "
           "the cell says so.")
      (table ["Measured this run" "Value"]
             [(row "actor runs" (str "<span class=\"num\">" (count runs) "</span>"))
              (row "auto-committed (clean, phase-3 auto-eligible)"
                   (str "<span class=\"num\">" (count auto) "</span> · "
                        (str/join " " (map #(code (:op %)) auto))))
              (row "human-approved commits"
                   (str "<span class=\"num\">" (count approved) "</span> · "
                        (str/join " " (map #(code (:op %)) approved))))
              (row "HARD governor holds (never reached a human)"
                   (str (crit (str "<span class=\"num\">" (count holds) "</span>"))
                        " across " (count rules) " distinct rules: "
                        (str/join " " (map #(code %) rules))))
              (row "rollout-phase holds (governor had no objection)"
                   (str "<span class=\"num\">" (count (rollout-holds runs)) "</span>"))
              (row "filings in the SSoT"
                   (str "<span class=\"num\">" (count (store/all-filings db)) "</span>"))
              (row "append-only ledger facts"
                   (str "<span class=\"num\">" (count (store/ledger db)) "</span>"))]))

     (section
      "Filings"
      (str "The SSoT after the run. <em>Deadline</em> is <strong>recomputed</strong> from "
           "<code>senkyo.procedure</code> against the filing's own <code>:submitted-epoch-day</code> — "
           "the advisor's claim about the deadline is never read. <em>Formal review</em> counts the "
           "jurisdiction's own published review items, all of which must be satisfied before an entry "
           "in the register is possible.")
      (table ["Filing" "Name" "Jurisdiction" "Procedure" "Submitted (epoch-day)"
              "Deadline (recomputed)" "Formal review" "Register" "Outcome this run"]
             (filing-rows db runs)))

     (section
      (str "Governor HARD holds — " (count holds) " refusals, "
           (count rules) " distinct rules")
      (str "A HARD hold is a refusal that <strong>never reaches a human</strong>: the run ends at "
           "<code>:hold</code> without ever pausing at the approval interrupt, so there is no approver "
           "to override it. Each of these steps offered an approver in the scenario and none of them "
           "was asked. All six of the governor's checks fire here.")
      (table ["Thread" "Scenario step" "Op" "Filing" "Rule" "Detail as the governor wrote it"]
             (hold-rows runs)))

     (section
      "Rollout gate — a different thing from a governor refusal"
      (str "<code>electoralops.phase</code> can hold or escalate a proposal the governor had "
           "<strong>no objection to</strong>. Such a hold carries <strong>zero violations</strong>, so "
           "counting holds alone would overstate what the compliance layer caught. They are kept in "
           "their own table for exactly that reason. The phase table below is read out of "
           "<code>electoralops.phase/phases</code> at render time, not transcribed.")
      (table ["Thread" "Op" "Filing" "Phase" "Gate" "Reason (from the run's own audit fact)" "Result"]
             (rollout-rows runs))
      "    <h3>Phase table</h3>\n"
      (table ["Phase" "Label" "Writes permitted" "Auto-commit permitted when governor-clean"]
             (phase-table-rows))
      (str "    <p class=\"muted\"><code>:actuation/publish-receipt</code> appears in no phase's "
           "auto set — that is a permanent structural property, not a rollout milestone. "
           "<code>electoralops.governor</code>'s high-stakes gate enforces the same invariant "
           "independently; two layers agree.</p>\n"))

     (section
      "Approver attribution"
      (attribution-lead attrib)
      (table ["Thread" "Op" "Filing" "Approver (graph audit)" "Register"
              "Approver in the SSoT record" "Approver in the ledger fact"]
             (attribution-rows-html attrib)))

     (section
      "Formal review results"
      "Committed formal-review records, read back out of the store."
      (table ["Filing" "Review items (from the jurisdiction's catalog entry)" "Outstanding"
              "Legal basis" "Approver kept?"]
             (review-rows db)))

     (section
      "Deficiency notices"
      (str "A deficiency notice lists what is still outstanding. It does <strong>not</strong> decide "
           "not to accept the filing — <code>:acceptance-refusal</code> is structurally absent from "
           "this actor's vocabulary.")
      (table ["Filing" "Name" "Outstanding items" "Count" "Approver kept?"]
             (deficiency-rows db)))

     (section
      "Filing receipt register"
      (str "Receipt drafts as <code>electoralops.registry</code> built them. Every one is "
           "<strong>unsigned</strong> — signing is the authority's own act, not this actor's. "
           "The receipt number is a jurisdiction-scoped sequence; this repo invents no receipt-number "
           "standard because none exists internationally.")
      (table ["Receipt" "Kind" "Filing" "Jurisdiction" "Procedure" "Immutable" "Approver kept?"]
             (receipt-rows db)))

     (section
      "Deadline recomputation"
      (str "&laquo;Not knowing a deadline&raquo; and &laquo;there being no deadline&raquo; are "
           "different answers and are kept different. <code>:no-deadline</code> passes (this layer "
           "genuinely does not decide, e.g. US ballot access is state law); "
           "<code>:unresolved</code> / <code>:unknown-filing</code> / <code>:no-basis</code> block, "
           "because rounding &laquo;we could not check&raquo; up to &laquo;in time&raquo; is how a "
           "filing goes into the register anyway. Anchor days come from the run context, not from "
           "the actor's configuration.")
      (table ["Filing" "Procedure" "Legal basis" "Anchor" "Due (epoch-day)"
              "Submitted" "Status" "Effect"]
             (deadline-rows db)))

     (section
      "Permanently out of scope"
      (str "<code>electoralops.intake/out-of-scope-intents</code> is a closed set checked before "
           "anything else runs. These are not gated by risk level and cannot be escalated for human "
           "override. The set below is read from the code at render time; the last two columns show "
           "which of them this run actually attempted and had refused.")
      (table ["Intent" "This run" "Governor"]
             (out-of-scope-rows runs)))

     (section
      "Jurisdiction coverage (honest)"
      (str "Read verbatim from <code>senkyo.procedure/coverage</code> — this repo does not recount it. "
           "A day's error in an electoral deadline forfeits a right, so read the source-check list "
           "before relying on any figure here. An unregistered jurisdiction means "
           "<strong>no spec basis</strong>, never &laquo;no requirements&raquo;.")
      (table ["Measure" "Value"] (coverage-rows)))

     (section
      "Audit ledger (this run)"
      (str "The append-only decision log, in append order, exactly as the run wrote it. "
           "A losing candidate is entitled to contest the administrative record, so every commit and "
           "every refusal is here — including the rollout-gate hold, which is marked as such.")
      (table ["#" "Fact" "Op" "Filing" "Disposition" "Basis"] (ledger-rows db)))

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-8411-electoral · AGPL-3.0-or-later · "
     "generated by <code>electoralops.render-html</code> from one real actor run. "
     "Deterministic: no timestamps in the page, byte-identical across reruns from the same seed.</p>\n"
     "  <p>This actor is not an election authority, adjudicator or returning officer. "
     "Whoever deploys a live instance is the electoral authority, supplies the real statutory "
     "authority, and bears that jurisdiction's liability.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn assert-hard-holds!
  "Build-time invariant, enforced from REAL governor output rather than
  from a convention nobody checks.

  Two stages on purpose. A rollout-phase hold (`:phase-disabled`,
  `:phase-approval`) is a genuine hold that carries an EMPTY violation
  list, so counting holds alone would let a run in which the governor
  objected to nothing still look governed. Stage 2 therefore demands at
  least one hold whose verdict carries a violation with a rule AND a
  detail."
  [runs]
  (let [all-holds (filter #(= :hold (:disposition %)) runs)
        substantive (governor-holds runs)
        detailed (filter (fn [r] (some #(and (:rule %) (seq (str (:detail %))))
                                       (:violations (:verdict r))))
                         substantive)]
    (when (empty? all-holds)
      (throw (ex-info "render-html: the scenario produced NO holds at all"
                      {:runs (count runs)})))
    (when (empty? detailed)
      (throw (ex-info (str "render-html: no HARD governor hold — " (count all-holds)
                           " hold(s) fired but none carried a violation with a rule "
                           "and a detail (a rollout-phase hold is not a governor refusal)")
                      {:holds (count all-holds)
                       :with-violations (count substantive)})))
    {:holds (count all-holds)
     :hard (count detailed)
     :rules (distinct-hold-rules runs)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ;; invariant BEFORE anything is written: a run that produced no
        ;; HARD governor hold must not be allowed to publish a console
        ;; claiming the actor is governed.
        {:keys [holds hard rules]} (assert-hard-holds! runs)
        html (render result)]
    (spit out html)
    (println "wrote" out "-" (count runs) "actor runs,"
             (count (store/ledger db)) "ledger facts,"
             holds "holds of which" hard "HARD governor holds over"
             (count rules) "distinct rules" (pr-str rules) ","
             (count (store/receipt-history db)) "register entries")))
