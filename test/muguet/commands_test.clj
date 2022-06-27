(ns muguet.commands-test
  "Those tests are using the real implementation of database through an in-memory instance of XTDB"
  (:require [clojure.test :refer :all]
            [muguet.commands :as sut]
            [muguet.db :as db]
            [muguet.usecase :as uc]
            [xtdb.api :as xt]))

(def some-id "some fake id")
(def id-provider (constantly some-id))

(def pokemon-system {:schema uc/pokemon-card
                     :aggregate-name :pokemon-card
                     :id-provider id-provider})

(deftest hatch-test
  (testing "hatching an empty pokemon card"
    (let [results (sut/hatch nil pokemon-system)
          [{:keys [event on-aggregate aggregate]} & others] results
          expected-aggregate {:id some-id}
          expected-event {:type :pokemon-card/hatched
                          :version 1
                          :aggregate-id some-id
                          ;; in that case the body of the event is the aggregate,
                          ;; but in other circumstances it should be something different
                          :body expected-aggregate}]
      (is (= nil others) "hatch results in a single event")
      (is (= on-aggregate nil) "the aggregate is applied upon a nil (non existant value)")
      (is (= event expected-event))
      (is (= aggregate expected-aggregate))
      (is (= aggregate (db/fetch-aggregate (xt/db @db/node) some-id)) "The aggregate is persisted")
      (is (= [expected-event] (db/fetch-event-history (xt/db @db/node) some-id)))))

  (testing "hatching a pokemon card with initial values"
    (let [initial-value {:number 121}
          events (sut/hatch initial-value pokemon-system)
          [{:keys [event on-aggregate aggregate event-history]} & others] events
          expected-aggregate (assoc initial-value :id some-id)]
      (is (= nil others) "hatch results in a single event")
      (is (= on-aggregate nil))
      (is (= event {:type :pokemon-card/hatched
                    :version 1
                    :aggregate-id some-id
                    :body expected-aggregate}))
      (is (= aggregate expected-aggregate))
      (is (empty? event-history))))

  (testing "hatching when id already exists"
    #_(let [first-result (sut/hatch nil pokemon-system)
            second-result (sut/hatch {:number 12} pokemon-system)]
        (is (= 'todo second-result))))
  (testing "hathching when initial values doesn't match schema"))

;; todo test async
;; todo activate instrumentation of schemas
