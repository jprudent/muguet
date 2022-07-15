(ns muguet.core
  ;; todo write a meaningful documentation of this namespace
  (:require [muguet.internals.commands :as mug-cmd]
            [muguet.internals.db :as db]
            [muguet.internals.meta-schemas :as meta]
            [xtdb.api :as xt])
  (:import (java.time Duration)))

(defn start!
  [system]
  (when-let [node @db/node] (.close node))
  (reset! db/node (xt/start-node {}))
  (let [{:keys [schema] :as system} system]
    (if (meta/validate schema)
      (-> (mug-cmd/assoc-event-builders system)
          (mug-cmd/register-events!)
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

(defn find-one
  "Return a future of the given query"
  [query stream-version]
  (future (xt/await-tx @db/node stream-version (Duration/ofMinutes 1))
          (ffirst (xt/q (xt/db @db/node) query))))

(defn destroy
  ";; There is no \"delete\" but a \"destroy\" to enforce the destructive outcome
  this operation. User can implement a non-destructive method of his own."
  [id collection-system])