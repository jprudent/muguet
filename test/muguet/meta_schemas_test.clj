(ns muguet.meta-schemas-test
  (:require [clojure.test :refer [deftest is]]
            [muguet.meta-schemas :as sut]
            [malli.dev.pretty :as mp]))

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

(deftest meta-schema-test
  (is (sut/validate valid-schema))
  (is (= nil (mp/explain sut/meta-coll-schema valid-schema))))