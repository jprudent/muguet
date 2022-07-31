(ns muguet.internals.db
  (:require [muguet.api :as muga]
            [xtdb.api :as xt])
  (:import (java.time Duration)))

(defn- extract-events [xt-event]
  (loop [acc []
         ops (:xtdb.api/tx-ops xt-event)]
    (if-let [[op-type doc op-param] (first ops)]
      (case op-type
        :xtdb.api/put
        (if (= :muguet.api/event (:muguet.api/document-type doc))
          (recur (conj acc doc) (rest ops))
          (recur acc (rest ops)))
        :xtdb.api/fn
        (recur acc (into (rest ops) (:xtdb.api/tx-ops op-param))))
      acc)))



(defn listen-events [system on-event]
  (xt/listen (:node system) {::xt/event-type ::xt/indexed-tx :with-tx-ops? true}
             (fn
               ;; xt-event is not a muguet event, it's an event inside xtdb
               [xt-event]
               (when (:committed? xt-event)
                 (doseq [event (extract-events xt-event)]
                   (on-event event))))))

(defn register-tx-fn
  [system id f]
  ;; todo check f is a LIST (source code fn) with proper arguments
  ;;      see how we can get the source code if it's a fn ?
  ;;      maybe we can just wrap f in a list because it feels weird they have to do some xtdb shit in upper layer...
  ;; example f:
  ;'(fn [ctx eid]
  ;  (let [db (xtdb.api/db ctx)
  ;        entity (xtdb.api/entity db eid)]
  ;    [[::xt/put (update entity :age inc)]]))
  (xt/await-tx (:node system) (xt/submit-tx (:node system) [[::xt/put {:xt/id id
                                                                       :xt/fn f}]])
               (Duration/ofSeconds 1)))

(defn id->xt-last-event-id [id] (str id "_last_event"))
(defn id->xt-error-id [id] (str id "_error"))
(defn id->xt-aggregation-id [aggregation-name id] (str id "_" (name aggregation-name)))

;; todo all this functions should support pagination and ordering

(defn clean-doc
  "Remove purely technical attribute to have a clean API"
  [doc]
  ;; :stream-version is part of the API
  (when doc
    (dissoc doc :xt/id ::muga/aggregate-name ::muga/document-type)))

(defn fetch-aggregation
  [db aggregation-name id]
  (clean-doc (xt/entity db (id->xt-aggregation-id aggregation-name id))))

(defn fetch-event-history
  "Retrieve the all the events that have been applied on the aggregate"
  ;; todo we probably want to retrieve slices of event history to rebuild
  ;;      from snapshots.
  [db id]
  (map (fn [{:keys [:xtdb.api/doc]}] (clean-doc doc))
       (xt/entity-history db (id->xt-last-event-id id) :asc {:with-docs? true})))

(defn fetch-last-event-version
  "Fetch last event holding the version.
  A document exists in 2 dimensions: its identity `id` and in time with `stream-version`.
  The sole stream-version is not enough because there can be several document inserted with the same stream-version
  (in case of multiple events emitted in a command)"
  ;; todo from comment above, maybe instroduce a document "coordinates" that contains version and id ?
  [system version id]
  (clean-doc (ffirst (xt/q (xt/db (:node system))
                           '{:find [(pull ?last-event [*])]
                             :in [xt-id version]
                             :where [[?last-event :xt/id xt-id]
                                     [?last-event :stream-version version]]}
                           (id->xt-last-event-id id) version))))

(defn fetch-error-version
  [system stream-version id]
  (with-open [cursor (xt/open-entity-history (xt/db (:node system)) (id->xt-error-id id) :asc
                                             {:with-docs? true
                                              :with-corrections? true
                                              :start-tx-id (:tx-id stream-version)})]
    (let [history (iterator-seq (:lazy-seq-iterator cursor))]
      (clean-doc (some (fn [{:keys [:xtdb.api/doc]}]
                         (when (= stream-version (:stream-version doc)) doc))
                       history)))))

(defn all-aggregations
  [system aggregate-name aggregation-name]
  (map
    (comp clean-doc first)
    (xt/q (xt/db (:node system))
          '{:find [(pull aggregate [*])]
            :in [aggregate-name aggregation-name]
            :where [[aggregate ::muga/aggregate-name aggregate-name]
                    [aggregate ::muga/document-type aggregation-name]]}
          aggregate-name aggregation-name)))

;; fixme in multi aggregate system there will be id conflicts
(defn fetch-aggregation-version [system aggregation-name id version]
  (clean-doc
    (ffirst
      (xt/q (xt/db (:node system))
            '{:find [(pull ?aggregation [*])]
              :in [xt-id version]
              :where [[?aggregation :xt/id xt-id]
                      [?aggregation :stream-version version]]}
            (id->xt-aggregation-id aggregation-name id)
            version))))

(defn fetch-last-event [db aggregate-id]
  ;; todo can use xt/pull directly
  (clean-doc (ffirst (xt/q db '{:find [(pull ?last-event [*])]
                                :in [xt-id]
                                :where [[?last-event :xt/id xt-id]]}
                           (id->xt-last-event-id aggregate-id)))))