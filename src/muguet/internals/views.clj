(ns muguet.internals.views
  (:require [muguet.internals.db :as db]))


;; there are 3 kinds of views
;; - `poly-type-aggregate-view` are db wide views that spans over multiple aggregates (eg: a consolidated dashboard)
;; - `mono-type-aggregate-view` are views about a single type of aggregate, and concerns several aggregates (eg: a list of products)
;; - `one-aggregate-view` is a view about a single specific aggregate (eg: a product detail)
;; A view can be generated with
;; - a query that works on aggregate and event documents
;;   - the result is computed on the fly
;; - a reduction algorithm that applies successive events
;;   - the result is computed once, cached, and updated when an event arrives

;; todo pagination and sorting
(defn all-aggregations
  "a query based mono-type-aggregate-view that returns all aggregates of given type"
  [aggregate-system aggregation-name]
  (db/all-aggregations (:aggregate-name aggregate-system) aggregation-name))

