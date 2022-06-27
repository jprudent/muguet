(ns muguet.schema-test
  (:require [clojure.test :refer :all]
            [malli.core :as m]
            [malli.util :as mu]
            [muguet.schema :as sut]
            [muguet.usecase :as uc]))

(deftest validate-test
  (is (sut/validate uc/pokemon-card (:example (m/properties uc/pokemon-card)))))

(deftest optional-test
  (testing "first level attributes"
    (is (mu/equals (m/schema [:map [:foo {:optional true} :string]])
                   (sut/optional [:map [:foo :string]]))))
  (testing "already optional"
    (is (mu/equals (m/schema [:map [:foo {:optional true} :string]])
                   (sut/optional [:map [:foo {:optional true} :string]]))))
  (testing "nested map are not set optional"
    (is (mu/equals (m/schema [:map [:foo {:optional true} [:map [:bar :int]]]])
                   (sut/optional [:map [:foo [:map [:bar :int]]]])))))
