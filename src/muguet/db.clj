(ns muguet.db
  (:require [xtdb.api :as xt]))

(defonce node (atom (xt/start-node {})))

(defn insert
  [attributes collection-system]
  (let [value (assoc attributes :xt/id (:id attributes))]
    (assoc value :version (xt/submit-tx @node [[::xt/put value]]))))