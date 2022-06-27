(ns muguet.db
  (:require [xtdb.api :as xt])
  (:import (java.time Duration)))

(defonce node (atom (xt/start-node {})))

(def event-ctx
  [:map
   [:event {:doc "the event to apply"} :map]
   [:on-aggregate {:doc "the aggregate the event is applied upon"} :map]
   [:aggregate {:doc "the resulting aggregate after applying the event"} :map]])

(defn register-event-handler
  [event-type f]
  ;; todo check f is a LIST (source code fn) with proper arguments
  ;;      see how we can get the source code if it's a fn ?
  ;;      maybe we can just wrap f in a list because it feels weird they have to do some xtdb shit in upper layer...
  ;; example f:
  ;'(fn [ctx eid]
  ;  (let [db (xtdb.api/db ctx)
  ;        entity (xtdb.api/entity db eid)]
  ;    [[::xt/put (update entity :age inc)]]))
  (xt/await-tx @node (xt/submit-tx @node [[::xt/put {:xt/id event-type
                                                     :xt/fn f}]])
               (Duration/ofSeconds 1)))

;; todo make an async version
(defn insert!
  [event-ctx]
  (xt/await-tx
    @node
    (xt/submit-tx @node [[::xt/fn (-> event-ctx :event :type) event-ctx]]))

  #_(let [value (assoc document :xt/id (:id document))]
      ;; the insertion is done in 2 phases
      ;; phase 1: check the aggregate exists at the given version (on-aggregate)
      ;;          and call transaction function :store-event-fn
      ;; phase 2: :store-event-fn put:
      ;; - the aggregate
      ;; - the last event
      ;; - update the event history
      ;; Rationale behind those 2 phases is that I don't want to pollute the
      ;; document log with document that are not meant to be indexed (if match
      ;; fails, the document will appear in the document log)
      (assoc value :version (xt/submit-tx @node [[::xt/match] [::xt/put value]]))))

(defn id->xt-aggregate-id [id] (str id "_aggregate"))
(defn id->xt-last-event-id [id] (str id "_last_event"))

(defn fetch-aggregate
  ; The DB must be provided because it's the context it provides a context
  ; If it was not required, it would have been fetch from the node without any
  ; kind of context
  "Utility function that fetch an aggregate by id"
  [db id]
  (dissoc (xt/entity db (id->xt-aggregate-id id)) :xt/id))

(defn fetch-event-history
  "Retrieve the all the events that have been applied on the aggregate"
  [db id]
  (map #_identity (fn [{:keys [:xtdb.api/doc]}] (dissoc doc :xt/id))
                  (xt/entity-history db (id->xt-last-event-id id) :asc {:with-docs? true})))