(ns muguet.meta-schemas-test
  (:require [clojure.test :refer :all]
            [muguet.meta-schemas :as sut]
            [malli.core :as m]))

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
   [:price [:float]]
   [:image [:media #:media{:allowed-types #{"images"}}]]
   [:slug [:uid #:uid{:target-field :title}]]
   [:categories [:relations #:relation{:arity :many-to-many, :target :api.category/category}]]
   [:products [:relation #:relation{:arity :many-to-many, :target :api.product/product}]]
   [:custom-fields [:sequential [:component #:component{:target :component.custom-fields/custom-fields}]]]
   [:status {:default :draft} [:enum :draft :published]]])

(deftest meta-schema-test
  (is (= nil (m/explain sut/meta-schema valid-schema))))