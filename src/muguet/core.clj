(ns muguet.core
  ;; todo write a meaningful documentation of this namespace
  (:require [muguet.internals.commands :as mug-cmd]
            [muguet.internals.db :as db]
            [muguet.internals.meta-schemas :as meta]
            [xtdb.api :as xt])
  (:import (java.time Duration)))

(defn start!
  [systems]
  (when-let [node @db/node] (.close node))
  (reset! db/node (xt/start-node {}))
  (doseq [{:keys [schema] :as system} systems]
    (if (meta/validate schema)
      (mug-cmd/register-events! system)
      (throw (ex-info "invalid aggregate schema" (or (meta/explain schema) {}))))))

(defn find-one
  "Return a future of the given query"
  [query stream-version]
  (future (xt/await-tx @db/node stream-version (Duration/ofMinutes 1))
          (ffirst (xt/q (xt/db @db/node) query))))

(defn destroy
  ";; There is no \"delete\" but a \"destroy\" to enforce the destructive outcome
  this operation. User can implement a non-destructive method of his own."
  [id collection-system])