(ns muguet.internals.commands
  "those are out of the box commands provided by muguet"
  (:require [clojure.tools.logging :as log]
            [exoscale.interceptor :as int]
            [malli.core :as m]
            [muguet.api :as muga]
            [muguet.internals.db :as db]
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

;; todo move this in public api
;; todo why is there an aggregate id mandatory ?
;; todo have a single argument named `command-result`
(defn fetch-command-result
  "Given a stream-version returned by a command (all commands ares asynchronous)
  and an aggregate-id, returns the status, event, aggregate or error "
  [stream-version id]
  ;; it can happen that the command fails outside the context of a database
  ;; transaction
  ;; todo can that happen for a command success ?
  (if (= ::muga/complete (::muga/command-status stream-version))
    stream-version
    (let [;; fixme there will be a bug if some other command at stream-version+1 happened (won't find the element i think)
          ;; fixme retrieving 2 documents smells like teen spirit (https://github.com/jprudent/muguet/issues/1)
          event (db/fetch-last-event-version stream-version id)
          aggregate (db/fetch-aggregate-version stream-version id)]
      (if (and event aggregate)
        {:event event
         :aggregate aggregate
         ::muga/command-status ::muga/complete}
        (if-let [error (db/fetch-error-version stream-version id)]
          {:error error
           ::muga/command-status ::muga/complete}
          {::muga/command-status ::muga/pending})))))

(defn- make-event-builder
  [{:keys [type body-schema]}]
  (fn [aggregate-id body]
    {:pre [(m/validate body-schema body)]}
    {:type type :body body :aggregate-id aggregate-id}))
(defn assoc-event-builder
  [event]
  (assoc event :builder (make-event-builder event)))

(defn register-events!
  [{:keys [aggregate-name event-registry] :as system}]
  (doseq [{:keys [event-handler type]} (vals event-registry)]
    (db/register-tx-fn type `(fn ~'[db-ctx event-ctx]
                               (let ~'[{:keys [event on-aggregate aggregate-id]} event-ctx]
                                 [[::xt/put (assoc (~event-handler ~'on-aggregate ~'event)
                                              :xt/id (muguet.internals.db/id->xt-aggregate-id ~'aggregate-id)
                                              ::muga/aggregate-name ~aggregate-name
                                              ::muga/document-type ::muga/aggregate
                                              :stream-version (:indexing-tx ~'db-ctx))]]))))
  system)

(defn submit-event
  [f]
  {:name "submit-event"
   :enter (fn [context]
            (when (not (:event context)) (throw (ex-info "Can't submit event. Missing event in context." context)))
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
                       :details {:actual (:stream-version existing-aggregate)
                                 :expected stream-version}}]
        (clojure.tools.logging/error error-doc)
        [[::xt/put error-doc]]))))

(defn register-command
  ;; todo command-name is only there to get clean name, but it could be generated
  [aggregate-system command-name user-interceptors]
  (let [command-fn (fn [id stream-version command-params]
                     (int/execute
                       {:aggregate-system aggregate-system
                        :aggregate-id id
                        :stream-version stream-version
                        :command-params command-params}
                       (int/into-stages
                         (concat [{:error (fn [_ctx error] {:error error
                                                            ::muga/command-status ::muga/complete})}]
                                 user-interceptors
                                 [(submit-event (keyword command-name))])
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

(defn error
  [context error]
  (int/error context error))

(defn validate-command-params
  [schema]
  {:name "validate-command-params"
   :enter (fn [{:keys [command-params] :as context}]
            (if (schema/validate schema command-params)
              context
              (let [explanation (schema/explain schema command-params)]
                (int/error context
                           {:status :invalid
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

(defn register-commands! [system]
  (reduce-kv (fn [system command-name interceptors]
               (assoc-in system [:commands command-name]
                         (register-command system command-name interceptors)))
             system
             (:commands system)))

(defn get-command [system command-name]
  (get-in system [:commands command-name]))

(defn assoc-event-builders [system]
  (update system :event-registry
          #(reduce-kv (fn [registry event-type event]
                        (assoc registry
                          event-type
                          (-> event
                              (assoc :type event-type)
                              (assoc-event-builder))))
                      % %)))
