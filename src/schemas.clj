(ns schemas
  (:require [malli.core :as m]))

(def text [:string {:min 1}])
; TODO should it be limited to those characters ? what about non-latin ?
(def kebab [:re #"[a-z0-9-]+"])
(def uid [:re #"^[A-Za-z0-9-_.~]*$"])

(def attributes-registry
  {:uid (m/-simple-schema {:type :uid, :pred string?})
   :relation '???})

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
         [:collection/singular {:doc "Used to generate the API routes"} kebab]
         [:collection/plural {:doc "Used to generate the API routes"} kebab]
         [:collection/display-name {:doc "Whenever this collection name must be displayed. Eg: Swagger"} text]
         [:collection/doc {:optional true :doc "Explains what the collection really is."}]
         [:collection/icon {:optional true :doc "FontAwesome icon name"} [:re #"[a-z0-9-]+"]]]
   ; TODO plural must really be different from singular ?
   [:fn {:error/message "The singular must be different than plural"}
    '(fn [{:keys [:collection/plural :collection/singular]}] (not= plural singular))]])

(def relation-meta
  [:map
   :relation/arity [{:doc "
## `many-to-many`
`many-to-many` relationships are useful when:
- an entry from content-type A is linked to many entries of content-type B,
- and an entry from content-type B is also linked to many entries from content-type A.
`many-to-many` relationships can be unidirectional or bidirectional. In unidirectional relationships, only one of the models can be queried with its linked item.
## `one-to-many`
`one-to-many` relationships are useful when:
- an entry from a content-type A is linked to many entries of another content-type B
- while an entry from content-type B is linked to only one entry of content-type A.
`one-to-many` relationships are always bidirectional, and are usually defined with the corresponding `many-to-one` relationship.
## `many-to-one`
`many-to-one` relationships are useful to link many entries to one entry.
They can be unidirectional or bidirectional. In unidirectional relationships, only one of the models can be queried with its linked item.
## `one-to-one`
`one-to-one` relationships are useful when one entry can be linked to only one other entry.
They can be unidirectional or bidirectional. In unidirectional relationships, only one of the models can be queried with its linked item."}]
   [:enum [:one-to-one
           :one-to-many
           :many-to-one
           :many-to-many]]
   [:relation/target {:doc "
   Schema the target content-type.
   For instance, if you defined a api/product/content-types/product, that would be `:api.product/product`"}
    ; TODO check that the target exists
    [:keyword]]
   ; TODO Strapi define mappedBy and inversedBy, but I think a flag to describe relation ownership is more appropriate
   ; TODO check that both ends are not owner
   [:relation/owner? {:optional true} [:boolean]]])
