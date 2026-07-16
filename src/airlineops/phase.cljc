(ns airlineops.phase
  "Phase 0->3 staged rollout for the community-passenger-air-transport-
  operations actor.

    Phase 0  read-only         -- no writes, still governor-gated.
    Phase 1  assisted-logging  -- flight-record logging and safety-
                                  concern flagging allowed, every write
                                  needs human approval.
    Phase 2  assisted-coord    -- adds scheduling and maintenance-
                                  coordination writes, still approval.
    Phase 3  supervised auto   -- governor-clean, high-confidence
                                  `:log-flight-record` (pure data
                                  logging, no operational/safety risk)
                                  may auto-commit. `:schedule-flight-
                                  operation`/`:coordinate-maintenance`
                                  still always need human approval even
                                  when clean, and `:flag-flight-safety-
                                  concern` NEVER auto-commits, at any
                                  phase.

  `:flag-flight-safety-concern` is deliberately ABSENT from EVERY
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Surfacing a flight-
  safety concern is exactly the kind of proposal that must always
  reach a human, no matter how confident the advisor is or how clean
  the governor's other checks come back. `airlineops.governor`'s
  `high-stakes` gate (keyed on `:coordination/flag-safety-concern`)
  enforces the same invariant independently -- two layers, not one,
  agree on this. See `test/airlineops/phase_test.clj`'s
  `flag-flight-safety-concern-never-auto-at-any-phase`."
  )

(def read-ops  #{})
(def write-ops #{:log-flight-record :schedule-flight-operation
                  :flag-flight-safety-concern :coordinate-maintenance})

;; NOTE the invariant: `:flag-flight-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"          :writes #{}                                                                          :auto #{}}
   1 {:label "assisted-logging"   :writes #{:log-flight-record :flag-flight-safety-concern}                            :auto #{}}
   2 {:label "assisted-coord"     :writes #{:log-flight-record :flag-flight-safety-concern :schedule-flight-operation
                                             :coordinate-maintenance}                                                    :auto #{}}
   3 {:label "supervised-auto"    :writes write-ops
      :auto #{:log-flight-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-flight-safety-concern` is never auto-eligible at any phase,
    so it always escalates once the governor clears it (or holds if
    the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Aviation Safety Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
