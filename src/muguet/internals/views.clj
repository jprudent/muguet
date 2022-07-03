(ns muguet.internals.views
  (:require [muguet.internals.db :as db]))

;; todo pagination and sorting
(defn all
  "returns all aggregates"
  [version aggregate-system]
  (db/all version (:aggregate-name aggregate-system)))
