(ns muguet.core
  ;; todo write a meaningful documentation of this namespace
  (:require [clojure.tools.logging :as log]
            [muguet.internals.commands :as mug-cmd]
            [muguet.internals.db :as db]
            [muguet.internals.meta-schemas :as meta]
            [xtdb.api :as xt]))

(defn start!
  [system]
  (when-let [node @db/node] (.close node))
  (reset! db/node (xt/start-node {}))
  ;; start logging
  (xt/listen @db/node {::xt/event-type ::xt/indexed-tx :with-tx-ops? true} #(log/debug "Tx commited:" (prn-str %)))
  (let [{:keys [schema] :as system} system]
    (if (meta/validate schema)
      (-> (mug-cmd/assoc-event-builders system)
          (mug-cmd/register-aggregations!)
          (mug-cmd/register-event-handlers!)
          (mug-cmd/register-commands!))
      (throw (ex-info "invalid aggregate schema" (or (meta/explain schema) {}))))))

(defn build-event
  [type event-body-fn]
  {:name "build-event"
   :enter (fn ^{:doc "interceptor that build and add an event to the context"}
            [{:keys [aggregate-system aggregate-id] :as context}]
            (let [event-builder (-> aggregate-system :events type :builder)
                  event (event-builder aggregate-id (event-body-fn context))]
              (assoc context :event event)))})