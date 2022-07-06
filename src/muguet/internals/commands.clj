(ns muguet.internals.commands
  "those are out of the box commands provided by muguet"
  (:require [clojure.tools.logging :as log]
            [exoscale.interceptor :as int]
            [malli.core :as m]
            [malli.error :as me]
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
(defn register-event-handlers
  [aggregate-systems]
  (doseq [{:keys [aggregate-name]} aggregate-systems]
    (db/register-event-handler
     (keyword (name aggregate-name) "hatched")
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
              [[::xt/put error-doc]])))))))

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
      {:stream-version (db/insert-async {:on-aggregate nil
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

;; todo move this in public api
(defn fetch-command-result
  [stream-version id]
  (let [;; fixme there will be a bug if some other command at stream-version+1 happened (won't find the element i think)
        event (db/fetch-last-event-version stream-version id)
        aggregate (db/fetch-aggregate-version stream-version id)]
    (if (and event aggregate)
      {:event event
       :aggregate aggregate
       ::muga/command-status ::muga/complete}
      (if-let [error (db/fetch-error-version stream-version id)]
        {:error error
         ::muga/command-status ::muga/complete}
        {::muga/command-status ::muga/pending}))))

;; For those unconvinced by the metaphor
(def create hatch)
(def init hatch)

(defn- make-event-builder
  [{:keys [type body-schema]}]
  (fn [aggregate-id body]
    {:pre [(m/validate body-schema body)]}
    {:type type :body body :aggregate-id aggregate-id}))

(defn assoc-event-builder
  [event]
  (assoc event :builder (make-event-builder event)))

(defn register-events!
  [{:keys [aggregate-name event-registry] :as _system}]
  (doseq [{:keys [event-handler type]} (vals event-registry)]
    (db/register-tx-fn type `(fn ~'[db-ctx event-ctx]
                               (let ~'[{:keys [event on-aggregate aggregate-id]} event-ctx]
                                 [[::xt/put (assoc (~event-handler ~'on-aggregate ~'event)
                                              :xt/id (muguet.internals.db/id->xt-aggregate-id ~'aggregate-id)
                                              ::muga/aggregate-name ~aggregate-name
                                              ::muga/document-type ::muga/aggregate
                                              :stream-version (:indexing-tx ~'db-ctx))]])))))

(defn submit-event
  [f]
  {:name "submit-event"
   :enter (fn [context]
            {:pre [(:event context)]}
            (xt/submit-tx @db/node [[::xt/fn f (select-keys context [:event
                                                                     :aggregate-name
                                                                     :aggregate-id
                                                                     :stream-version])]]))})

(defn event-tx-fn
  [db-ctx event-ctx]
  (let [{:keys [event aggregate-name aggregate-id stream-version]} event-ctx
        db (xt/db db-ctx)
        existing-aggregate (muguet.internals.db/fetch-aggregate db aggregate-id)]
    (if (= stream-version (:stream-version existing-aggregate))
      [[::xt/put (assoc event
                   :xt/id (muguet.internals.db/id->xt-last-event-id aggregate-id)
                   ::muga/aggregate-name aggregate-name
                   ::muga/document-type ::muga/event
                   :stream-version (:indexing-tx db-ctx))]
       [::xt/fn (-> event :type) (assoc event-ctx :on-aggregate existing-aggregate)]]
      (let [error-doc {:xt/id (muguet.internals.db/id->xt-error-id aggregate-id)
                       :stream-version (:indexing-tx db-ctx)
                       ;; todo change error message: this is a conflict
                       :status ::muga/not-found
                       ::muga/aggregate-name aggregate-name
                       ::muga/document-type ::muga/error
                       :message "the specified aggregate version couldn't be find"
                       :details {:actual existing-aggregate
                                 :expected (:on-aggregate event-ctx)}}]
        (clojure.tools.logging/error error-doc)
        [[::xt/put error-doc]]))))

(defn register-command
  ;; todo command-name is only there to get clean name, but it could be generated
  [aggregate-system command-name interceptors]
  (let [command-fn (fn [id stream-version command-params]
                     (sc.api/spy)
                     (int/execute
                      {:aggregate-system aggregate-system
                       :aggregate-id id
                       :stream-version stream-version
                       :command-params command-params}
                      (int/into-stages
                       (into interceptors [(submit-event (keyword command-name))])
                       [:enter]
                       (fn [stage-f execution-context]
                         (int/before-stage stage-f (fn [context]
                                                     (log/info "Before" (:name (:interceptor execution-context)) ":" (dissoc context :aggregate-system ::int/queue ::int/stack))
                                                     context))))))]
    (db/register-tx-fn
     (keyword command-name)
     '(fn [db-ctx event-ctx] (muguet.internals.commands/event-tx-fn db-ctx event-ctx)))
    command-fn))

;; typical command interceptor chain:
;; - validate command args (schema)
;; - optional: retrieve the reference `:aggregate`
;; - optional: custom business validations (can use `:aggregate`)
;; - transform :command-args to :event-body
;; - build event

(defn validate-command-params
  [schema]
  {:name "validate-command-params"
   :enter (fn [{:keys [command-params] :as context}]
            (if (schema/validate schema command-params)
              context
              (let [explanation (schema/explain schema command-params)]
                (assoc context :error {:status :invalid
                                       :error-message "Arguments are invalid"
                                       :details explanation}))))})

(defn build-event
  [type event-body-fn]
  {:name "build-event"
   :enter (fn ^{:doc "interceptor that build and add an event to the context"}
            [{:keys [aggregate-system aggregate-id] :as context}]
            (let [event-builder (-> aggregate-system :event-registry type :builder)
                  event (event-builder aggregate-id (event-body-fn context))]
              (assoc context :event event)))})
