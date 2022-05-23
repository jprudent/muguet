(ns muguet.meta-schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [muguet.usecase :as uc]
            [muguet.meta-schemas :as sut]
            [malli.dev.pretty :as mp]
            [clojure.string :as str]
            [matcher-combinators.test]))

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
   [:categories [:relation #:relation{:arity :many-to-many, :target :api.category/category}]]
   [:products [:relation #:relation{:arity :many-to-many, :target :api.product/product}]]
   [:custom-fields [:sequential [:component #:component{:target :component.custom-fields/custom-fields}]]]
   [:status {:default :draft} [:enum :draft :published]]])

(deftest valid-schema-test
  (is (sut/validate valid-schema))
  (is (= nil (mp/explain sut/meta-coll-schema valid-schema))))

(def empty-schema
  [:map
   #:collection{:kind :collection,
                :singular "product",
                :plural "products",
                :display-name "Products",
                :doc "A product sold on the website",
                :icon "cubes"}])

(deftest meta-schema-test
  (testing "A schema without attributes is a valid schema"
    (is (sut/validate empty-schema))))

(deftest attributes-test
  (testing "the attribute schema can be expressed with a keyword"
    (let [schema (conj empty-schema [:attr :int])]
      (is (sut/validate schema))))
  (testing "the attribute schema can be expressed with a symbol"
    (let [schema (conj empty-schema [:attr 'pos-int?])]
      (is (sut/validate schema))))
  (testing "the attribute schema can be expressed with a keyword inside a vector"
    (let [schema (conj empty-schema [:attr [:int]])]
      (is (sut/validate schema))))
  (testing "the attribute schema can be expressed with a symbol inside a vector"
    (let [schema (conj empty-schema [:attr ['pos-int?]])]
      (is (sut/validate schema))))
  (testing "the attribute can be a nested map, there is no need to have relations"
    (let [schema (conj empty-schema [:attr [:map
                                            {:example {:x 1.2, :y 3, :plan {:dims 2}}}
                                            [:x 'double?]
                                            [:y 'double?]
                                            [:plan [:map [:dims [:int {:min 1}]]]]]])]
      (is (sut/validate schema))))
  (testing "the attribute can be a sequence of maps"
    (let [schema (conj empty-schema [:attr [:sequential [:map
                                                         [:x 'double?]
                                                         [:y 'double?]]]])]
      (is (sut/validate schema)))))

(deftest attributes-error-test
  ; The idea is to check that we have a comprehensible error message in those cases
  (testing "unknown simple type"
    (let [schema (conj empty-schema [:attr :int2])]
      (is (match? #{#"unknown type: `:int2`" #"type can have simple form"}
                  (sut/errors (sut/explain schema))))))
  (testing "unknown simple type in submap"
    ;; int2 is not a valid type (must be a valid symbol or keyword)
    (let [schema (conj empty-schema [:attr [:map [:foo :int2]]])]
      (is (match? #{#"unknown type: `:int2`" #"type can have simple form"}
                  (sut/errors (sut/explain schema))))))
  (testing "malformed submap"
    ;; `[:map :int]` is non sense
    (let [schema (conj empty-schema [:attr [:map :int]])]
      (is (match? #{#"unknown type: `:int2`" #"type can have simple form"}
                  (sut/errors (sut/explain schema)))))))

(deftest pokemon-card-schema-test
  (is (sut/validate uc/pokemon-card)))