(ns muguet.schema
  "About validating aggregate against schema"
  (:require [malli.core :as m]
            [muguet.meta-schemas :as meta]))

(defn validate
  [schema aggregate]
  {:malli/schema [:=> [meta/meta-coll-schema map?] :boolean]}
  (m/validate schema aggregate))