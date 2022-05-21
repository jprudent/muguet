(ns muguet.meta-schemas-test
  (:require [clojure.test :refer :all]
            [muguet.meta-schemas :as sut]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.error :as me]))

(def valid-schema
  [:map
   #:collection{:kind :collection,
                :singular "product",
                :plural "products",
                :display-name "Products",
                :doc "A product sold on the website",
                :icon "cubes"}
   [:title [:string {:min 1}]]
   [:description [:string {:min 1}]]
   [:price [:double]]
   [:image [:media #:media{:allowed-types #{:image}}]]
   [:slug [:uid #:uid{:target-field :title}]]
   [:categories [:relations #:relation{:arity :many-to-many, :target :api.category/category}]]
   [:products [:relation #:relation{:arity :many-to-many, :target :api.product/product}]]
   [:custom-fields [:sequential [:component #:component{:target :component.custom-fields/custom-fields}]]]
   [:status {:default :draft} [:enum :draft :published]]])

(deftest meta-schema-test
  (is (= nil (m/explain sut/meta-coll-schema valid-schema))))

(deftest gen-schemas-test
  (testing "schemas can be generated"
    (is (not-empty (mg/generate sut/meta-coll-schema))))
  (testing "generated schemas can be used to generate collection elements"
    (let [coll-schema (mg/generate sut/meta-coll-schema)]
      (is (not-empty (mg/generate coll-schema))))))