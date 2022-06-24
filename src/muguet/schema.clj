(ns muguet.schema
  "About validating aggregate against schema"
  (:require [malli.core :as m]
            [muguet.meta-schemas :as meta]
            [malli.util :as mu]))

(defn validate
  [schema aggregate]
  {:malli/schema [:=> [meta/meta-coll-schema map?] :boolean]}
  (m/validate schema aggregate))

(defn optional
  "Make all the map attributes optional"
  [schema]
  (m/walk schema (fn [schema _path _walked-children _opts]
                   (mu/update-properties schema assoc :optional true))))