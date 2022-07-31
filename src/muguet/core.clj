(ns muguet.core
  ;; todo write a meaningful documentation of this namespace
  (:require [clojure.tools.logging :as log]
            [muguet.internals.commands :as mug-cmd]
            [muguet.internals.meta-schemas :as meta]
            [xtdb.api :as xt]))

(defn start!
  [system]
  ;; start logging
  (let [node (xt/start-node {})]
    (try
      (xt/listen node {::xt/event-type ::xt/indexed-tx :with-tx-ops? true}
                 #(log/info "====Tx commited:" (prn-str %)))
      (let [{:keys [schema] :as system} system]
        (if (meta/validate schema)
          (-> system
              (assoc :node (xt/start-node {}))
              (mug-cmd/assoc-event-builders)
              (mug-cmd/register-aggregations!)
              (mug-cmd/register-commands!))
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
