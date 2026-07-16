(ns airlineops.airlineopsllm
  "AirlineOps-LLM client -- the *contained intelligence node* for the
  community-passenger-air-transport-operations actor.

  It drafts flight/passenger-manifest/on-time-performance logging
  entries, drafts schedule/gate/crew-assignment coordination proposals,
  drafts flight-safety-concern escalation flags, and drafts aircraft-
  maintenance coordination proposals. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record and NEVER a real flight-
  safety-authority decision. Every output is censored downstream by
  `airlineops.governor` before anything touches the SSoT.

  SCOPE, stated explicitly and repeatedly because it is the single
  most important invariant this actor has: this advisor NEVER proposes
  to clear an aircraft for departure, NEVER proposes to override a
  weather or mechanical go/no-go hold, and NEVER proposes a
  maintenance RELEASE back to airworthy service. It only proposes
  operations-COORDINATION records -- logging, scheduling, concern-
  flagging, and maintenance-coordination drafts. `:effect` is ALWAYS
  `:propose`, never a direct actuation effect. See README `Scope`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all four ops):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis AND
                                 ;   finalize-authority-scope gates
     :cites      [str ..]       ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a real actuation
     :stake      kw|nil         ; :coordination/flag-safety-concern | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [airlineops.facts :as facts]
            [airlineops.store :as store]
            [langchain.model :as model]))

(defn- confidence-for [fl cites]
  (if (and fl (seq cites) (:certification-verified? fl)
           (not (and (:safety-concern-raised? fl) (not (:safety-concern-resolved? fl)))))
    0.92
    0.2))

(defn- propose-log-flight-record
  "Draft a flight-record log entry -- flight/passenger-manifest/on-time-
  performance data. Pure data logging, no actuation, the lowest-risk
  op in this domain (the ONLY one this actor's phase table ever allows
  to auto-commit, see `airlineops.phase`)."
  [db {:keys [subject patch]}]
  (let [fl (store/flight db subject)
        iso3 (:jurisdiction fl)
        cites (facts/citation iso3)]
    {:summary   (str subject " 運航記録(便名/乗客名簿/定時運航実績)のログ提案"
                     (when (seq patch) (str " patch=" (pr-str (keys patch)))))
     :rationale (str "証明書検証済み=" (boolean (:certification-verified? fl))
                     " -- 入力patchの正規化のみ、新規事実の生成なし。")
     :cites     (vec cites)
     :effect    :propose
     :value     (merge {:kind :flight-record-log} (or patch {}))
     :stake     nil
     :confidence (confidence-for fl cites)}))

(defn- propose-schedule-flight-operation
  "Draft a gate/crew/schedule coordination proposal -- logistics
  coordination only, NOT a runway-occupancy or departure-clearance
  authorization (that remains outside this actor's remit, a real
  air-traffic-control/dispatch-authority act)."
  [db {:keys [subject gate crew]}]
  (let [fl (store/flight db subject)
        iso3 (:jurisdiction fl)
        cites (facts/citation iso3)]
    {:summary   (str subject " 向けゲート/乗務員スケジュール調整案"
                     (when gate (str " gate=" gate)) (when crew (str " crew=" crew)))
     :rationale (str "証明書検証済み=" (boolean (:certification-verified? fl))
                     " -- ゲート/乗務員配置の調整提案。滑走路占有や出発許可等の運航認可事項ではない。")
     :cites     (vec cites)
     :effect    :propose
     :value     (cond-> {:kind :flight-schedule} gate (assoc :gate gate) crew (assoc :crew crew))
     :stake     nil
     :confidence (confidence-for fl cites)}))

(defn- propose-flag-flight-safety-concern
  "Draft a flight-safety-concern escalation flag -- surfaces a reported
  mechanical-fault or weather concern to the flight-safety authority.
  ALWAYS `:stake :coordination/flag-safety-concern` -- ALWAYS escalates
  to human sign-off, at every phase, regardless of confidence or
  governor cleanliness (`airlineops.governor`'s high-stakes gate AND
  `airlineops.phase`'s phase table, which never adds this op to any
  phase's `:auto` set, independently agree). This advisor explicitly
  does NOT determine whether the flight should proceed -- it only
  proposes that the concern be recorded and routed to a human."
  [db {:keys [subject concern-kind detail]}]
  (let [fl (store/flight db subject)
        iso3 (:jurisdiction fl)
        cites (facts/citation iso3)]
    {:summary   (str subject " の運航安全上の懸念(" (or concern-kind "unspecified") ")を報告"
                     (when detail (str " -- " detail)))
     :rationale (str "報告された懸念(整備上の不具合の可能性や気象条件など)を運航安全部門へ"
                     "エスカレートするための記録提案。本アクターはこの懸念について進行/中止の"
                     "判断そのものは行わない -- 常に人間(運航安全部門)の承認へ回付する。")
     :cites     (vec cites)
     :effect    :propose
     :value     (cond-> {:kind :flight-safety-concern-flag}
                  concern-kind (assoc :concern-kind concern-kind)
                  detail (assoc :detail detail))
     :stake     :coordination/flag-safety-concern
     :confidence 0.85}))

(defn- propose-coordinate-maintenance
  "Draft an aircraft-maintenance COORDINATION proposal -- scheduling a
  maintenance slot, requesting parts, or dispatching ground crew. This
  is NOT a maintenance RELEASE / return-to-service sign-off (that
  remains outside this actor's remit, a real certifying-authority
  act -- see README `Scope`)."
  [db {:keys [subject maintenance-kind]}]
  (let [fl (store/flight db subject)
        iso3 (:jurisdiction fl)
        cites (facts/citation iso3)]
    {:summary   (str subject " 向け整備調整案(" (or maintenance-kind "unspecified") ")")
     :rationale (str "証明書検証済み=" (boolean (:certification-verified? fl))
                     " -- 整備スケジュール調整の提案。整備完了後の耐空性復帰承認(リリース)ではない。")
     :cites     (vec cites)
     :effect    :propose
     :value     (cond-> {:kind :maintenance-coordination} maintenance-kind (assoc :maintenance-kind maintenance-kind))
     :stake     nil
     :confidence (confidence-for fl cites)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-flight-record          (propose-log-flight-record db request)
    :schedule-flight-operation  (propose-schedule-flight-operation db request)
    :flag-flight-safety-concern (propose-flag-flight-safety-concern db request)
    :coordinate-maintenance     (propose-coordinate-maintenance db request)
    {:summary "未対応の操作 -- 閉じたoperations-coordination許可リストの範囲外"
     :rationale (str op) :cites [] :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域旅客航空運航事業者の運航コーディネーション・エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に :propose) "
       ":stake(:coordination/flag-safety-concern か nil) :confidence(0..1)。\n"
       "重要: あなたは出発可否・go/no-go判断・整備リリース承認を一切行いません。"
       "登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "証明書検証状況や運航安全上の懸念の有無を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [subject]}]
  {:flight (store/flight st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Aviation Safety Governor
  escalates/holds -- an LLM hiccup can never auto-commit anything, and
  can certainly never finalize a flight-safety decision."
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
  {:t          :airlineopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
