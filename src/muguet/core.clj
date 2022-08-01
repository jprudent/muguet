(ns muguet.core
  ;; todo write a meaningful documentation of this namespace
  (:require [muguet.api :as muga]
            [muguet.internals.commands :as mug-cmd]
            [muguet.internals.meta-schemas :as meta]
            [xtdb.api :as xt]))

(defn log-tx!
  [system]
  (xt/listen (:node system) {::xt/event-type ::xt/indexed-tx :with-tx-ops? true}
             (fn [xt-event] (prn "====Tx commited:" xt-event)))
  system)
(defn start!
  [system]
  ;; start logging
  (let [node (xt/start-node {})]
    (try
      (let [{:keys [schema] :as system} system]
        (if (meta/validate schema)
          (-> system
              (assoc :node (xt/start-node {}))
              (mug-cmd/assoc-event-builders)
              (mug-cmd/register-aggregations!)
              (mug-cmd/register-commands!)
              (log-tx!))
          (throw (ex-info "invalid aggregate schema" (or (meta/explain schema) {})))))
      (catch Exception e (.close node) (throw e)))))

(defn build-event
  [type event-body-fn]
  {:name "build-event"
   :enter (fn ^{:doc "interceptor that build and add an event to the context"}
            [{:keys [aggregate-system aggregate-id] :as context}]
            (let [event-builder (-> aggregate-system :events type :builder)
                  event (event-builder aggregate-id (event-body-fn context))]
              (assoc context :event event)))})

(defn command
  [system command-name aggregate-id version command-arg]
  (if-let [command (mug-cmd/get-command system command-name)]
    (command aggregate-id version command-arg)
    (throw (ex-info (str command-name " doesn't exist.") {:command-name command-name
                                                          :system system}))))
(defn recompute-aggregation
  "One would need to recompute an aggregation to fix a bug, or simply to change
  the schema. This will evolve all aggregates with all events from the dawn of time.
  WARNING: This operation needs to be performed when the system is read only because
  it would introduce incoherence otherwise. Read only means do not perform commands."
  ;; fixme because I know that users will fuck up the "do this offline", we need
  ;;   an application flag to put it read only. OR we need a smart CAS
  [system aggregation-name]
  (let [db (xt/db (:node system))
        aggregate-name (:aggregate-name system)
        evolve (find-var (get-in system [:aggregations aggregation-name :evolve]))]
    (with-open [query (xt/open-q db
                                 '{:find [id]
                                   :in [aggregate-name]
                                   :where [[last-event :xt/id id]
                                           [last-event ::muga/aggregate-name aggregate-name]
                                           [last-event ::muga/document-type ::muga/event]]}
                                 aggregate-name)]
      (doseq [[last-event-id] (iterator-seq query)
              :let [events (xt/entity-history db last-event-id :asc {:with-docs? true})
                    ;; commit all versions of the aggregation in a single transaction
                    ;; so une the eager function `entity-history`
                    ;; in case this is revealed impractical, we could use `open-entity-history`
                    ;; to split the job in several transactions.
                    {:keys [operations]} (reduce (fn [acc {:keys [::xt/doc ::xt/valid-time] :as _event}]
                                                   (let [new-aggregation (evolve (:aggregation acc) doc)
                                                         operation [::xt/put (mug-cmd/make-aggregation-document
                                                                               new-aggregation
                                                                               (:aggregate-id doc)
                                                                               aggregation-name
                                                                               aggregate-name
                                                                               doc)
                                                                    valid-time]]
                                                     (assoc
                                                       (update acc :operations conj operation)
                                                       :aggregation new-aggregation)))
                                                 {:aggregation nil :operations []}
                                                 events)]]
        (xt/await-tx (:node system) (xt/submit-tx (:node system) operations))))))
