(ns airlineops.governor
  "Aviation Safety Governor -- the independent compliance layer that
  earns AirlineOps-LLM the right to commit. The LLM has no notion of
  which jurisdiction's civil-aviation-authority law is official,
  whether a flight's own aircraft/Air-Operator-Certificate record has
  actually been independently verified and registered, whether an open
  flight-safety concern is still unresolved on a flight, or when a
  drafted proposal has drifted into actually FINALIZING a flight-
  safety-authority decision rather than merely coordinating around
  one, so this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD.

  SCOPE, the single invariant this governor exists to enforce: this
  actor is a passenger-airline OPERATIONS COORDINATION actor, NOT a
  flight-safety authority and NOT aircraft control. It never clears an
  aircraft for departure, never overrides a weather/mechanical go/no-go
  hold, and never signs off a maintenance release back to airworthy
  service. Those acts are always either (a) structurally absent from
  this actor's closed op-allowlist, or (b) an explicit HARD, PERMANENT,
  un-overridable block if a proposal's own text drifts toward one
  anyway (`finalize-authority-scope-violations` below) -- defense in
  depth, not merely 'the op list doesn't include it'.

  KNOWN BUG CLASS THIS GOVERNOR DELIBERATELY AVOIDS: a scope-exclusion
  term list phrased as a bare noun (e.g. \"safety\", \"go-no-go\",
  \"weather\") will accidentally match inside this actor's OWN mock
  advisor's default rationale text for a legitimate, allowed proposal
  -- `airlineops.airlineopsllm/propose-flag-flight-safety-concern`
  itself legitimately talks about mechanical faults and weather as the
  CONTENT of a concern being flagged, and `no-spec-basis`-style hard
  holds would then fire on the actor's own happy path. This governor's
  `finalize-authority-phrases` are therefore phrased as the
  FINALIZATION/EXECUTION ACTION itself (\"finalize the go-no-go
  decision\", \"clear the aircraft for departure\", \"override the
  weather hold decision\") rather than the bare topic noun -- see
  `test/airlineops/governor_contract_test.clj`'s
  `default-advisor-proposals-never-self-trip-finalize-authority-scope`
  test, which asserts this directly against every default proposal for
  all four ops.

  Five checks, ALL HARD violations: a human approver CANNOT override
  them. The confidence/high-stakes gate is SOFT: it asks a human to
  look (low confidence, or `:flag-flight-safety-concern`'s dedicated
  stake) -- but see `airlineops.phase`: `:flag-flight-safety-concern`
  is NEVER a member of any phase's `:auto` set either. Two independent
  layers agree that flagging a safety concern always reaches a human.

    1. Op not allowed             -- the closed allowlist
                                      (`allowed-ops`) is the ONLY
                                      vocabulary this actor has.
                                      Evaluated UNCONDITIONALLY.
    2. Effect not :propose        -- this actor NEVER actuates
                                      directly; every committed record
                                      is a coordination draft, never a
                                      real-world act. Evaluated
                                      UNCONDITIONALLY.
    3. Finalize-authority scope   -- the proposal's own summary/
                                      rationale/cites text must not
                                      contain a flight-safety-authority
                                      FINALIZATION action phrase. A
                                      HARD, PERMANENT block -- never
                                      overridable by a human approver,
                                      because a human should never be
                                      offered the choice to approve a
                                      proposal that has drifted into
                                      finalizing a go/no-go or a
                                      maintenance release.
    4. Certification unverified   -- INDEPENDENTLY verify the flight's
                                      own `:certification-verified?`
                                      ground truth is true before ANY
                                      of the four ops may proceed.
                                      Evaluated UNCONDITIONALLY.
    5. Open safety concern blocks -- an unresolved flight-safety
                                      concern already on file
                                      (`:safety-concern-raised? true`
                                      AND `:safety-concern-resolved?
                                      false`) blocks `:log-flight-
                                      record`/`:schedule-flight-
                                      operation`/`:coordinate-
                                      maintenance` on that flight (NOT
                                      `:flag-flight-safety-concern`
                                      itself, which must always remain
                                      reachable to report further
                                      detail on an already-open
                                      concern).

  A sixth check, no-spec-basis, is evaluated UNCONDITIONALLY across all
  four ops: every coordination proposal must cite an official civil-
  aviation-authority source for the flight's own jurisdiction
  (`airlineops.facts`), never an invented one."
  (:require [clojure.string :as str]
            [airlineops.facts :as facts]
            [airlineops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed operations-coordination op-allowlist. This actor has NO
  other vocabulary -- a proposal naming any other op is a HARD
  violation, not merely unrecognized."
  #{:log-flight-record :schedule-flight-operation
    :flag-flight-safety-concern :coordinate-maintenance})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Flagging a flight-safety concern is the one op in this domain that
  always escalates -- a single-member set, the SAME structural shape
  every sibling's own always-escalate stake establishes."
  #{:coordination/flag-safety-concern})

(def finalize-authority-phrases
  "Flight-safety-authority FINALIZATION/EXECUTION action phrases --
  phrased as the ACT of deciding/executing, never a bare topic noun
  (\"safety\"/\"weather\"/\"go-no-go\" alone are NOT here -- see the ns
  docstring's KNOWN BUG CLASS note). Any proposal whose summary/
  rationale/cites text contains one of these (case-insensitive) is a
  HARD, PERMANENT, un-overridable block."
  #{"finalize the go-no-go decision"
    "finalize the go/no-go decision"
    "override the go-no-go decision"
    "override the weather hold decision"
    "override the weather hold"
    "clear the aircraft for departure"
    "authorize departure despite the reported fault"
    "authorize departure despite the mechanical fault"
    "sign off the maintenance release"
    "approve the maintenance release back to service"
    "return the aircraft to service"})

;; ----------------------------- checks -----------------------------

(defn- op-not-allowed-violations
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " は閉じたoperations-coordination許可リストの範囲外 -- このアクターは実行しない")}]))

