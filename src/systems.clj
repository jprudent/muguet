(ns systems
  (:require [integrant.core :as ig]
            [unilog.config :as log]
            [clojure.java.io :as io]
            [xtdb.api :as xt]))


(def config
  {:muguet/logger {:impl (ig/ref :muguet.logger.impl/unilog)}
   :muguet/repository {:impl (ig/ref :muguet.repository.impl/xtdb)}

   :muguet.logger.impl/unilog {:console true
                               :level :info}

   :muguet.repository.impl/xtdb {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                          :db-dir "data/dev/tx-log"
                                                          :sync true}}
                                 :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                                  :db-dir "data/dev/store"
                                                                  :sync true}}
                                 :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                                               :db-dir "data/dev/index-store"
                                                               :sync true}}}})

(defmethod ig/init-key :muguet/logger [_ conf] conf)
(defmethod ig/resolve-key :muguet/logger [_ conf] (:impl conf))
(defmethod ig/init-key :muguet/repository [_ conf] conf)
(defmethod ig/resolve-key :muguet/repository [_ conf] (:impl conf))

(defmethod ig/init-key :muguet.repository.impl/xtdb [_ conf] (xt/start-node conf))
(defmethod ig/halt-key! :muguet.repository.impl/xtdb [_ xtdb-node] (.close xtdb-node))
(defmethod ig/init-key :muguet.logger.impl/unilog [_ conf] (log/start-logging! conf))

(defonce current-system (atom nil))
(defn start! [] (reset! current-system (ig/init config)))
(defn halt! [] (when @current-system (ig/halt! @current-system)))
(defn restart! [] (do (halt!) (start!)))
