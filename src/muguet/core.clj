(ns muguet.core
  (:require [muguet.internals.commands :as mug-cmd]
            [muguet.internals.db :as db]
            [xtdb.api :as xt]))

(defn start!
  []
  (when-let [node @db/node] (.close node))
  (reset! db/node (xt/start-node {}))
  (mug-cmd/register-event-handlers))

(defn find-one [id collection-system])

(defn destroy
  ";; There is no \"delete\" but a \"destroy\" to enforce the destructive outcome
  this operation. User can implement a non-destructive method of his own."
  [id collection-system])

(defn hatch
  [attributes aggregate-system]
  (mug-cmd/hatch attributes aggregate-system))
