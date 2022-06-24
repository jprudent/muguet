(ns muguet.commands-test
  (:require [clojure.test :refer :all]
            [muguet.commands :as sut]
            [muguet.usecase :as uc]))

(deftest hatch-test
  (testing "creating a pokemon card"
    (is (= {:aggregate {}
            :command {}
            :events []}
           (sut/hatch {:aggregate {}} {:schema uc/pokemon-card})))))
