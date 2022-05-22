(ns muguet.core
  (:require [malli.util :as mu]
            [malli.core :as m]))

(def meta-schema [:meta {:optional true} map?])

(def object-schema
  [:map
   ;; the id is not mixed with attributes to enforce the segregation between identity and value
   [:id pos-int?]
   [:attributes map?]
   meta-schema])

;; inspired by https://docs.strapi.io/developer-docs/latest/developer-resources/database-apis-reference/rest-api.html#unified-response-format
(def response-schema
  [:map
   [:data
    [:orn
     [:single object-schema]
     [:list object-schema]]]
   meta-schema])

(def error-schema
  [:map
   ;; inspired by https://docs.strapi.io/developer-docs/latest/developer-resources/error-handling.html#rest-errors
   [:error [:map
            [:status [:enum [:bad-request :conflict :not-exist :exception]]
             :message [:string {:min 1}]
             :details map?]]]
   meta-schema])

(def api-return-schema
  [:orn
   [:ko error-schema]
   [:ok response-schema]])

(defn hatch
  "Creation is always a weird time. Think how a baby was born. Does he have
  all the attribute that would qualify him as a complete human ? No, still
  someone named him. He gets identity, he exists.
  When creating an aggregate-root for your domain, it may be incomplete. Some
  attribute values may be missing. But that's ok, it's tolerated. At that point
  of the CRUD lifecycle every attribute are optional.
  If some attributes are provided, they are checked against their respective schema."
  ;; TODO implement "required" attributes for birth time
  ;;      it's different from required/optional attribute schema
  ;;      may be name it "mandatory" or "enforced"
  [parameters {:keys [schema] :as collection-system}]
  {:post [(m/validate api-return-schema %)]}
  (mu/keys)
  )

;; For those unconvinced by the metaphor
(def create hatch)
(defn find-one [id collection-system])
(defn delete [id collection-system])
