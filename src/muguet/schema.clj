(ns muguet.schema
  "About validating aggregate against schema"
  (:require [malli.core :as m]
            [malli.util :as mu]
            [muguet.meta-schemas :as meta]))

(defn validate
  [schema aggregate]
  {:malli/schema [:=> [meta/meta-coll-schema map?] :boolean]}
  (m/validate schema aggregate))

(defn explain
  [schema aggregate]
  {:malli/schema [:=> [meta/meta-coll-schema map?] map?]}
  (m/explain schema aggregate))

(defn optional
  "Make all the map attributes optional"
  [schema]
  (mu/optional-keys schema)
  #_(m/walk schema (fn [schema _path _walked-children _opts]
                     (mu/update-properties schema assoc :optional true))))