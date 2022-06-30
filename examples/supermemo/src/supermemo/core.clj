(ns supermemo.core
  (:require [muguet.meta-schemas :as meta-schema]))

(def flashcard
  [:map
   [:question [:string {:min 1}]]
   [:response [:string {:min 1}]]
   [:lear-map [:map
               [:difficulty [:double {:min 0 :max 1}]]
               [:interval :pos-int]
               [:percent-overdue :double]
               [:updated :inst]
               [:due-date :inst]]]])


(meta-schema/validate flashcard)


