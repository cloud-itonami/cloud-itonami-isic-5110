(ns airlineops.reservation-commerce-test
  "The two COMMERCIAL ops (`:quote-fare`, `:place-booking`) and the two
  governor checks that make them safe.

  What is being tested here is different in kind from the operations
  ops: checks 7 and 8 are ground-truth RECOMPUTES against
  `kotoba.reservation`, not restatements of what the advisor claimed.
  The tests therefore all take the same shape -- make the advisor claim
  something false, and assert the governor catches it by recomputing."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [airlineops.airlineopsllm :as llm]
            [airlineops.governor :as governor]
            [airlineops.phase :as phase]
            [airlineops.registry :as registry]
            [airlineops.store :as store]
            [airlineops.operation :as op]
            [kotoba.reservation :as res]))

(def ^:private ctx {:actor-id "op-1" :actor-role :revenue-coordinator :phase 4})

(defn- verdict
  ([db request] (verdict db request identity))
  ([db request f]
   (let [p (f (llm/infer db request))]
     (governor/check request ctx p db))))

(defn- rules [v] (set (map :rule (:violations v))))

;; ---------------------------------------------------------------------------
;; The allowlist grew; the invariants that bind it must have grown too
;; ---------------------------------------------------------------------------

(deftest the-allowlist-and-the-registry-codes-still-name-the-same-ops
  (is (= governor/allowed-ops (set (keys registry/op->code))))
  (is (= governor/allowed-ops (set (keys registry/op->kind))))
  (is (= governor/allowed-ops phase/write-ops))
  (testing "record codes stay distinct, so a record id names exactly one op"
    (is (= (count registry/op->code) (count (set (vals registry/op->code)))))))

(deftest the-commercial-ops-never-auto-commit-at-any-phase
  (doseq [p (keys phase/phases), o governor/priced-ops]
    (is (not (contains? (:auto (get phase/phases p)) o))
        (str o " must never auto-commit at phase " p))))

(deftest the-commercial-ops-are-disabled-until-phase-four
  (doseq [p [0 1 2 3]
          o governor/priced-ops]
    (is (= :phase-disabled (:reason (phase/gate p {:op o} :commit)))
        (str o " must not be writable at phase " p)))
  (doseq [o governor/priced-ops]
    (is (= :phase-approval (:reason (phase/gate 4 {:op o} :commit)))
        (str o " is writable at phase 4, but only via a human"))))

