(ns airlineops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [airlineops.registry :as r]))

;; ----------------------------- register-coordination-record -----------------------------

(deftest coordination-record-is-a-draft-not-a-real-act
  (let [result (r/register-coordination-record :log-flight-record "flight-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest coordination-record-assigns-op-specific-code
  (is (= "JPN-LOG-000007" (get (r/register-coordination-record :log-flight-record "flight-1" "JPN" 7) "record_id")))
  (is (= "JPN-SCH-000000" (get (r/register-coordination-record :schedule-flight-operation "flight-1" "JPN" 0) "record_id")))
  (is (= "JPN-SAF-000000" (get (r/register-coordination-record :flag-flight-safety-concern "flight-1" "JPN" 0) "record_id")))
  (is (= "JPN-MNT-000000" (get (r/register-coordination-record :coordinate-maintenance "flight-1" "JPN" 0) "record_id"))))

(deftest coordination-record-fields
  (let [result (r/register-coordination-record :log-flight-record "flight-1" "JPN" 0)]
    (is (= (get-in result ["record" "flight_id"]) "flight-1"))
    (is (= (get-in result ["record" "kind"]) "flight-record-log-draft"))
    (is (= (get-in result ["record" "op"]) "log-flight-record"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest coordination-record-op-must-be-in-the-closed-allowlist
  (is (thrown? Exception (r/register-coordination-record :some-other-op "flight-1" "JPN" 0))))

(deftest coordination-record-validation-rules
  (is (thrown? Exception (r/register-coordination-record :log-flight-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-coordination-record :log-flight-record "flight-1" "" 0)))
  (is (thrown? Exception (r/register-coordination-record :log-flight-record "flight-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-coordination-record :log-flight-record "flight-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-coordination-record :log-flight-record "flight-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-LOG-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-LOG-000001" (get-in hist2 [1 "record_id"])))))
