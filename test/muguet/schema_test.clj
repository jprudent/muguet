(ns muguet.schema-test
  (:require [clojure.test :refer :all]
            [muguet.schema :as sut]
            [muguet.usecase :as uc]
            [malli.core :as m]
            [malli.util :as mu]))

(deftest validate-test
  (is (sut/validate uc/pokemon-card (:example (m/properties uc/pokemon-card)))))

(deftest optional-test
  (testing "first level attributes"
    (is (mu/equals (m/schema [:map {:optional true} [:foo :string]])
                   (sut/optional [:map [:foo :string]]))))
  (testing "already optional"
    (is (mu/equals (m/schema [:map {:optional true} [:foo :string]])
                   (sut/optional [:map {:optional true} [:foo :string]]))))
  (testing "nested map are not set optional"
    (is (mu/equals (m/schema [:map {:optional true} [:foo [:map [:bar]]]])
                   (sut/optional [:map [:foo [:map [:bar]]]])))))
