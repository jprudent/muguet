(ns core
  (:require            [malli.util :as mu]))

(def meta-schema [:meta map?])

(def object-schema
  [:map
   [:id pos-int?]
   [:attributes map?]
   meta-schema])

(def response-schema
  [:map
   [:data
    [:orn
     [:single object-schema]
     [:list object-schema]
     [:custom any?]]]
   meta-schema])

(def error-schema
  (mu/merge (mu/update-properties response-schema assoc :optional true)
            [:map
             [:error map?]]))