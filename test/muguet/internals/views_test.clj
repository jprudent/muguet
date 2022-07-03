(ns muguet.internals.views-test
  (:require [clojure.test :refer :all]
            [muguet.internals.commands :as cmd]
            [muguet.internals.main :as main]
            [muguet.internals.views :as sut]
            [muguet.test-utils :as tu]
            [muguet.usecase :as uc]))

;; todo run those test against different persistence mechanisms
(use-fixtures :each main/start!)

(def some-id "yep")
(def aggregate-system
  {:schema uc/pokemon-card
   :id-provider (constantly some-id)
   :aggregate-name :pokemon-card})

(deftest all-test
  (let [version (cmd/hatch {:number 123} aggregate-system)]
    (tu/blocking-fetch-result version some-id)
    (is (= [{:number 123 :id "yep"}]
           (map #(dissoc % :stream-version) (sut/all version aggregate-system))))))

