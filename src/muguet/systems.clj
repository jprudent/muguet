(ns muguet.systems
  (:require [integrant.core :as ig]
            [unilog.config :as log]
            [babashka.fs :as fs]
            [xtdb.api :as xt]
            [clojure.edn :as edn]))

(def config
  {:muguet/logger {:console true
                   :level :info}
   :muguet/repository {}
   :muguet/app {:dir "."
                :logger (ig/ref :muguet/logger)
                :repository (ig/ref :muguet/repository)}})

(defmethod ig/init-key :muguet/logger [_ conf] (log/start-logging! conf))
(defmethod ig/init-key :muguet/repository [_ conf] (xt/start-node conf))
(defmethod ig/halt-key! :muguet/repository [_ xtdb-node] (.close xtdb-node))
(defmethod ig/init-key :muguet/app [conf]
  )

(defonce current-system (atom nil))

(defn load-schemas [project-dir]
  (reduce
    (fn [schemas schema-path]
      (let [[_ coll-namespace coll-name] (re-matches #".*/api/(.+)/content-types/(.+)/schema.edn" (str schema-path))
            schema-id (keyword (str "api." coll-namespace) coll-name)
            schema (-> schema-path fs/file slurp edn/read-string)]
        (assoc schemas schema-id schema)))
    {} (fs/glob project-dir "api/**/schema.edn")))

(defn validate-schemas)
(defn start!
  "Start the systems. It will use an in memory persistence backend by default."
  [dir]
  {:pre [(fs/directory? dir)]}
  (reset! current-system (ig/init config))
  (load-schemas dir))

(defn halt! [] (when @current-system (ig/halt! @current-system)))

(defn restart! [dir] (do (halt!) (start! dir)))
