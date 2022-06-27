(ns muguet.commands
  "those are out of the box commands provided by muguet"
  (:require [malli.core :as m]
            [malli.error :as me]
            [muguet.core :as core]
            [muguet.meta-schemas :as meta]
            [muguet.schema :as schema]))

(defn ->event
  [event-type {:keys [id] :as aggregate}]
  ;; todo specify type of id
  {:malli/schema [:=> [:qualified-keyword [:map [:id any?]]]]}
  {:header {:event-type event-type
            :event-version 1
            :aggregate-id id}
   :body aggregate})

;; TODO rename initialize ?
(defn hatch
  "Creation is always a weird time. Think how a baby was born. Does he have
  all the attribute that would qualify him as a complete human ? No, still
  someone named him. He gets identity, he exists.
  When creating an aggregate-root for your domain, it may be incomplete. Some
  attribute values may be missing. But that's ok, it's tolerated. At that point
  of the CRUD lifecycle every attribute are optional.
  If some attributes are provided, they are checked against their respective schema."

  ;; TODO implement "required" attributes for hatch time
  ;;      it's different from required/optional attribute schema
  ;;      may be name it "mandatory" or "enforced"

  [attributes {:keys [schema id-provider aggregate-name] :as collection-system}]
  #_{:post [(m/validate core/api-return-schema %)]}
  {:malli/schema [:=> [[:maybe map?] [:map [:schema meta/meta-coll-schema
                                            :id-provider {:doc "injection of any strategy for id generation"} fn?]]]
                  core/api-return-schema]}
  (let [optional-schema (schema/optional schema)
        id (id-provider attributes)
        aggregate (assoc attributes :id id)]
    (if (schema/validate optional-schema aggregate)
      [{:event (->event (keyword (name aggregate-name) "hatched") aggregate)
        :on-aggregate nil
        :aggregate aggregate
        :event-history nil}]
      {:error {:status :invalid
               ;; TODO the error message must be more precise, explaining
               ;;      which attributes, and why
               :message "Invalid attributes"
               ;; TODO give complete coordinate of the error
               :details (me/humanize (schema/explain schema attributes))}})))

;; For those unconvinced by the metaphor
(def create hatch)
(def init hatch)