(defn- effect-not-propose-violations
  [_request proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "effect=" (:effect proposal) " は :propose ではない -- このアクターは直接actuateしない")}]))

(defn- finalize-authority-scope-violations
  "The proposal's own text drifting toward FINALIZING a flight-safety-
  authority decision is a HARD, PERMANENT block -- see ns docstring."
  [_request proposal]
  (let [text (str/lower-case (str (:summary proposal) " " (:rationale proposal) " " (pr-str (:cites proposal))))]
    (when (some #(str/includes? text %) finalize-authority-phrases)
      [{:rule :finalize-authority-scope-violation
        :detail "提案テキストが運航安全当局の最終判断/実行行為に該当する -- 恒久的にブロック、人間承認でも解除不可"}])))

(defn- no-spec-basis-violations
  "Every coordination proposal must cite an official civil-aviation-
  authority source for the flight's own jurisdiction -- never invent
  one. Evaluated UNCONDITIONALLY across all four ops."
  [{:keys [subject]} st proposal]
  (let [fl (store/flight st subject)
        iso3 (:jurisdiction fl)]
    (when (or (empty? (:cites proposal)) (not (facts/known-jurisdiction? iso3)))
      [{:rule :no-spec-basis
        :detail (str iso3 " の公式spec-basisの引用が無い提案は法域要件として扱えない")}])))

(defn- certification-unverified-violations
  "INDEPENDENTLY verify the flight's own `:certification-verified?`
  ground truth is true before ANY of the four ops may proceed.
  Evaluated UNCONDITIONALLY."
  [{:keys [subject]} st]
  (let [fl (store/flight st subject)]
    (when-not (true? (:certification-verified? fl))
      [{:rule :certification-unverified
        :detail (str subject " は独立検証済みの耐空証明/運航証明記録が無い -- いかなる提案も進められない")}])))

(defn- open-safety-concern-violations
  "An unresolved flight-safety concern already on file blocks the
  OTHER three ops on that flight -- `:flag-flight-safety-concern`
  itself is exempt so the safety-reporting channel always stays open."
  [{:keys [op subject]} st]
  (when (not= op :flag-flight-safety-concern)
    (let [fl (store/flight st subject)]
      (when (and (true? (:safety-concern-raised? fl)) (not (true? (:safety-concern-resolved? fl))))
        [{:rule :open-safety-concern-blocks-op
          :detail (str subject " は未解決の運航安全上の懸念がある -- flag以外の提案は進められない")}]))))

(defn check
  "Censors an AirlineOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (finalize-authority-scope-violations request proposal)
                           (no-spec-basis-violations request st proposal)
                           (certification-unverified-violations request st)
                           (open-safety-concern-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
