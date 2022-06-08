(ns muguet.schema-test
  (:require [clojure.test :refer :all]
            [muguet.schema :as sut]
            [muguet.usecase :as uc]
            [malli.core :as m]))

(deftest validate-test
  (is (sut/validate uc/pokemon-card (:example (m/properties uc/pokemon-card)))))
