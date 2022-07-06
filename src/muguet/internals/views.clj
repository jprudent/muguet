(ns muguet.internals.views
  (:require [muguet.internals.db :as db]
            [xtdb.api :as xt]))


;; there are 3 kinds of views
;; - `poly-type-aggregate-view` are db wide views that spans over multiple aggregates
;; - `mono-type-aggregate-view` are views about a single type of aggregate, and concerns several aggregates
;; - `one-aggregate-view` is a view about a single specific aggregate
;; A view can be generated with
;; - a query that works on aggregate and event documents
;;   - the result is computed on the fly
;; - a reduction algorithm that applies successive events
;;   - the result is computed once, cached, and updated when an event arrives

;; todo pagination and sorting
(defn all
  "a query based mono-type-aggregate-view that returns all aggregates of given type"
  [version aggregate-system]
  (db/all version (:aggregate-name aggregate-system)))

;; todo should provide a stream-version so init-value could be a snapshot, without need to process all events
(defn register-event-based-mono-type-aggregate-view
  [view-name aggregate-system reduction-fn init-value]
  (let [view (reduce reduction-fn init-value (db/fetch-events (:aggregate-name aggregate-system)))]
    (db/save-view view-name view)
    ;; todo shouldn't have a ref of node here
    ;; todo should close the
    (xt/listen @db/node {::xt/event-type ::xt/indexed-tx
                         :with-tx-ops? true}
               prn)))

