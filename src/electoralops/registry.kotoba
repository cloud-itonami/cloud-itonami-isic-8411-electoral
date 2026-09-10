(ns electoralops.registry
  "受理台帳（filing receipt register）の DRAFT を構築する純関数群。

  選挙管理機関が届出を受け取ったときに残す**受理記録**の形を作る。実在の
  選挙システムには触れない —— 作るのは「機関が保持するであろうレコード」で
  あって、受理するという行為そのものではない（それは
  `electoralops.operation` の `:actuation/publish-receipt` で、常に人間を
  通す。README `Actuation` 参照）。

  受理番号の国際標準は存在しない —— 機関ごとに独自の採番をする。この ns は
  **標準を発明しない**。法域スコープの連番と必須項目の検証だけを行う、
  `senkyo` と同じ非創作の規律である。

  `submitted-within-deadline?` は、この fleet の ground-truth-recompute
  ディシプリン（`partyops.registry/member-consensus-share-insufficient?` が
  position 自身の票数から比率を計算し直すのと同型）を、**提出日と法定期限**に
  当てはめたもの。提案が主張した期限は読まない。"
  (:require [kotoba.lang.text :as str]
            [electoralops.intake :as intake]))

(defn- unsigned-certificate
  "この actor が作る証明はすべて UNSIGNED。署名は選挙管理機関自身の行為で
  あって、この actor の行為ではない。"
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_authority" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn submitted-within-deadline?
  "filing 自身の `:submitted-epoch-day` と、`senkyo` から**再計算した**法定
  期限を突き合わせる。提案の主張は一切参照しない。

  `:within` / `:no-deadline` のときだけ true。`:past` はもちろん、
  `:unresolved` / `:unknown-filing` / `:no-basis` でも false —— **期限を
  確認できないことを「期限内」と扱わない。**"
  [filing anchor-epoch-days]
  (contains? #{:within :no-deadline}
             (intake/deadline-status filing anchor-epoch-days)))

(defn register-receipt
  "受理台帳への登載 DRAFT を検証・構築する。純関数。

  `electoralops.governor` が、この手前で filing 自身の提出日・形式審査の
  充足・手続きの spec-basis を独立に再検証し、同じ filing の二重受理も
  ブロックする。"
  [filing-id jurisdiction procedure-id sequence]
  (when-not (and filing-id (not= filing-id ""))
    (throw (ex-info "filing-receipt: filing_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "filing-receipt: jurisdiction required" {})))
  (when (nil? procedure-id)
    (throw (ex-info "filing-receipt: procedure_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "filing-receipt: sequence must be >= 0" {})))
  (let [receipt-number (str (str/upper jurisdiction) "-RCPT-" (zero-pad sequence 6))
        record {"record_id" receipt-number
                "kind" "filing-receipt-draft"
                "filing_id" filing-id
                "jurisdiction" jurisdiction
                "procedure_id" (str procedure-id)
                "immutable" true}]
    {"record" record "receipt_number" receipt-number
     "certificate" (unsigned-certificate "FilingReceipt" receipt-number receipt-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
