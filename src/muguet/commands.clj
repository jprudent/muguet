(ns muguet.commands
  "those are out of the box commands provided by muguet"
  (:require [malli.error :as me]
            [muguet.api :as mug]
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
(defn register-event-handlers
  []
  (db/register-event-handler
    (keyword (name :pokemon-card) "hatched")
    ;; for now this is generic providing we added the id in the command,
    ;; which seems reasonable
    ;; todo provide a mechanism to let user define its own transactions
    '(fn [db-ctx event-ctx]
       (let [db (xtdb.api/db db-ctx)
             id (-> event-ctx :aggregate :id)
             existing-aggregate (muguet.db/fetch-aggregate db id)]
         (if (= existing-aggregate (:on-aggregate event-ctx))
           [[::xt/put (assoc (:aggregate event-ctx)
                        :xt/id (muguet.db/id->xt-aggregate-id id)
                        ;; todo rename stream-version ::mug/stream-version
                        :stream-version (:indexing-tx db-ctx))]
            ;; event history can be retrieved from the history of this document
            [::xt/put (assoc (:event event-ctx)
                        :xt/id (muguet.db/id->xt-last-event-id id)
                        :stream-version (:indexing-tx db-ctx))]]
           ;; put an error document so error can be retrieved from command
           ;; this could also be implemented with a "registy" of promises but
           ;; that's a state to maintain
           (do (prn "!!!!!!" [[::xt/put {:xt/id (muguet.db/id->xt-error-id id)
                                         :stream-version (:indexing-tx db-ctx)
                                         :status ::mug/not-found
                                         :message "the specified aggregate version couldn't be find"
                                         :details {:actual existing-aggregate
                                                   :expected (:on-aggregate event-ctx)}}]])
               [[::xt/put {:xt/id (muguet.db/id->xt-error-id id)
                           :stream-version (:indexing-tx db-ctx)
                           :status ::mug/not-found
                           :message "the specified aggregate version couldn't be find"
                           :details {:actual existing-aggregate
                                     :expected (:on-aggregate event-ctx)}}]]))))))

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
                                            ;; todo let's remove the id-provider, the client must provide id
                                            :id-provider {:doc "injection of any strategy for id generation"} fn?]]]
                  core/api-return-schema]}
  (let [optional-schema (schema/optional schema)
        id (id-provider attributes)
        aggregate (assoc attributes :id id)]
    (if (schema/validate optional-schema aggregate)
      ;; fixme there is serious flaw here where the events are not inserted atomically
      ;;       solution is to insert the whole vector in the same transaction
      ;;       but we got same version for 2 different aggregate/last-event hummmmmm
      {:version (db/insert-async {:on-aggregate nil
                                  :event (->event (keyword (name aggregate-name) "hatched") aggregate)
                                  :aggregate aggregate})
       ::mug/command-status ::mug/pending}
      {:error {:status ::mug/invalid
               ;; TODO the error message must be more precise, explaining
               ;;      which attributes, and why
               :message "Invalid attributes"
               ;; TODO give complete coordinate of the error
               :details (me/humanize (schema/explain schema attributes))}
       ::mug/command-status ::mug/complete})))

(defn fetch-command-result
  [version id]
  (let [;; fixme there will be a bug if some other command at version+1 happened (won't find the element i think)
        event (db/fetch-last-event-version version id)
        aggregate (db/fetch-aggregate-version version id)]
    (if (and event aggregate)
      {:event event
       :aggregate aggregate
       ::mug/command-status ::mug/complete}
      (if-let [error (db/fetch-error-version version id)]
        {:error error
         ::mug/command-status ::mug/complete}
        {::mug/command-status ::mug/pending}))))

;; For those unconvinced by the metaphor
(def create hatch)
(def init hatch)