(deftest enabling-commerce-does-not-loosen-the-operations-ops
  (testing "phase 4 still auto-commits only log-flight-record"
    (is (= #{:log-flight-record} (:auto (get phase/phases 4))))))

;; ---------------------------------------------------------------------------
;; Check 7 -- fare recompute
;; ---------------------------------------------------------------------------

(deftest a-fare-the-advisor-did-not-actually-compute-is-hard
  (doseq [o governor/priced-ops]
    (let [v (verdict (store/seed-db) {:op o :subject "flight-5" :qty 1}
                     #(assoc-in % [:value :total] 1))]
      (is (contains? (rules v) :fare-mismatch) (str o))
      (is (:hard? v) (str o " -- a wrong fare is never merely an escalation")))))

(deftest a-correctly-computed-fare-survives-the-recompute
  (let [v (verdict (store/seed-db) {:op :quote-fare :subject "flight-1" :qty 2
                                    :dates [{:date "2026-08-01" :weekday 6}]})]
    (is (not (contains? (rules v) :fare-mismatch)))
    (is (not (:hard? v)))))

(deftest the-governor-re-runs-the-library-rather-than-reimplementing-it
  (let [db (store/seed-db)
        fl (store/flight db "flight-1")
        req {:dates [{:date "2026-08-01" :weekday 6}] :qty 2}
        expected (res/quote-total (res/quote-for (:rate-plan fl) req))
        p (llm/infer db {:op :quote-fare :subject "flight-1" :qty 2
                         :dates [{:date "2026-08-01" :weekday 6}]})]
    (is (= expected (:total (:value p))))
    ;; 42000 base × 2 seats × 1.2 (Saturday) = 100800, + 2900 fee = 103700,
    ;; + 10% tax = 114070. Integer-only, so this is exact on every runtime.
    (is (= 114070 expected))))

(deftest a-fare-that-cannot-be-recomputed-is-a-violation-not-a-pass
  (testing "no stated total"
    (doseq [o governor/priced-ops]
      (let [v (verdict (store/seed-db) {:op o :subject "flight-5" :qty 1}
                       #(update % :value dissoc :total))]
        (is (contains? (rules v) :fare-not-recomputable) (str o)))))
  (testing "no request to price"
    (let [v (verdict (store/seed-db) {:op :quote-fare :subject "flight-5" :qty 1}
                     #(update % :value dissoc :request))]
      (is (contains? (rules v) :fare-not-recomputable))))
  (testing "no filed rate plan on the flight"
    (let [db (store/seed-db)]
      (store/with-flights db (update-in (:flights (store/demo-data)) ["flight-5"] dissoc :rate-plan))
      (let [v (verdict db {:op :quote-fare :subject "flight-5" :qty 1})]
        (is (contains? (rules v) :fare-not-recomputable))))))

(deftest the-operations-ops-are-not-subjected-to-a-fare-check
  (doseq [o (remove governor/priced-ops governor/allowed-ops)]
    (let [v (verdict (store/seed-db) {:op o :subject "flight-1"})]
      (is (not (contains? (rules v) :fare-not-recomputable)) (str o))
      (is (not (contains? (rules v) :fare-mismatch)) (str o)))))

;; ---------------------------------------------------------------------------
;; Check 8 -- oversell recompute
;; ---------------------------------------------------------------------------

(deftest a-booking-past-available-inventory-is-hard
  (testing "a completely full flight"
    (let [v (verdict (store/seed-db) {:op :place-booking :subject "flight-6" :qty 1})]
      (is (contains? (rules v) :oversell))
      (is (:hard? v))))
  (testing "one seat past the last two"
    (let [v (verdict (store/seed-db) {:op :place-booking :subject "flight-5" :qty 3})]
      (is (contains? (rules v) :oversell)))))

(deftest a-booking-for-exactly-the-remaining-seats-is-allowed
  (let [v (verdict (store/seed-db) {:op :place-booking :subject "flight-5" :qty 2})]
    (is (not (contains? (rules v) :oversell))
        "the last two seats are real seats and may be sold")))

(deftest availability-that-cannot-be-recomputed-is-a-violation-not-a-pass
  (testing "no seat bucket on the flight"
    (let [db (store/seed-db)]
      (store/with-flights db (update-in (:flights (store/demo-data)) ["flight-1"] dissoc :bucket))
      (let [v (verdict db {:op :place-booking :subject "flight-1" :qty 1})]
        (is (contains? (rules v) :availability-not-recomputable)))))
  (testing "no requested quantity"
    (let [v (verdict (store/seed-db) {:op :place-booking :subject "flight-1" :qty 1}
                     #(update % :value dissoc :qty))]
      (is (contains? (rules v) :availability-not-recomputable)))))

(deftest only-place-booking-is-subjected-to-the-oversell-check
  (doseq [o (disj governor/allowed-ops :place-booking)]
    (let [v (verdict (store/seed-db) {:op o :subject "flight-6" :qty 1})]
      (is (not (contains? (rules v) :oversell)) (str o)))))

;; ---------------------------------------------------------------------------
;; Commercial scope drift
;; ---------------------------------------------------------------------------

(deftest text-drifting-into-ticketing-or-fare-filing-is-hard
  (doseq [phrase ["issue the ticket" "capture the payment"
                  "file the fare with the authority" "confirm the seat as sold"]]
    (let [v (verdict (store/seed-db) {:op :quote-fare :subject "flight-5" :qty 1}
                     #(update % :rationale str " " phrase))]
      (is (contains? (rules v) :finalize-authority-scope-violation)
          (str "must block: " phrase)))))

(deftest the-commercial-advisor-proposals-never-self-trip-the-scope-gate
  (testing "the advisor legitimately says it does NOT ticket or book revenue;
            a phrase list of bare nouns would fire on its own happy path"
    (let [db (store/seed-db)]
      (doseq [o governor/priced-ops]
        (let [req {:op o :subject "flight-5" :qty 1}
              p (llm/infer db req)]
          (is (not (contains? (rules (governor/check req ctx p db))
                              :finalize-authority-scope-violation))
              (str o " self-tripped; rationale was: " (:rationale p))))))))

;; ---------------------------------------------------------------------------
;; End to end through the actor graph
;; ---------------------------------------------------------------------------

(defn- exec [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest an-approved-booking-moves-inventory-and-only-as-a-hold
  (let [db (store/seed-db)
        actor (op/build db)
        r (exec actor "b1" {:op :place-booking :subject "flight-5" :qty 2
                            :hold-id "h-1" :expires-at "2026-07-27T12:00:00Z"} ctx)]
    (is (= :interrupted (:status r)) "a booking always reaches a human first")
    (is (zero? (:inv/held (:bucket (store/flight db "flight-5"))))
        "and moves nothing until that human approves")
    (let [r2 (approve! actor "b1")
          fl (store/flight db "flight-5")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 2 (:inv/held (:bucket fl))) "the hold is placed")
      (is (= 68 (:inv/sold (:bucket fl)))
          "and NOT sold -- ticketing is outside this actor's op-allowlist")
      (is (zero? (res/available (:bucket fl)))))))

(deftest a-held-booking-writes-a-record-carrying-the-verified-payload
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec actor "b2" {:op :quote-fare :subject "flight-5" :qty 1} ctx)
    (approve! actor "b2")
    (let [rec (last (store/coordination-history db))]
      (is (= "USA-FAR-000000" (get rec "record_id")))
      (is (= "fare-quote-draft" (get rec "kind")))
      (is (some? (get-in rec ["payload" :total]))
          "the recomputed total is carried onto the record, not discarded"))))

(deftest an-oversold-booking-never-reaches-a-human-and-writes-nothing
  (let [db (store/seed-db)
        actor (op/build db)
        r (exec actor "b3" {:op :place-booking :subject "flight-6" :qty 1} ctx)]
    (is (= :hold (get-in r [:state :disposition])))
    (is (not= :interrupted (:status r))
        "a human is never offered the choice to oversell")
    (is (empty? (store/coordination-history db)))
    (is (= 60 (:inv/sold (:bucket (store/flight db "flight-6")))))
    (is (zero? (:inv/held (:bucket (store/flight db "flight-6")))))))

(deftest the-ssot-write-refuses-to-oversell-even-if-the-governor-were-bypassed
  (testing "belt and braces: the write itself re-checks availability"
    (doseq [[label db] {"MemStore" (store/seed-db) "DatomicStore" (store/datomic-seed-db)}]
      (testing label
        (is (thrown? clojure.lang.ExceptionInfo
                     (store/commit-record! db {:effect :propose :op :place-booking
                                               :path ["flight-6"] :payload {:qty 1}})))))))

(deftest both-backends-round-trip-the-reservation-values
  (doseq [[label db] {"MemStore" (store/seed-db) "DatomicStore" (store/datomic-seed-db)}]
    (testing label
      (let [fl (store/flight db "flight-1")]
        (is (= 180 (:inv/capacity (:bucket fl))))
        (is (= 100 (:inv/sold (:bucket fl))))
        (is (= 42000 (:rate/base-amount (:rate-plan fl))))
        (is (= {6 12000} (:rate/weekday-bp (:rate-plan fl)))
            "an integer-keyed map must not be stringified by the blob codec")
        (is (= 1000 (:rate/tax-bp (:rate-plan fl))))))))

(deftest committing-a-booking-moves-inventory-identically-in-both-backends
  (doseq [[label db] {"MemStore" (store/seed-db) "DatomicStore" (store/datomic-seed-db)}]
    (testing label
      (store/commit-record! db {:effect :propose :op :place-booking :path ["flight-1"]
                                :payload {:qty 2 :hold-id "h-1" :expires-at "2026-07-27T12:00:00Z"}})
      (let [fl (store/flight db "flight-1")]
        (is (= 2 (:inv/held (:bucket fl))))
        (is (= 100 (:inv/sold (:bucket fl))))
        (is (= 78 (res/available (:bucket fl))) "180 capacity - 100 sold - 2 held")))))

(deftest quoting-never-moves-inventory-in-either-backend
  (doseq [[label db] {"MemStore" (store/seed-db) "DatomicStore" (store/datomic-seed-db)}]
    (testing label
      (let [before (:bucket (store/flight db "flight-1"))]
        (store/commit-record! db {:effect :propose :op :quote-fare :path ["flight-1"]
                                  :payload {:total 114070}})
        (is (= before (:bucket (store/flight db "flight-1")))
            "a quote is a draft; it commits no seat")))))
