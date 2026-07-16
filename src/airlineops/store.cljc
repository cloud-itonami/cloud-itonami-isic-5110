(ns airlineops.store
  "SSoT for the community-passenger-air-transport-operations actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every prior `cloud-itonami-isic-*` actor in this fleet
  uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/airlineops/store_contract_test.clj), which is the whole point:
  the actor, the Aviation Safety Governor and the audit ledger never
  know which SSoT they run on.

  `:certification-verified?` is GROUND TRUTH this actor CONSUMES, never
  MINTS -- it represents the flight's own aircraft/Air-Operator-
  Certificate record, independently verified and registered by a real
  civil aviation authority (or this actor's operator, standing in for
  one, in a real deployment) OUTSIDE this actor's own closed op-
  allowlist. None of `:log-flight-record`/`:schedule-flight-operation`/
  `:flag-flight-safety-concern`/`:coordinate-maintenance` ever sets it
  -- see README `Scope`.

  `:safety-concern-raised?`/`:safety-concern-resolved?` are dedicated
  booleans (never a single `:status` value), mirroring the SAME
  discipline every prior sibling governor's guards establish: a
  flagged, unresolved flight-safety concern blocks the OTHER three ops
  on that flight (`airlineops.governor`'s open-safety-concern check) --
  but resolving a concern is likewise OUTSIDE this actor's own op-
  allowlist (a real flight-safety authority's call, not this actor's)."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [airlineops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (flight [s id])
  (all-flights [s])
  (ledger [s])
  (coordination-history [s] "the append-only operations-coordination history (airlineops.registry drafts, all four op kinds)")
  (next-sequence [s jurisdiction op] "next record-id sequence for a jurisdiction+op pair")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-flights [s flights] "replace/seed the flight directory (map id->flight)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained flight set covering the clean path plus the
  governor's own HARD checks, so the actor + tests run offline."
  []
  {:flights
   {"flight-1" {:id "flight-1" :flight-number "JL101" :aircraft-registration "JA101A"
                :origin "HND" :destination "ITM" :carrier "Local Regional Air"
                :certification-verified? true
                :safety-concern-raised? false :safety-concern-resolved? false
                :jurisdiction "JPN" :status :scheduled}
    "flight-2" {:id "flight-2" :flight-number "AT202" :aircraft-registration "AT202X"
                :origin "Atlantis Intl" :destination "Atlantis City" :carrier "Atlantis Air"
                :certification-verified? true
                :safety-concern-raised? false :safety-concern-resolved? false
                :jurisdiction "ATL" :status :scheduled}
    "flight-3" {:id "flight-3" :flight-number "JL303" :aircraft-registration "JA303A"
                :origin "HND" :destination "CTS" :carrier "Local Regional Air"
                :certification-verified? false
                :safety-concern-raised? false :safety-concern-resolved? false
                :jurisdiction "JPN" :status :scheduled}
    "flight-4" {:id "flight-4" :flight-number "JL404" :aircraft-registration "JA404A"
                :origin "HND" :destination "FUK" :carrier "Local Regional Air"
                :certification-verified? true
                :safety-concern-raised? true :safety-concern-resolved? false
                :jurisdiction "JPN" :status :scheduled}
    "flight-5" {:id "flight-5" :flight-number "US505" :aircraft-registration "N505AA"
                :origin "SFO" :destination "LAX" :carrier "US Regional Air"
                :certification-verified? true
                :safety-concern-raised? false :safety-concern-resolved? false
                :jurisdiction "USA" :status :scheduled}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- draft-coordination-record!
  "Backend-agnostic coordination-record draft -- looks up the flight
  via the protocol and drafts the record for `op`, returns
  {:result .. :flight-patch ..} for the caller to persist.
  `:flight-patch` is ALWAYS empty except for `:flag-flight-safety-
  concern`, which sets `:safety-concern-raised? true` -- flagging is
  the only one of the four ops that changes flight-safety-relevant
  ground truth, and even then it never sets `:safety-concern-
  resolved?` (resolving a concern is outside this actor's remit)."
  [s op flight-id]
  (let [fl (flight s flight-id)
        seq-n (next-sequence s (:jurisdiction fl) op)
        result (registry/register-coordination-record op flight-id (:jurisdiction fl) seq-n)]
    {:result result
     :flight-patch (if (= op :flag-flight-safety-concern)
                     {:safety-concern-raised? true}
                     {})}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (flight [_ id] (get-in @a [:flights id]))
  (all-flights [_] (sort-by :id (vals (:flights @a))))
  (ledger [_] (:ledger @a))
  (coordination-history [_] (:coordination-history @a))
  (next-sequence [_ jurisdiction op] (get-in @a [:sequences [jurisdiction op]] 0))
  (commit-record! [s {:keys [effect op path]}]
    (when (= :propose effect)
      (let [flight-id (first path)
            {:keys [result flight-patch]} (draft-coordination-record! s op flight-id)
            jurisdiction (:jurisdiction (flight s flight-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences [jurisdiction op]] (fnil inc 0))
                       (cond-> (seq flight-patch) (update-in [:flights flight-id] merge flight-patch))
                       (update :coordination-history registry/append result))))
        result))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-flights [s flights] (when (seq flights) (swap! a assoc :flights flights)) s))

(defn seed-db
  "A MemStore seeded with the demo flight set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sequences {} :coordination-history []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (ledger facts, coordination records) are stored
  as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses."
  {:flight/id                {:db/unique :db.unique/identity}
   :ledger/seq               {:db/unique :db.unique/identity}
   :coordination/seq         {:db/unique :db.unique/identity}
   :sequence/key             {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- flight->tx [{:keys [id flight-number aircraft-registration origin destination carrier
                          certification-verified? safety-concern-raised? safety-concern-resolved?
                          jurisdiction status]}]
  (cond-> {:flight/id id}
    flight-number                                          (assoc :flight/flight-number flight-number)
    aircraft-registration                                    (assoc :flight/aircraft-registration aircraft-registration)
    origin                                                      (assoc :flight/origin origin)
    destination                                                    (assoc :flight/destination destination)
    carrier                                                          (assoc :flight/carrier carrier)
    (some? certification-verified?)                                    (assoc :flight/certification-verified? certification-verified?)
    (some? safety-concern-raised?)                                       (assoc :flight/safety-concern-raised? safety-concern-raised?)
    (some? safety-concern-resolved?)                                       (assoc :flight/safety-concern-resolved? safety-concern-resolved?)
    jurisdiction                                                              (assoc :flight/jurisdiction jurisdiction)
    status                                                                      (assoc :flight/status status)))

(def ^:private flight-pull
  [:flight/id :flight/flight-number :flight/aircraft-registration :flight/origin :flight/destination
   :flight/carrier :flight/certification-verified? :flight/safety-concern-raised?
   :flight/safety-concern-resolved? :flight/jurisdiction :flight/status])

(defn- pull->flight [m]
  (when (:flight/id m)
    {:id (:flight/id m) :flight-number (:flight/flight-number m)
     :aircraft-registration (:flight/aircraft-registration m)
     :origin (:flight/origin m) :destination (:flight/destination m) :carrier (:flight/carrier m)
     :certification-verified? (boolean (:flight/certification-verified? m))
     :safety-concern-raised? (boolean (:flight/safety-concern-raised? m))
     :safety-concern-resolved? (boolean (:flight/safety-concern-resolved? m))
     :jurisdiction (:flight/jurisdiction m) :status (:flight/status m)}))

(defrecord DatomicStore [conn]
  Store
  (flight [_ id]
    (pull->flight (d/pull (d/db conn) flight-pull [:flight/id id])))
  (all-flights [_]
    (->> (d/q '[:find [?id ...] :where [?e :flight/id ?id]] (d/db conn))
         (map #(pull->flight (d/pull (d/db conn) flight-pull [:flight/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (coordination-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :coordination/seq ?s] [?e :coordination/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction op]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (pr-str [jurisdiction op]))
        0))
  (commit-record! [s {:keys [effect op path]}]
    (when (= :propose effect)
      (let [flight-id (first path)
            {:keys [result flight-patch]} (draft-coordination-record! s op flight-id)
            jurisdiction (:jurisdiction (flight s flight-id))
            key-str (pr-str [jurisdiction op])
            next-n (inc (next-sequence s jurisdiction op))]
        (d/transact! conn
                     (cond-> [{:sequence/key key-str :sequence/next next-n}
                              {:coordination/seq (count (coordination-history s)) :coordination/record (enc (get result "record"))}]
                       (seq flight-patch) (conj (flight->tx (assoc flight-patch :id flight-id)))))
        result))
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-flights [s flights]
    (when (seq flights) (d/transact! conn (mapv flight->tx (vals flights)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:flights ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [flights]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-flights s flights))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo flight set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
