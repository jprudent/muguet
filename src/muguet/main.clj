(ns muguet.main
  (:require [muguet.commands :as commands]
            [muguet.db :as db]
            [xtdb.api :as xt]))

(defn start!
  [f]
  (with-open [node (xt/start-node {})]
    (reset! db/node node)
    (commands/register-event-handlers)
    (f)))