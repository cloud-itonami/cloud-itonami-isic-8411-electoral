(ns electoralops.electoralopsllm
  "ElectoralOps-LLM client —— 受領側の *contained intelligence node*。

  届出の受付記録を正規化し、形式審査のチェックリストを起案し、不備通知の
  文面を起案し、受理台帳への登載を起案する。CRITICAL: これは賢いが信用され
  ていない助言者である。返すのは常に *proposal*（根拠と参照した事実つき）で
  あって、committed record でも実際の受理でもない。すべての出力は
  `electoralops.governor` が SSoT に触れる前に検閲し、
  `:actuation/publish-receipt` はどの phase でも auto-commit しない。

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM with the same
  proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/publish-receipt | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [electoralops.intake :as intake]
            [electoralops.store :as store]
            [langchain.model :as model]))

(defn- normalize-receive
  "受付記録の upsert —— LLM は patch の正規化だけを行い、届出の内容も提出日も
  法域も発明しない。"
  [_db {:keys [patch]}]
  {:summary    (str "届出受付記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :filing/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- draft-formal-review
  "形式審査の起案。`:no-basis?` が注入する失敗モードは、**未収録の手続きの
  審査項目を推測で作る**こと —— governor はこれを拒否しなければならない。"
  [db {:keys [subject no-basis? intents]}]
  (let [f (store/filing db subject)
        iso3 (if no-basis? "ATL" (:jurisdiction f))
        pid (if no-basis? :atl-unknown (:procedure-id f))
        items (intake/formal-review-items iso3 pid)]
    (if (nil? items)
      {:summary    (str iso3 "/" pid " の手続き spec-basis が見つかりません")
       :rationale  "senkyo.procedure に未収録の手続き。審査項目を推測で作らない。"
       :cites      []
       :effect     :formal-review/set
       :value      {:filing-id subject :items [] :spec-basis nil
                    :intents (vec (or intents []))}
       :stake      nil
       :confidence 0.9}
      {:summary    (str (:filing-name f) ": 形式審査項目 " (count items) " 件")
       :rationale  (str "根拠法: " (:proc/legal-basis (intake/procedure-basis iso3 pid))
                        " / 受領者: " (:proc/authority (intake/procedure-basis iso3 pid)))
       :cites      [(:proc/legal-basis (intake/procedure-basis iso3 pid))]
       :effect     :formal-review/set
       :value      {:filing-id subject
                    :items items
                    :outstanding (intake/outstanding-review-items f)
                    :spec-basis (:proc/legal-basis (intake/procedure-basis iso3 pid))
                    :intents (vec (or intents []))}
       :stake      nil
       :confidence 0.9})))

(defn- draft-deficiency-notice
  "不備通知の起案。未充足の形式審査項目を並べるだけで、**受理しない決定は
  しない**（それは `:acceptance-refusal` で構造的に不在）。"
  [db {:keys [subject intents]}]
  (let [f (store/filing db subject)
        outstanding (intake/outstanding-review-items f)]
    {:summary    (str (:filing-name f) ": 補正を要する項目 "
                      (if outstanding (count outstanding) "不明"))
     :rationale  (if outstanding
                   (str "未充足: " (str/join ", " outstanding))
                   "手続きが表に無いため未充足項目を列挙できない")
     :cites      (if outstanding [(:procedure-id f)] [])
     :effect     :deficiency/set
     :value      {:filing-id subject :outstanding (vec (or outstanding []))
                  :intents (vec (or intents []))}
     :stake      nil
     :confidence (if outstanding 0.9 0.3)}))

(defn- propose-receipt
  "受理台帳への登載の起案 —— 候補者の地位に法的効果を持つ実世界の行政行為。
  ALWAYS `:stake :actuation/publish-receipt`。どの phase でも auto にならず、
  governor も独立に escalate する。"
  [db {:keys [subject intents]}]
  (let [f (store/filing db subject)
        complete? (intake/formal-review-complete? f)]
    {:summary    (str subject " の受理台帳登載提案"
                      (when f (str " (" (:filing-name f) ")")))
     :rationale  (if f
                   (str "法域=" (:jurisdiction f) " 手続き=" (:procedure-id f)
                        " 提出日=" (:submitted-epoch-day f)
                        " 形式審査完了=" complete?)
                   "届出が見つかりません")
     :cites      (if f [subject] [])
     :effect     :filing/enter-receipt
     :value      {:filing-id subject :intents (vec (or intents []))}
     :stake      :actuation/publish-receipt
     :confidence (if complete? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :filing/receive               (normalize-receive db request)
    :review/formal                (draft-formal-review db request)
    :deficiency/notice            (draft-deficiency-notice db request)
    :actuation/publish-receipt    (propose-receipt db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは選挙管理機関の受領事務エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:filing/upsert|:formal-review/set|:deficiency/set|:filing/enter-receipt) "
       ":stake(:actuation/publish-receipt か nil) :confidence(0..1)。\n"
       "重要: 登録されていない手続きの受領要件・審査項目・期限を絶対に創作しては"
       "いけません。spec-basisが無い場合は :cites を空にし confidence を上げないこと。\n"
       "重要: 受理しない決定・候補者の適格性の実質判断・当落の確定は、あなたの"
       "扱う範囲ではありません。それらは人間の選挙管理機関の判断です。"))

(defn- facts-for [st {:keys [subject]}]
  {:filing (store/filing st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Electoral Administration
  Governor escalates/holds -- an LLM hiccup can never auto-enter a
  filing in the public register."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :electoralopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
