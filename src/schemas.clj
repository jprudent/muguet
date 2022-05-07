(ns schemas
  (:require [malli.core :as m]))

(def text [:string {:min 1}])
; TODO should it be limited to those characters ? what about non-latin ?
(def slug [:re #"[a-z0-9-]+"])
(def attributes-registry
  ; [A-Za-z0-9-_.~]*
  {:uid (m/-simple-schema {:type :uid, :pred string?})})
(def collection-meta
  [:and [:map
         ; there are 2 kinds of collection
         ; - :collection means there can be several instance of this schema
         ; - :single means there can be only one
         ; TODO why? it would be clearer to introduce a :limit attribute that set that limitation
         [:collection/kind
          {:doc "Defines if the content-type is:
           - a collection: there can be several instance of this schema. Eg: products, users
           - a single type: there can be only one instance. Eg: the about section of a blog"}
          [:enum :collection :single]]
         [:collection/singular {:doc "Used to generate the API routes"} slug]
         [:collection/plural {:doc "Used to generate the API routes"} slug]
         [:collection/display-name {:doc "Whenever this collection name must be displayed. Eg: Swagger"} text]
         [:collection/doc {:optional true :doc "Explains what the collection really is."}]
         [:collection/icon {:optional true :doc "FontAwesome icon name"} [:re #"[a-z0-9-]+"]]]
   ; TODO plural must really be different from singular ?
   [:fn {:error/message "The singular must be different than plural"}
    '(fn [{:keys [:collection/plural :collection/singular]}] (not= plural singular))]])
