(ns muguet.internals.db
  (:require [muguet.api :as muga]
            [xtdb.api :as xt])
  (:import (java.time Duration)))

;; todo set in system, not in a global var
(defonce node (atom nil))


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



(defn listen-events [on-event]
  (xt/listen @node {::xt/event-type ::xt/indexed-tx :with-tx-ops? true}
             (fn
               ;; xt-event is not a muguet event, it's an event inside xtdb
               [xt-event]
               (when (:committed? xt-event)
                 (doseq [event (extract-events xt-event)]
                   (on-event event))))))

(def event-ctx
  [:map
   [:event {:doc "the event to apply"} :map]
   ;; todo "on-aggregate" is a confusing name that recall some kink of callback
   [:on-aggregate {:doc "The aggregate the event is applied upon.
    This is how Optimistic Concurrency Control is implemented.
    on-aggregate can be provided by the end user if he wants to solve concurrency issues itself.
    on-aggregate can be retrieved automatically and the action could be retried on up to date aggregate"}
    ;; TODO instead of the whole aggregate, we could provide the aggregate version
    :map]
   [:aggregate {:doc "the resulting aggregate after applying the event"}
    [:map
     [:id {:doc "The domain id (not the technical one), must be serializable as string because it is derived to construct the technical id"} any?]
     [:version {:doc "An aggregate has a - string serialized - version that changes each time an event is applied on it. Versions have a relation of order but must be opaque for client. They can be used as HTTP ETag"} :string]]]])

(defn register-tx-fn
  [id f]
  ;; todo check f is a LIST (source code fn) with proper arguments
  ;;      see how we can get the source code if it's a fn ?
  ;;      maybe we can just wrap f in a list because it feels weird they have to do some xtdb shit in upper layer...
  ;; example f:
  ;'(fn [ctx eid]
  ;  (let [db (xtdb.api/db ctx)
  ;        entity (xtdb.api/entity db eid)]
  ;    [[::xt/put (update entity :age inc)]]))
  (xt/await-tx @node (xt/submit-tx @node [[::xt/put {:xt/id id
                                                     :xt/fn f}]])
               (Duration/ofSeconds 1)))

(defn register-event-handler
  [event-type f]
  (register-tx-fn event-type f))

(defn id->xt-aggregate-id [id] (str id "_aggregate"))
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

(defn fetch-aggregate
  ; The DB must be provided because it's the context it provides a context
  ; If it was not required, it would have been fetch from the node without any
  ; kind of context.
  ; todo could also use version as a query context
  "Utility function that fetch an aggregate by id"
  [db id]
  (clean-doc (xt/entity db (id->xt-aggregate-id id))))

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
  [stream-version id]
  (clean-doc (ffirst (xt/q (xt/db @node)
                           '{:find [(pull ?last-event [*])]
                             :in [xt-id version]
                             :where [[?last-event :xt/id xt-id]
                                     [?last-event :stream-version version]]}
                           (id->xt-last-event-id id) stream-version))))

(defn fetch-aggregate-version
  [stream-version id]
  (clean-doc (ffirst (xt/q (xt/db @node)
                           '{:find [(pull ?aggregate [*])]
                             :in [[xt-id version]]
                             :where [[?aggregate :xt/id xt-id]
                                     [?aggregate :stream-version version]]}
                           [(id->xt-aggregate-id id) stream-version]))))

(defn fetch-error-version
  [stream-version id]
  (with-open [cursor (xt/open-entity-history (xt/db @node) (id->xt-error-id id) :asc
                                             {:with-docs? true
                                              :with-corrections? true
                                              :start-tx-id (:tx-id stream-version)})]
    (let [history (iterator-seq (:lazy-seq-iterator cursor))]
      (clean-doc (some (fn [{:keys [:xtdb.api/doc]}]
                         (when (= stream-version (:stream-version doc)) doc))
                       history)))))

(defn all-aggregations
  [aggregate-name aggregation-name]
  (map
    (comp clean-doc first)
    (xt/q (xt/db @node)
          '{:find [(pull aggregate [*])]
            :in [aggregate-name aggregation-name]
            :where [[aggregate ::muga/aggregate-name aggregate-name]
                    [aggregate ::muga/document-type aggregation-name]]}
          aggregate-name aggregation-name)))

;; fixme in multi aggregate system there will be id conflicts
(defn fetch-aggregation-version [aggregation-name id version]
  (clean-doc
    (ffirst
      (xt/q (xt/db @node)
            '{:find [(pull ?aggregation [*])]
              :in [xt-id version]
              :where [[?aggregation :xt/id xt-id]
                      [?aggregation :stream-version version]]}
            (id->xt-aggregation-id aggregation-name id)
            version))))

(defn fetch-last-event [db aggregate-id]
  (clean-doc (ffirst (xt/q (xt/db @node)
                           '{:find [(pull ?last-event [*])]
                             :in [xt-id]
                             :where [[?last-event :xt/id xt-id]]}
                           (id->xt-last-event-id aggregate-id)))))