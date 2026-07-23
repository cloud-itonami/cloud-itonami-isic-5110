(ns airlineops.facts
  "Per-jurisdiction civil-aviation-authority spec-basis catalog -- the
  G2-style table the Aviation Safety Governor checks every proposal
  against ('did the advisor cite an OFFICIAL public source for this
  flight's operating jurisdiction, or did it invent one?').

  This is NOT a certification authority and does NOT itself verify an
  Air Operator Certificate (AOC) or an aircraft's airworthiness
  certificate -- see `airlineops.store`'s `:certification-verified?`
  ground-truth fact, which this actor treats as independently
  registered elsewhere (a real civil-aviation-authority record this
  actor consumes, never mints). This catalog only answers 'is there an
  official regulatory basis for coordinating operations in this
  jurisdiction at all' -- the honest, non-fabricating discipline every
  sibling actor's `facts` namespace uses.

  Coverage is reported HONESTLY (see `coverage`): a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries."
  (:require [clojure.string :as str]))

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  airworthiness-certificate / air-operator-certificate / crew-licensing
  / safety-management-system evidence set a real civil aviation
  authority requires before a carrier's operations can be coordinated
  in that jurisdiction. `:legal-basis` / `:owner-authority` /
  `:provenance` are the G2 citation the governor requires before any
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "国土交通省航空局 (Japan Civil Aviation Bureau, JCAB)"
          :legal-basis "航空法 (Civil Aeronautics Act)"
          :national-spec "航空運送事業の安全管理に関する省令基準"
          :provenance "https://www.mlit.go.jp/koku/koku_fr10_000001.html"
          :required-evidence ["耐空証明記録 (airworthiness-certificate record)"
                              "航空運送事業許可記録 (air-operator-certificate record)"
                              "乗員資格記録 (crew-licensing record)"
                              "安全管理システム記録 (safety-management-system record)"]}
   "USA" {:name "United States"
          :owner-authority "Federal Aviation Administration (FAA)"
          :legal-basis "14 C.F.R. Part 121 (Operating Requirements: Domestic, Flag, and Supplemental Operations)"
          :national-spec "FAA Part 5 Safety Management Systems + Part 121 operating-certificate standards"
          :provenance "https://www.faa.gov/regulations_policies/faa_regulations"
          :required-evidence ["Airworthiness-certificate record"
                              "Air-operator-certificate (Part 121) record"
                              "Crew-licensing record"
                              "Safety-management-system record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "UK Civil Aviation Authority (CAA)"
          :legal-basis "Air Navigation Order 2016 (SI 2016/765)"
          :national-spec "UK CAA operating-certificate/airworthiness enforcement standards"
          :provenance "https://www.caa.co.uk/our-work/publications/documents/"
          :required-evidence ["Airworthiness-certificate record"
                              "Air-operator-certificate record"
                              "Crew-licensing record"
                              "Safety-management-system record"]}
   "DEU" {:name "Germany"
          :owner-authority "Luftfahrt-Bundesamt (LBA)"
          :legal-basis "Luftverkehrsgesetz (LuftVG) / EU Regulation 965/2012 (Air OPS)"
          :national-spec "LBA Betriebsgenehmigung- und Lufttüchtigkeitsanforderungen"
          :provenance "https://www.lba.de/DE/Betrieb/Luftverkehrsgesellschaften/Luftverkehrsgesellschaften_node.html"
          :required-evidence ["Lufttüchtigkeitszeugnisnachweis (airworthiness-certificate record)"
                              "Luftverkehrsbetreibergenehmigungsnachweis (air-operator-certificate record)"
                              "Besatzungslizenznachweis (crew-licensing record)"
                              "Sicherheitsmanagementsystemnachweis (safety-management-system record)"]}
   "ARE" {:name "United Arab Emirates"
          :owner-authority "General Civil Aviation Authority of the U.A.E. (GCAA)"
          :legal-basis "Federal Act No. (20) of 1991 (Civil Aviation Law), Art. 20 (rules of the air / overflight & route designation) + Federal Law No. (4) of 1996 (General Civil Aviation Authority Law), Art. 7(9) (Air Traffic Control operations in the State)"
          :national-spec "GCAA Civil Aviation Regulations (CARs) -- UAE Air Operator / Air Navigation Service Provider operating-certificate and safety-oversight standards"
          :provenance "https://www.gcaa.gov.ae/en/about-gcaa/aviation-laws-uae"
          :required-evidence ["Airworthiness-certificate record"
                              "Air-operator-certificate record"
                              "Crew-licensing record"
                              "Safety-management-system record"]}
   ;; CAN citations independently fetched+read directly this session
   ;; (2026-07-23) from laws-lois.justice.gc.ca's own FullText.html for
   ;; both the parent Act and its operating regulations (both HTTP
   ;; 200, no bot-detection challenge). Confirmed verbatim on the Act's
   ;; own s.1 (Short Title): "This Act may be cited as the Aeronautics
   ;; Act." and its own Interpretation clause: "Minister means (a)
   ;; ... the Minister of Transport ...". Confirmed verbatim on the
   ;; Canadian Aviation Regulations' (SOR/96-433) own Interpretation
   ;; section: "air operator certificate means a certificate issued
   ;; under Part VII that authorizes the holder of the certificate to
   ;; operate a commercial air service"; "flight authority means a
   ;; certificate of airworthiness, special certificate of
   ;; airworthiness, flight permit or validation of a foreign document
   ;; attesting to an aircraft's fitness for flight, issued under
   ;; Subpart 7 of Part V"; "safety management system means a
   ;; documented process for managing risks that integrates operations
   ;; and technical systems with the management of financial and human
   ;; resources to ensure aviation safety or the safety of the public";
   ;; and Part IV/VI/VII exam-fee schedule references to "a flight
   ;; crew licence" and "a flight crew permit" confirming the
   ;; crew-licensing regime. HONEST GAP: this iteration did not
   ;; independently confirm Transport Canada's own internal branding
   ;; for its civil-aviation directorate (its own tc.canada.ca aviation
   ;; page rendered only a small nav-shell, not substantive text) --
   ;; this entry cites "Transport Canada" (the department, matching the
   ;; Act's own "Minister of Transport" definition) rather than a more
   ;; specific sub-branch name that was not independently verified.
   "CAN" {:name "Canada"
          :owner-authority "Transport Canada (Minister of Transport, per the Aeronautics Act's own definition)"
          :legal-basis "Aeronautics Act (R.S.C., 1985, c. A-2); Canadian Aviation Regulations (SOR/96-433)"
          :national-spec "Canadian Aviation Regulations' own definitions: air operator certificate (issued under Part VII, authorizing commercial air service); flight authority (certificate of airworthiness / special certificate of airworthiness / flight permit, issued under Subpart 7 of Part V); safety management system (documented risk-management process integrating operations/technical/financial/human-resource management for aviation safety); flight crew licence/permit examination and issuance fee schedule (Parts IV/VI/VII)"
          :provenance "https://laws-lois.justice.gc.ca/eng/acts/a-2/ ; https://laws-lois.justice.gc.ca/eng/regulations/SOR-96-433/"
          :required-evidence ["Airworthiness-certificate record"
                              "Air-operator-certificate record"
                              "Crew-licensing record"
                              "Safety-management-system record"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to coordinate
  operations on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-5110 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `airlineops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn citation
  "The [legal-basis provenance] citation pair for `iso3`, or nil when
  there is no spec-basis -- the advisor uses this directly as its own
  `:cites`, it never invents a citation."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    [(:legal-basis sb) (:provenance sb)]))

(defn owner-authority [iso3]
  (:owner-authority (spec-basis iso3)))

(defn known-jurisdiction? [iso3]
  (boolean (and iso3 (not (str/blank? iso3)) (spec-basis iso3))))
