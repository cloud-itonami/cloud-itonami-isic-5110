(ns airlineops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [airlineops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/flight s "flight-1"))))
      (is (true? (:certification-verified? (store/flight s "flight-1"))))
      (is (false? (:safety-concern-raised? (store/flight s "flight-1"))))
      (is (false? (:certification-verified? (store/flight s "flight-3"))))
      (is (true? (:safety-concern-raised? (store/flight s "flight-4"))))
      (is (false? (:safety-concern-resolved? (store/flight s "flight-4"))))
      (is (= ["flight-1" "flight-2" "flight-3" "flight-4" "flight-5"]
             (mapv :id (store/all-flights s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/coordination-history s)))
      (is (zero? (store/next-sequence s "JPN" :log-flight-record))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "a :propose commit drafts a coordination record and advances the per-jurisdiction+op sequence"
        (store/commit-record! s {:effect :propose :op :log-flight-record :path ["flight-1"]})
        (is (= "JPN-LOG-000000" (get (first (store/coordination-history s)) "record_id")))
        (is (= "flight-record-log-draft" (get (first (store/coordination-history s)) "kind")))
        (is (= 1 (count (store/coordination-history s))))
        (is (= 1 (store/next-sequence s "JPN" :log-flight-record)))
        (is (false? (:safety-concern-raised? (store/flight s "flight-1")))
            "log-flight-record never touches safety-concern ground truth"))
      (testing "flag-flight-safety-concern commit sets safety-concern-raised? true but never resolved?"
        (store/commit-record! s {:effect :propose :op :flag-flight-safety-concern :path ["flight-1"]})
        (is (= "JPN-SAF-000000" (get (first (filter #(= "flight-safety-concern-flag-draft" (get % "kind")) (store/coordination-history s))) "record_id")))
        (is (true? (:safety-concern-raised? (store/flight s "flight-1"))))
        (is (false? (:safety-concern-resolved? (store/flight s "flight-1")))))
      (testing "sequences are independent per jurisdiction+op pair"
        (store/commit-record! s {:effect :propose :op :log-flight-record :path ["flight-1"]})
        (is (= 2 (store/next-sequence s "JPN" :log-flight-record)))
        (is (= 1 (store/next-sequence s "JPN" :flag-flight-safety-concern))))
      (testing "a non-:propose effect is never committed"
        (let [before (count (store/coordination-history s))]
          (store/commit-record! s {:effect :flight/clear-for-departure :op :log-flight-record :path ["flight-1"]})
          (is (= before (count (store/coordination-history s))) "governor should never let this reach the store, but the store itself is also defensive")))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/flight s "nope")))
    (is (= [] (store/all-flights s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/coordination-history s)))
    (is (zero? (store/next-sequence s "JPN" :log-flight-record)))
    (store/with-flights s {"x" {:id "x" :flight-number "ZZ1" :aircraft-registration "N1XX"
                                :origin "o" :destination "d" :carrier "c"
                                :certification-verified? true
                                :safety-concern-raised? false :safety-concern-resolved? false
                                :jurisdiction "JPN" :status :scheduled}})
    (is (= "c" (:carrier (store/flight s "x"))))))
