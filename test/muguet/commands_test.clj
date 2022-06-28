(ns muguet.commands-test
  "Those tests are using the real implementation of database through an in-memory instance of XTDB"
  (:require [clojure.test :refer :all]
            [muguet.commands :as sut]
            [muguet.db :as db]
            [muguet.main :as main]
            [muguet.usecase :as uc]
            [xtdb.api :as xt]))

(def some-id "some fake id")
(def id-provider (constantly some-id))

(def pokemon-system {:schema uc/pokemon-card
                     :aggregate-name :pokemon-card
                     :id-provider id-provider})

(use-fixtures :each main/start!)

(deftest hatch-test
  (testing "hatching an empty pokemon card"
    (let [results (sut/hatch nil pokemon-system)
          [{:keys [event on-aggregate aggregate]} & others] results

          aggregate-in-db (db/fetch-aggregate (xt/db @db/node) some-id)
          aggregate-stream-version (:stream-version aggregate)
          expected-aggregate {:id some-id :stream-version aggregate-stream-version}
          aggregate-at-version (db/fetch-aggregate-version aggregate-stream-version some-id)

          event-history-in-db (db/fetch-event-history (xt/db @db/node) some-id)
          event-stream-version (:stream-version event)
          expected-event {:type :pokemon-card/hatched
                          :version 1
                          :aggregate-id some-id
                          ;; in that case the body of the event is the aggregate,
                          ;; but in other circumstances it should be something different
                          :body (dissoc expected-aggregate :stream-version)
                          :stream-version event-stream-version}
          event-at-version (db/fetch-last-event-version event-stream-version some-id)]
      (is (= nil others) "hatch results in a single event")

      (is (= on-aggregate nil) "the aggregate is applied upon a nil (non existant value)")

      (is (= expected-event event event-at-version (first event-history-in-db))
          "Event is returned and durably persisted")
      (is (= 1 (count event-history-in-db)))

      (is (= expected-aggregate aggregate aggregate-in-db aggregate-at-version)
          "Aggregate is returned and durably persisted")

      (is (some? event-stream-version))
      (is (= event-stream-version aggregate-stream-version)
          "Aggregate and event has same stream version")))

  (testing "hatching when initial values doesn't match schema")

  (testing "concurrent hatching"))

(deftest hatch-init-vals-test
  (testing "hatching a pokemon card with initial values"
    (let [initial-value {:number 121}
          events (sut/hatch initial-value pokemon-system)
          [{:keys [event on-aggregate aggregate]} & others] events
          expected-aggregate (assoc initial-value :id some-id
                                                  :stream-version (:stream-version aggregate))]
      (is (= nil others) "hatch results in a single event")
      (is (= on-aggregate nil))
      (is (= event {:type :pokemon-card/hatched
                    :version 1
                    :aggregate-id some-id
                    :stream-version (:stream-version event)
                    :body (dissoc expected-aggregate :stream-version)}))
      (is (= aggregate expected-aggregate)))))

#_(deftest hatch-id-exists-test
    (testing "hatching when id already exists"
      (let [first-result (sut/hatch nil pokemon-system)
            second-result (sut/hatch {:number 12} pokemon-system)]
        (is (= 'todo second-result)))))

;; todo atomicity of several events by a single command
;; todo test async
;; todo activate instrumentation of schemas
