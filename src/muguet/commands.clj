(ns muguet.commands
  "those are out of the box commands provided by muguet"
  (:require [malli.error :as me]
            [muguet.core :as core]
            [muguet.db :as db]
            [muguet.meta-schemas :as meta]
            [muguet.schema :as schema]
            [xtdb.api :as xt]))

(defn ->event
  [event-type {:keys [id] :as aggregate}]
  ;; todo specify type of id
  {:malli/schema [:=> [:qualified-keyword [:map [:id any?]]]]}
  {:type event-type
   :version 1
   :aggregate-id id
   :body aggregate})

;; a command can result in multiple events.
;; For instance is there is a command to delete all pokemon cards of the
;; fire family, such command will emit as many events as there is fire
;; pokemon cards.


;; todo do that in an initialization code
;; todo hard coded kw
(defonce _ (db/register-event-handler (keyword (name :pokemon-card) "hatched")
                                      ;; for now this is generic providing we added the id in the command,
                                      ;; which seems reasonable
                                      ;; todo provide a mechanism to let user define its own transactions
                                      '(fn [db-ctx event-ctx]
                                         (let [db (xtdb.api/db db-ctx)
                                               id (-> event-ctx :aggregate :id)
                                               existing-aggregate (muguet.db/fetch-aggregate db id)]
                                           (if (= existing-aggregate (:on-aggregate event-ctx))
                                             [[::xt/put (assoc (:aggregate event-ctx) :xt/id (muguet.db/id->xt-aggregate-id id))]
                                              ;; event history can be retrieved from the history of this document
                                              [::xt/put (assoc (:event event-ctx) :xt/id (muguet.db/id->xt-last-event-id id))]])))))

;; TODO rename initialize ? or identify
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
  {:malli/schema [:=> [[:maybe map?] [:map [:schema meta/meta-coll-schema
                                            :id-provider {:doc "injection of any strategy for id generation"} fn?]]]
                  core/api-return-schema]}
  (let [optional-schema (schema/optional schema)
        id (id-provider attributes)
        aggregate (assoc attributes :id id)
        event-ctx [{:on-aggregate nil
                    :event (->event (keyword (name aggregate-name) "hatched") aggregate)
                    :aggregate aggregate}]]
    (if (schema/validate optional-schema aggregate)
      (do (mapv db/insert! event-ctx)
          event-ctx)
      {:error {:status :invalid
               ;; TODO the error message must be more precise, explaining
               ;;      which attributes, and why
               :message "Invalid attributes"
               ;; TODO give complete coordinate of the error
               :details (me/humanize (schema/explain schema attributes))}})))

;; For those unconvinced by the metaphor
(def create hatch)
(def init hatch)
