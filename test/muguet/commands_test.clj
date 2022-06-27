(ns muguet.commands-test
  (:require [clojure.test :refer :all]
            [muguet.commands :as sut]
            [muguet.usecase :as uc]))

(def some-id "some fake id")
(def id-provider (constantly some-id))

(deftest hatch-test
  (testing "hatching an empty pokemon card"
    (let [events (sut/hatch nil {:schema uc/pokemon-card
                                 :aggregate-name :pokemon-card
                                 :id-provider id-provider})
          [{:keys [event on-aggregate aggregate event-history]} & others] events]
      (is (= nil others) "hatch results in a single event")
      (is (= on-aggregate nil))
      (is (= event {:header {:event-type :pokemon-card/hatched
                             :event-version 1
                             :aggregate-id some-id}
                    :body {:id some-id}}))
      (is (= aggregate {:id some-id}))
      (is (empty? event-history))))

  (testing "hatching a pokemon card with initial values")
  (testing "hatching when id already exists")
  (testing "hathching when initial values doesn't match schema"))

;; todo test async
;; todo activate instrumentation of schemas
