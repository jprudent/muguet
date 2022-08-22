(ns muguet.core
  ;; todo write a meaningful documentation of this namespace
  (:require [muguet.internals.commands :as mug-cmd]
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

(defn get-command [system command-name]
  (get-in system [:commands command-name]))

(defn command
  [system command-name aggregate-id version command-arg]
  (if-let [command (get-command system command-name)]
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
  (mug-cmd/recompute-aggregation system aggregation-name))
