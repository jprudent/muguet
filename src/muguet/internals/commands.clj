(ns muguet.internals.commands
  "those are out of the box commands provided by muguet"
  (:require [malli.error :as me]
            [muguet.api :as muga]
            [muguet.internals.db :as db]
            [muguet.internals.meta-schemas :as meta]
            [muguet.internals.schema :as schema]
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
             aggregate-name (:aggregate-name event-ctx)
             existing-aggregate (muguet.internals.db/fetch-aggregate db id)]
         (if (= existing-aggregate (:on-aggregate event-ctx))
           [[::xt/put (assoc (:aggregate event-ctx)
                        :xt/id (muguet.internals.db/id->xt-aggregate-id id)
                        ;; todo shouldn't be muga bc it's not part of public api
                        ::muga/aggregate-name aggregate-name
                        ;; todo shouldn't be muga bc it's not part of public api
                        ::muga/document-type ::muga/aggregate
                        ;; todo rename stream-version ::muga/stream-version
                        :stream-version (:indexing-tx db-ctx))]
            ;; event history can be retrieved from the history of this document
            [::xt/put (assoc (:event event-ctx)
                        :xt/id (muguet.internals.db/id->xt-last-event-id id)
                        ::muga/aggregate-name aggregate-name
                        ::muga/document-type ::muga/event
                        :stream-version (:indexing-tx db-ctx))]]
           ;; put an error document so error can be retrieved from command
           ;; this could also be implemented with a "registy" of promises but
           ;; that's a state to maintain
           (let [error-doc {:xt/id (muguet.internals.db/id->xt-error-id id)
                            :stream-version (:indexing-tx db-ctx)
                            ;; todo change error message: this is a conflict
                            :status ::muga/not-found
                            ::muga/aggregate-name aggregate-name
                            ::muga/document-type ::muga/error
                            :message "the specified aggregate version couldn't be find"
                            :details {:actual existing-aggregate
                                      :expected (:on-aggregate event-ctx)}}]
             (clojure.tools.logging/error error-doc)
             [[::xt/put error-doc]]))))))

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
                  muga/api-return-schema]}
  (let [optional-schema (schema/optional schema)
        id (id-provider attributes)
        aggregate (assoc attributes :id id)]
    (if (schema/validate optional-schema aggregate)
      ;; fixme there is serious flaw here where the events are not inserted atomically
      ;;       solution is to insert the whole vector in the same transaction
      ;;       but we got same version for 2 different aggregate/last-event hummmmmm
      {:version (db/insert-async {:on-aggregate nil
                                  :event (->event (keyword (name aggregate-name) "hatched") aggregate)
                                  :aggregate aggregate
                                  :aggregate-name aggregate-name})
       ::muga/command-status ::muga/pending}
      {:error {:status ::muga/invalid
               ;; TODO the error message must be more precise, explaining
               ;;      which attributes, and why
               :message "Invalid attributes"
               ;; TODO give complete coordinate of the error
               :details (me/humanize (schema/explain optional-schema aggregate))}
       ::muga/command-status ::muga/complete})))

(defn fetch-command-result
  [version id]
  (let [;; fixme there will be a bug if some other command at version+1 happened (won't find the element i think)
        event (db/fetch-last-event-version version id)
        aggregate (db/fetch-aggregate-version version id)]
    (if (and event aggregate)
      {:event event
       :aggregate aggregate
       ::muga/command-status ::muga/complete}
      (if-let [error (db/fetch-error-version version id)]
        {:error error
         ::muga/command-status ::muga/complete}
        {::muga/command-status ::muga/pending}))))

;; For those unconvinced by the metaphor
(def create hatch)
(def init hatch)
