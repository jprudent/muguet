(ns muguet.meta-schemas
  "meta-schemas for schemas (the one found under content-types directory)
  they are used to check the basic form of schemas"
  (:require [malli.core :as m]))

(def text [:string {:min 1}])
; TODO should it be limited to those characters ? what about non-latin ?
(def kebab [:re #"[a-z0-9-]+"])
(def uid [:re #"^[A-Za-z0-9-_.~]*$"])
(def icon [:re #"[a-z0-9-]+"])

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
         [:collection/doc {:optional true :doc "Explains what the collection really is."} text]
         [:collection/icon {:optional true :doc "FontAwesome icon name"} icon]]
   ; TODO plural must really be different from singular ?
   [:fn {:error/message "The singular must be different than plural"}
    '(fn [{:keys [:collection/plural :collection/singular]}] (not= plural singular))]])

(def schema-name [:keyword])

;; TODO probably need an attribute-metadata schema for expressing constraints not built-in malli like uniqueness

(def relation-type-meta
  [:map
   [:relation/arity
    {:doc "
## `many-to-many`
A relation links 2 kinds of collection. Each element in a collection is an identity associated to a given value at a given time.
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
They can be unidirectional or bidirectional. In unidirectional relationships, only one of the models can be queried with its linked item."}
    [:enum :one-to-one :one-to-many :many-to-one :many-to-many]]
   [:relation/target {:doc "The target content-type schema's. For instance, if you defined a api/product/content-types/product, that would be `:api.product/product`"}
    ; TODO check that the target exists
    schema-name]
   ; TODO Strapi define mappedBy and inversedBy, but I think a flag to describe relation ownership is more appropriate
   ; TODO Do I really need this information ?
   ; TODO check that both ends are not owner
   #_[:relation/owner? {:optional true} [:boolean]]])

(def component-type-metadata
  [:map
   [:component/target
    {:doc "The target component schema's. For instance, if you defined a components/custom/custom-fields, that would be `:api.custom/custom-fields"}
    schema-name]])

;; A component is just a reusable schema in different contexts. A component instance is just a value that has no id.

(def component-metadata
  [:map
   [:component/display-name {:doc "Whenever this component name must be displayed."} text text]
   [:component/doc {:optional true :doc "Explains what the collection really is."} text]
   [:componet/icon {:optional true :doc "FontAwesome icon name"} icon]])

(def attribute-options [:map])

(def attribute-type-schema
  ;; TODO missing :or :and and probably other constructs that would be useful
  [:schema {:registry {::attribute-type [:and
                                         [:cat keyword? [:* any?]]
                                         [:multi {:dispatch 'first}
                                            [:relation [:tuple {:example [:relation {:relation/target :api.product/product}]}
                                                        [:enum :relation] relation-type-meta]]
                                            [:component [:tuple {:example [:component {:component/target :component.custom-fields/custom-fields}]}
                                                           [:enum :component] component-type-metadata]]
                                            [:sequential [:tuple [:enum :sequential] [:ref ::attribute-type]]]

                                            [::m/default [:cat
                                                          [:keyword {:example :string}]
                                                          [:? {:example {:min 1}} map?]
                                                          ;; todo I could go further, but that would be validating a malli schema. where to stop ?
                                                          [:* {:example [:enum :red :blue]} any?]]]]]}}
   ::attribute-type])

(def attribute-schema
  ;; TODO I would like to express it with
  ;; [:cat keyword? [:? attribute-options] attribute-type-schema]
  ;; but that's not working
  [:or
   [:tuple keyword? attribute-type-schema]
   [:tuple keyword? attribute-options attribute-type-schema]])

;; the description of schema as data
(def meta-schema
  [:cat
   {:error/message "The collection schema must start with :map, then a map that describes the collection, then a list of attributes."}
   [:enum :map]
   collection-meta
   [:* attribute-schema]])


;; todo vizualization https://github.com/metosin/malli#visualizing-schemas