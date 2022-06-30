(ns muguet.utils
  "User utilities"
  (:require [clojure.string :as str]
            [malli.generator :as mg]
            [muguet.meta-schemas :as meta]))

(defn check-schema
  "Returns a list of error message if there is some problems with the schema"
  [schema]
  (meta/errors (meta/explain schema)))

(defn gen-collection-metadata
  "Generate a basic set of collection metadata ready to use in your schema
  aggregate-name is the stuff you're designing"
  [aggregate-name]
  {:collection/kind :collection
   :collection/singular aggregate-name
   :collection/plural (str aggregate-name "s")
   :collection/display-name (str/capitalize aggregate-name)})

(defn gen-aggregate
  "Generate an aggregate based on the schema. Useful for mockups and tests."
  [schema & {:as opts}]
  (mg/generate schema opts))

