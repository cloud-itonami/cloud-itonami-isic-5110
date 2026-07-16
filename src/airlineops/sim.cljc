(ns airlineops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks flight-1 through a clean
  operations-coordination lifecycle (log -> schedule -> coordinate
  maintenance -> flag a safety concern), then shows what flagging the
  concern does to the SAME flight (blocks further logging), then walks
  through the remaining HARD-hold scenarios: an unknown jurisdiction
  with no spec-basis, an aircraft whose certification has not been
  independently verified, and a flight that already has an unresolved
  safety concern on file (which still allows flagging, just not the
  other three ops).

  Like every sibling actor's own new checks, this actor's checks
  (`certification-unverified?`, `open-safety-concern-blocks-op?`,
  `finalize-authority-scope-violation?`) are evaluated directly at
  proposal time rather than via a separate screening op -- following
  the SAME 'exercise the failure mode directly, never only via a
  happy-path actuation' discipline this fleet establishes."
  (:require [langgraph.graph :as g]
            [airlineops.store :as store]
            [airlineops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :ops-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-flight-record flight-1 (JPN, clean -- auto-commits, no capital/safety risk) ==")
    (println (exec-op actor "t1" {:op :log-flight-record :subject "flight-1"
                                  :patch {:on-time? true :passengers 128}} operator))

    (println "== schedule-flight-operation flight-1 (escalates -- human approves) ==")
    (let [r (exec-op actor "t2" {:op :schedule-flight-operation :subject "flight-1" :gate "B12" :crew "crew-4"} operator)]
      (println r)
      (println (approve! actor "t2")))

    (println "== coordinate-maintenance flight-1 (escalates -- human approves) ==")
    (let [r (exec-op actor "t3" {:op :coordinate-maintenance :subject "flight-1" :maintenance-kind :routine-check} operator)]
      (println r)
      (println (approve! actor "t3")))

    (println "== flag-flight-safety-concern flight-1 (ALWAYS escalates -- human approves) ==")
    (let [r (exec-op actor "t4" {:op :flag-flight-safety-concern :subject "flight-1"
                                 :concern-kind :mechanical :detail "hydraulic pressure warning"} operator)]
      (println r)
      (println "-- human safety coordinator approves the FLAG (not a go/no-go determination) --")
      (println (approve! actor "t4")))

    (println "== log-flight-record flight-1 AGAIN (now blocked -- open unresolved safety concern -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-flight-record :subject "flight-1" :patch {:on-time? false}} operator))

    (println "== log-flight-record flight-2 (ATL, no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-flight-record :subject "flight-2" :patch {:on-time? true}} operator))

    (println "== log-flight-record flight-3 (JPN, certification NOT independently verified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-flight-record :subject "flight-3" :patch {:on-time? true}} operator))

    (println "== schedule-flight-operation flight-4 (JPN, already has an open unresolved safety concern -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :schedule-flight-operation :subject "flight-4" :gate "A3"} operator))

    (println "== flag-flight-safety-concern flight-4 (still reachable even with an open concern -- escalates -- human approves) ==")
    (let [r (exec-op actor "t9" {:op :flag-flight-safety-concern :subject "flight-4"
                                 :concern-kind :weather :detail "crosswind advisory"} operator)]
      (println r)
      (println (approve! actor "t9")))

    (println "== coordinate-maintenance flight-5 (USA, clean -- escalates -- human approves) ==")
    (let [r (exec-op actor "t10" {:op :coordinate-maintenance :subject "flight-5" :maintenance-kind :parts-request} operator)]
      (println r)
      (println (approve! actor "t10")))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft operations-coordination records ==")
    (doseq [r (store/coordination-history db)] (println r))))
