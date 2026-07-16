(ns airlineops.registry
  "Pure-function operations-coordination record construction -- an
  append-only draft log for the four operations this actor coordinates:
  flight-record logging, flight-operation scheduling, flight-safety-
  concern flagging, and maintenance coordination.

  CRITICAL SCOPE NOTE: every record this namespace builds is a
  COORDINATION DRAFT, never a flight-safety-authority decision. This
  actor does not clear an aircraft for departure, does not override a
  weather/go-no-go hold, and does not sign off a maintenance release
  back to airworthy service -- those are always either a hard,
  permanent governor block (`airlineops.governor`'s finalize-authority-
  scope check) or entirely outside this actor's closed op-allowlist.
  See README `Scope`.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real flight-operations system. It builds the RECORD an
  operations coordinator would keep, not a real-world flight-safety
  act itself."
  (:require [clojure.string :as str]))

(def op->code
  "op -> short record-kind code used in the record id, matching this
  actor's closed op-allowlist exactly."
  {:log-flight-record         "LOG"
   :schedule-flight-operation "SCH"
   :flag-flight-safety-concern "SAF"
   :coordinate-maintenance    "MNT"})

(def op->kind
  "op -> the `:kind` tag stored on the committed record."
  {:log-flight-record         "flight-record-log-draft"
   :schedule-flight-operation "flight-schedule-draft"
   :flag-flight-safety-concern "flight-safety-concern-flag-draft"
   :coordinate-maintenance    "maintenance-coordination-draft"})

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's/authority's act, not this actor's. This actor never
  issues an airworthiness or dispatch-release credential; it only
  drafts a coordination record. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-coordination-record
  "Validate + construct an operations-coordination DRAFT record for
  `op` (one of `airlineops.governor/allowed-ops`) -- pure function,
  does not touch any real flight-operations system; it builds the
  RECORD an operations coordinator would keep. `airlineops.governor`
  independently re-verifies the flight's own certification-verified
  ground truth and open-safety-concern status before this is ever
  allowed to commit."
  [op flight-id jurisdiction sequence]
  (when-not (contains? op->code op)
    (throw (ex-info "register-coordination-record: op must be in the closed allowlist" {:op op})))
  (when-not (and flight-id (not= flight-id ""))
    (throw (ex-info "register-coordination-record: flight_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "register-coordination-record: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "register-coordination-record: sequence must be >= 0" {})))
  (let [code (op->code op)
        kind (op->kind op)
        record-id (str (str/upper-case jurisdiction) "-" code "-" (zero-pad sequence 6))
        record {"record_id" record-id
                "kind" kind
                "op" (name op)
                "flight_id" flight-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "record_id" record-id
     "certificate" (unsigned-certificate kind record-id record-id)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
