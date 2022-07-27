(ns muguet.utils
  "User utilities"
  (:require [clojure.string :as str]
            [malli.generator :as mg]
            [muguet.internals.meta-schemas :as meta]))

(defn check-schema
  "Returns a list of error message if there is some problems with the schema"
  [schema]
  (meta/errors (meta/explain schema)))

(defn ->collection-metadata
  "Generate a basic set of collection metadata ready to use in your schema
  aggregate-name is the stuff you're designing"
  [aggregate-name]
  {:collection/kind :collection
   :collection/singular aggregate-name
   :collection/plural (str aggregate-name "s")
   :collection/display-name (str/capitalize aggregate-name)})

(defn gen-aggregate
  "Generate a random aggregate. Useful for mockups and tests."
  [system & {:as opts}]
  (mg/generate (:schema system) opts))

(defn gen-event
  "Generate a random event. Useful for mockups and tests."
  [system event-name & {:as opts}]
  (mg/generate (-> system :events event-name) opts))