(ns ecommerce.entity-api-test
  (:require [clojure.test :refer :all]
            [muguet.systems]
            [muguet.core :as muguet]))

;; The entity API provides CRUD operations over the defined schemas

(deftest create-category-test
  ;; Starting the system returns systems
  ;; One of the system is the categories system as defined in the src/api/category directory
  (let [{:keys [:muguet.api/categories]} (muguet.systems/start!)
        {:keys [:id :title] :as board-games} (muguet/create {:name "board games"} categories)]
    (is (= "board games" title))
    (is (= board-games (muguet/find-one id categories)))
    (muguet/delete id categories)
    (is (nil? (muguet/find-one id categories)))))


(deftest create-category-missing-attributes-test
  ;; Starting the system returns systems
  ;; One of the system is the categories system as defined in the src/api/category directory
  (let [{:keys [:muguet.api/categories]} (muguet.systems/start!)
        empty {}
        error (muguet/create empty categories)]
    (is (= "todo" error))))