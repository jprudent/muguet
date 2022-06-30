(ns muguet.meta-schemas
  "meta-schemas for schemas (the one found under content-types directory)
  they are used to check the basic form of schemas"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]))

(def text [:string {:min 1}])
; TODO should it be limited to those characters ? what about non-latin ?
(def kebab [:re #"[a-z0-9-]+"])
(def uid [:re #"^[A-Za-z0-9-_.~]*$"])
(def icon [:re #"[a-z0-9-]+"])

(def attributes-registry
  {:uid (m/-simple-schema {:type :uid, :pred string?})
   :relation '???})

;; TODO consider renaming collection aggregate-root borrowed from DDD
(def collection-meta
  [:and
   {:error/message "Collection metadata must be provided"}
   [:map
    {:error/message "The collection metadata is invalid"}
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
    '(fn [{:keys [:collection/plural :collection/singular]}] (or (nil? plural) (not= plural singular)))]])

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
;; It feels really weird to introduce that because Malli has the notion of repository that
;; enables schema reuse in different contexts.

(def component-metadata
  [:map
   [:component/display-name {:doc "Whenever this component name must be displayed."} text text]
   [:component/doc {:optional true :doc "Explains what the collection really is."} text]
   [:component/icon {:optional true :doc "FontAwesome icon name"} icon]])

(def mime-type [:and
                [:string {:example "type/subtype;parameter=value"}]
                [:re #"\s+/\s+;\s+=.+"]])

(def media-type-metadata
  [:map
   [:media/allowed-types {:doc "Kind of pre-built media types"
                          :optional true}
    [:set [:enum :image :video :archive :document]]]
   [:media/allowed-mime-types {:doc "List of mime-types"
                               :example #{"application/json"}
                               :optional true}
    [:set mime-type]]])

(def attribute-options [:map])

(def uid-type-metadata
  [:map
   ;; todo verify that target field exists
   [:uid/target-field keyword?]])


(def malli-built-in-types
  ;; some of the predicate are non serializable functions
  (filter (some-fn keyword? symbol?)
          (mapcat keys [(malli.core/predicate-schemas)
                        (malli.core/type-schemas)
                        (malli.core/sequence-schemas)
                        (malli.core/base-schemas)])))

(def muguet-types #{:relation :component :media :uid})

(def all-types (into malli-built-in-types muguet-types))

(defn- attr-type-error-fn [{:keys [value]} _]
  ;; todo going further on error message : "did you mean blah blah ?"
  (str "unknown type: `" value "`. Must be one of " (str/join ", " all-types)))

(comment
  (m/explain [:multi {:dispatch (fn [x] (if (seq? x) (first x) ::error))}
              [:a [:sequential keyword?]]
              [::error [:sequential any?]]
              [::m/default any?]]
             1))

;; TODO get inspired with https://kwrooijen.github.io/gungnir/model.html
;; the description of schema as data

;; TODO add a ":muguet/unique?"

;; TODO check there is a primary key. Unlike Strapi that adds the id itself
;; Muguet let the user choose the internal id. Not sure it's a good idea though.
;; Every aggregate-root must have some kind of natural id, or an artificial one
;; that is unique and managed intentionally (aka "external id"). I think
;; we can delegate identity management to Muguet, but let the developer choose
;; other alternative id forms.
;; Thinking more about that: Muguet must have its own internal and technical id
;; that is optimized for persistance engine. This id must never be used.
;; So users must provide a :muguet/primarey-key? that is used in APIs and can
;; be of any form.
;; This muguet/primary-key can be hold directly on the collection metadata.
;; Any attribute which is unique can be used as primary-key (used in API)


;; Introduce the concept of sequences to autogenerate values
;; Once we got that, we can have a :muguet/default metadata that tells Muguet to
;; generate value based on a sequence or a value generator

;; TODO introduce "virtual" or "transient" attributes ? (it may be an applicative hack)
(def meta-coll-schema
  [:schema
   {:registry {::coll-schema [:cat
                              {:error/message "The collection schema must start with :map, then a map that describes the collection, then a list of attributes."}
                              [:= :map]
                              collection-meta
                              [:* ::attribute-schema]]
               ::attribute-schema [:or
                                   ;; todo we should enforce namespaced keywords for attribute.
                                   [:tuple keyword? [:ref ::attribute-type]]
                                   [:tuple keyword? attribute-options [:ref ::attribute-type]]]
               ::attr-type (into [:enum {:error/fn attr-type-error-fn}] all-types)
               ::attribute-type [:orn
                                 [:simple-type ::attr-type]
                                 [:complex-type
                                  [:multi {:dispatch (fn [x] (if (sequential? x) (first x) ::type-error))}
                                   [:relation [:tuple {:example [:relation {:relation/target :api.product/product}]}
                                               [:= :relation] relation-type-meta]]

                                   [:component [:tuple {:example [:component {:component/target :component.custom-fields/custom-fields}]}
                                                [:= :component] component-type-metadata]]

                                   [:media [:tuple {:example [:media {:media/allowed-types #{"images"}}]}
                                            [:= :media] media-type-metadata]]

                                   [:uid [:tuple {:example [:uid {:uid/target-field :title}]}
                                          [:= :uid] uid-type-metadata]]

                                   [:map [:cat {:example [:map [:x :int] [:y :int]]}
                                          [:= :map] [:? map?] [:* ::attribute-schema]]]

                                   [:sequential [:orn
                                                 ;; todo can't use :ref in a sexp, so I have to rely on :orn
                                                 [:no-meta [:tuple {:example [:sequential :int]}
                                                            [:= :sequential] [:ref ::attribute-type]]]
                                                 [:with-meta [:tuple {:example [:sequential :int]}
                                                              [:= :sequential] map? [:ref ::attribute-type]]]]]

                                   [::type-error [:sequential {:error/message "type can have simple form (eg: `:int`) or complex form (eg: `[:int {:min 2}]`)"} any?]]

                                   [::m/default [:cat
                                                 ::attr-type
                                                 [:? {:example {:min 1}} map?]
                                                 ;; todo I could go further, but that would be validating a malli schema. where to stop ?
                                                 [:* {:example [:enum :red :blue]} any?]]]]]]}}
   [:ref ::coll-schema]])


;; todo vizualization https://github.com/metosin/malli#visualizing-schemas

;; todo for now, we use malli to validate the user collection schema,
;; but I am not convinced by error messages
(def validate (m/validator meta-coll-schema))

(def explain (comp me/humanize (m/explainer meta-coll-schema)))

(defn errors
  "extract all error messages from explanation."
  ;; todo do something smarter that correlate the error message with the key attribute
  [explanation]
  (when-let [errors (-> (walk/postwalk
                          (fn [x] (when (string? x) (println x)))
                          explanation)
                        (with-out-str)
                        (not-empty))]
    (set (str/split-lines errors))))