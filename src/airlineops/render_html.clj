(ns airlineops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`airlineops.operation` -> `airlineops.governor` ->
  `airlineops.store`) through a scenario adapted from this repo's own
  `airlineops.sim` demo driver (`clojure -M:dev:run`, confirmed
  BEFORE writing this file to produce a sensible ledger against the
  real seeded flight ids `flight-1`..`flight-6` -- so it was safe to
  reuse rather than author from scratch), trimmed to a representative
  subset (one full ops-coordination lifecycle through a safety-concern
  flag, and multiple distinct HARD-hold reasons including commercial
  oversell / fare-mismatch) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verify by diffing two
  consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [airlineops.airlineopsllm :as llm]
            [airlineops.store :as store]
            [airlineops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :ops-coordinator :phase 3})

;; Commercial ops only become writable at phase 4 (see airlineops.phase).
(def ^:private commercial-operator
  {:actor-id "op-1" :actor-role :revenue-coordinator :phase 4})

(defn- lying-advisor
  "Advisor that states a fare it did not compute -- the failure mode
  the governor's independent recompute exists to catch."
  []
  (reify llm/Advisor
    (-advise [_ st req] (assoc-in (llm/infer st req) [:value :total] 1))))

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: flight-1 clears a full ops lifecycle -- log
  (auto-commit clean at phase 3), schedule (approved), coordinate-
  maintenance (approved), flag-flight-safety-concern (ALWAYS escalates
  -- approved); a second log on flight-1 then HARD-holds on the open
  unresolved safety concern; flight-2 HARD-holds on no-spec-basis
  (jurisdiction ATL is not a known civil-aviation authority in
  airlineops.facts); flight-3 HARD-holds on certification-unverified;
  flight-4 HARD-holds schedule because it already carries an open
  unresolved safety concern; flight-6 HARD-holds place-booking because
  the governor's independent availability recompute rejects the oversell;
  and a quote-fare via a lying advisor HARD-holds on fare-mismatch.
  Every HARD hold never reaches a human. Returns the resulting store --
  every field read by `render` below is real governor/store output, not
  a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1-log" {:op :log-flight-record :subject "flight-1"
                           :patch {:on-time? true :passengers 128}} operator)

    (exec! actor "t1-sched" {:op :schedule-flight-operation :subject "flight-1"
                             :gate "B12" :crew "crew-4"} operator)
    (approve! actor "t1-sched")

    (exec! actor "t1-maint" {:op :coordinate-maintenance :subject "flight-1"
                             :maintenance-kind :routine-check} operator)
    (approve! actor "t1-maint")

    (exec! actor "t1-flag" {:op :flag-flight-safety-concern :subject "flight-1"
                            :concern-kind :mechanical :detail "hydraulic pressure warning"}
           operator)
    (approve! actor "t1-flag")

    ;; Open unresolved safety concern blocks further non-flag ops.
    (exec! actor "t1-log-again" {:op :log-flight-record :subject "flight-1"
                                 :patch {:on-time? false}} operator)

    (exec! actor "t2-log" {:op :log-flight-record :subject "flight-2"
                           :patch {:on-time? true}} operator)

    (exec! actor "t3-log" {:op :log-flight-record :subject "flight-3"
                           :patch {:on-time? true}} operator)

    (exec! actor "t4-sched" {:op :schedule-flight-operation :subject "flight-4"
                             :gate "A3"} operator)

    ;; Commercial surface (phase 4): full flight -> oversell HARD hold.
    (exec! actor "t6-book" {:op :place-booking :subject "flight-6" :qty 1}
           commercial-operator)

    ;; Lying advisor states a total the governor recomputes differently.
    ;; Use flight-5 (clean, verified, known jurisdiction, seats free).
    (let [liar (op/build db {:advisor (lying-advisor)})]
      (exec! liar "t5-quote-lie" {:op :quote-fare :subject "flight-5" :qty 1}
             commercial-operator))
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger flight-id]
  (last (filter #(= (:subject %) flight-id) ledger)))

(defn- status-cell [ledger flight-id]
  (let [f (last-fact-for ledger flight-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- bool-cell [v]
  (if v "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>"))

(defn- concern-cell [{:keys [safety-concern-raised? safety-concern-resolved?]}]
  (cond
    (and safety-concern-raised? (not safety-concern-resolved?))
    "<span class=\"critical\">open, unresolved</span>"
    (and safety-concern-raised? safety-concern-resolved?)
    "<span class=\"ok\">raised &amp; resolved</span>"
    :else "<span class=\"muted\">none</span>"))

(defn- flight-row [ledger {:keys [id flight-number origin destination jurisdiction
                                  certification-verified?] :as fl}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s→%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc flight-number)
          (esc origin) (esc destination)
          (esc jurisdiction)
          (bool-cell certification-verified?)
          (concern-cell fl)
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", "))
                   (some-> disposition name)
                   ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Ops`, `airlineops.governor`/`airlineops.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-flight-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital/safety risk</span></td></tr>"
   "        <tr><td><code>:schedule-flight-operation</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span></td></tr>"
   "        <tr><td><code>:coordinate-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span></td></tr>"
   "        <tr><td><code>:flag-flight-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; permanently high-stakes</span></td></tr>"
   "        <tr><td><code>:quote-fare</code></td><td><span class=\"warn\">phase-4 only &middot; ALWAYS human approval &middot; governor recomputes fare independently</span></td></tr>"
   "        <tr><td><code>:place-booking</code></td><td><span class=\"warn\">phase-4 only &middot; ALWAYS human approval &middot; governor recomputes availability &middot; hold only, never a sale</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        flights (store/all-flights db)
        flight-rows (str/join "\n" (map (partial flight-row ledger) flights))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-5110 &middot; passenger-air-transport</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Passenger air transport (ISIC 5110) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · safety-concern flag always human-approved · commercial holds never a sale</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Flights</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>airlineops.store</code> via <code>airlineops.render-html</code> (<code>clojure -M:dev:render-html</code>). Every row is real governor/store output, not scaffolding.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Flight</th><th>Number</th><th>Route</th><th>Jurisdiction</th><th>Certification verified</th><th>Safety concern</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     flight-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Aviation Safety Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Certification verification and open safety concerns are independently re-derived from the store. Fare totals and seat availability are recomputed by the governor against the flight's own filed rate plan and seat bucket — never trusted from the advisor. Finalizing a go/no-go, clearing an aircraft for departure, or issuing a ticket is permanently out of scope.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Flight</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        parent (.getParentFile (java.io.File. out))]
    (when parent (.mkdirs parent))
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-history db)) "coordination records )")))
