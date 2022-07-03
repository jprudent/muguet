(ns muguet.internals.main
  (:require [muguet.internals.commands :as commands]
            [muguet.internals.db :as db]
            [xtdb.api :as xt]))

(defn start!
  [f]
  (with-open [node (xt/start-node {})]
    (reset! db/node node)
    (commands/register-event-handlers)
    (f)))