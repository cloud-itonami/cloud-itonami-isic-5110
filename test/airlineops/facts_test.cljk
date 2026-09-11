(ns airlineops.facts-test
  (:require [clojure.test :refer [deftest is]]
            [airlineops.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest all-six-seeded-jurisdictions-have-a-spec-basis
  (doseq [iso3 ["JPN" "USA" "GBR" "DEU" "ARE" "CAN"]]
    (is (some? (facts/spec-basis iso3)) (str iso3 " spec-basis"))
    (is (string? (:provenance (facts/spec-basis iso3))) (str iso3 " provenance"))
    (is (some? (facts/citation iso3)) (str iso3 " citation"))))

(deftest are-has-a-spec-basis
  (is (some? (facts/spec-basis "ARE")))
  (is (string? (:provenance (facts/spec-basis "ARE")))))

(deftest can-has-a-spec-basis
  (is (some? (facts/spec-basis "CAN")))
  (is (string? (:provenance (facts/spec-basis "CAN"))))
  (is (re-find #"R\.S\.C\., 1985, c\. A-2" (:legal-basis (facts/spec-basis "CAN")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL")))
  (is (nil? (facts/citation "ATL")))
  (is (not (facts/known-jurisdiction? "ATL"))))

(deftest known-jurisdiction-predicate
  (is (facts/known-jurisdiction? "JPN"))
  (is (not (facts/known-jurisdiction? nil)))
  (is (not (facts/known-jurisdiction? ""))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
