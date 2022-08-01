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

(defn before?
  "Compares 2 transactions"
  [tx1 tx2]
  (<= (::xt/tx-id tx1) (::xt/tx-id tx2)))

;; todo move this in public api
;; todo why is there an aggregate id mandatory ?
;; todo have a single argument named `command-result`
(defn fetch-command-result
  "Given a stream-version returned by a command (all commands ares asynchronous)
  and an aggregate-id, returns the status, event, aggregate or error "
  [system stream-version id]
  ;; it can happen that the command fails outside the context of a database
  ;; transaction
  ;; todo can that happen for a command success ?
  ;;      no, complete only happens when the command fails
  ;;      I must change the api of return value of a command because it either returns { ...complete } if failure
  ;;      or { xt/tx-id, xt/tx-time } in case of success. Must be more monadic.
  (if (= ::muga/complete (::muga/command-status stream-version))
    stream-version
    (let [;; fixme there will be a bug if some other command at stream-version+1 happened (won't find the element i think)
          ;; fixme retrieving 2 documents smells like teen spirit (https://github.com/jprudent/muguet/issues/1)
          event (db/fetch-last-event-version system stream-version id)]
      (if event
        {:event event
         ::muga/command-status ::muga/complete}
        (if-let [error (db/fetch-error-version system stream-version id)]
          {:error error
           ::muga/command-status ::muga/complete}
          (if (before? stream-version (xt/latest-completed-tx (:node system)))
            ;; in that case, the command failed because the version have been indexed but the
            ;; documents can not be found. I may happen when the evolve function of aggregation
            ;; failed.
            {:error "The command failed in an unexpected manner. Check the logs for details."
             ::muga/command-status ::muga/complete}
            {::muga/command-status ::muga/pending}))))))

(defn- make-event-builder
  [{:keys [type body-schema]}]
  (fn [aggregate-id body]
    {:pre [(m/validate body-schema body)]}
    {:type type :body body :aggregate-id aggregate-id}))

(defn assoc-event-builder
  [event]
  (assoc event :builder (make-event-builder event)))

(defn submit-event
  [system f]
  {:name "submit-event"
   :enter (fn [context]
            (when (not (:event context)) (throw (ex-info "Can't submit event. Missing event in context." context)))
            (xt/submit-tx (:node system) [[::xt/fn f (select-keys context [:event
                                                                           :aggregate-name
                                                                           :aggregate-id
                                                                           :stream-version])]]))})

(defn event-tx-fn
  [db-ctx event-ctx aggregations]
  (let [{:keys [event aggregate-name aggregate-id stream-version]} event-ctx
        last-event (db/fetch-last-event (xt/db db-ctx) aggregate-id)]

    ;; there are 3 tx references in this function:
    ;; - the :stream-version of event-ctx is the expected version to be found in
    ;;   the database (Optimistic Concurrency)
    ;; - the :stream-version of the last-event is the actual version of the db
    ;; - the :indexing-tx of db-ctx is the new version that will result of this function

    (if (= stream-version (:stream-version last-event))
      (let [event (assoc event
                    :xt/id (muguet.internals.db/id->xt-last-event-id aggregate-id)
                    ::muga/aggregate-name aggregate-name
                    ::muga/document-type ::muga/event
                    :stream-version (:indexing-tx db-ctx))]
        (into
          [[::xt/put event]]
          (vec (map (fn [aggr-name] [:xtdb.api/fn aggr-name event]) (keys aggregations)))))
      (let [error-doc {:xt/id (muguet.internals.db/id->xt-error-id aggregate-id)
                       :stream-version (:indexing-tx db-ctx)
                       ;; todo change error message: this is a conflict
                       :status ::muga/not-found
                       ::muga/aggregate-name aggregate-name
                       ::muga/document-type ::muga/error
                       :message "the specified aggregate version couldn't be find"
                       :details {:actual (:stream-version last-event)
                                 :expected stream-version}}]
        (clojure.tools.logging/error error-doc)
        [[::xt/put error-doc]]))))

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

(defn register-command
  ;; todo command-name is only there to get clean name, but it could be generated
  [system command-name command]
  (let [interceptors (concat [{:error (fn [_ctx error] {:error error
                                                        ::muga/command-status ::muga/complete})}
                              (validate-command-params (:args-schema command))]
                             (:steps command)
                             [(submit-event system (keyword command-name))])
        log-before (fn [stage-f execution-context]
                     (int/before-stage stage-f
                                       (fn [context]
                                         (log/info "Before" (:name (:interceptor execution-context)) ":" (dissoc context :aggregate-system ::int/queue ::int/stack))
                                         context)))
        stages (int/into-stages interceptors [:enter] log-before)
        command-fn (fn [id stream-version command-params]
                     (int/execute
                       {:aggregate-system system
                        :aggregate-name (:aggregate-name system)
                        :aggregate-id id
                        :stream-version stream-version
                        :command-params command-params}
                       stages))
        tx-aggrs (reduce-kv (fn [tx-aggrs aggr-name {:keys [async] :as aggr-def}]
                              (if (true? async)
                                tx-aggrs
                                (assoc tx-aggrs aggr-name aggr-def)))
                            {} (:aggregations system))]
    (db/register-tx-fn
      system
      (keyword command-name)
      `(fn ~'[db-ctx event-ctx]
         (muguet.internals.commands/event-tx-fn ~'db-ctx ~'event-ctx ~tx-aggrs)))
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

(defn register-commands! [system]
  (reduce-kv (fn [system command-name command]
               (assoc-in system [:commands command-name]
                         (register-command system command-name command)))
             system
             (:commands system)))

(defn get-command [system command-name]
  (get-in system [:commands command-name]))

(defn assoc-event-builders [system]
  (update system :events
          #(reduce-kv (fn [registry event-type event]
                        (assoc registry
                          event-type
                          (-> event
                              (assoc :type event-type)
                              (assoc-event-builder))))
                      % %)))

(defn make-aggregation-document
  [aggregation aggregate-id aggregation-name aggregate-name event]
  (assoc aggregation
    :xt/id (db/id->xt-aggregation-id aggregation-name aggregate-id)
    ::muga/document-type aggregation-name
    ::muga/aggregate-name aggregate-name
    :stream-version (:stream-version event)))

;; we don't need to check stream-version because events are called event after
;; event, so they are indexed in that order
(defn call-evolve
  [aggregation db-ctx event]
  (let [{:keys [aggregate-id ::muga/aggregate-name]} event
        [aggregation-name aggr-desc] aggregation
        {:keys [evolve]} aggr-desc
        db (xt/db db-ctx)
        existing-aggregation (db/fetch-aggregation db aggregation-name aggregate-id)]
    ;; todo check schema of the aggregation here
    (if (= ::muga/error (:status existing-aggregation))
      (log/error "This aggregation is erroneous. Fix it." (pr-str existing-aggregation))
      ;; fixme the aggregation must have same valid time than the event
      ;;   so, event async aggrgations have same validity than the event that triggers it
      [[::xt/put (make-aggregation-document
                   (evolve existing-aggregation event)
                   aggregate-id
                   aggregation-name
                   aggregate-name
                   event)]])))

(defn async-aggregation-error
  [aggregation db-ctx event exception]
  (let [[aggr-name _aggr-desc] aggregation
        aggregate-id (:aggregate-id event)
        db (xt/db db-ctx)]
    [[::xt/put {:xt/id (muguet.internals.db/id->xt-aggregation-id aggr-name aggregate-id)
                :stream-version (:stream-version event)
                :status ::muga/error
                ::muga/aggregate-name aggr-name
                ::muga/document-type ::muga/error
                :message "An error occurred trying to evolve the aggregation.
                 This is likely a bug in the evolve function.
                 Check the logs.
                 To reproduce the error, try to run the evolve function against the event and aggregation found in the details of this document.
                 This aggregation will stop to evolve until something is done.
                 You'll have to recompute this aggregation when the bug is fixed."
                :details {:event (db/clean-doc event)
                          :aggregation (db/fetch-aggregation db aggr-name aggregate-id)
                          :exception exception}}]]))

(defn register-aggregations!
  [system]
  (when-let [aggregations (not-empty (get system :aggregations))]
    ;; register all functions that evolve aggregations
    (doseq [aggregation aggregations
            :let [transactional-evolve `(fn ~'[db-ctx event] (muguet.internals.commands/call-evolve ~aggregation ~'db-ctx ~'event))
                  async-evolve `(fn ~'[db-ctx event]
                                  (try (muguet.internals.commands/call-evolve ~aggregation ~'db-ctx ~'event)
                                       (catch ~'Exception ~'e
                                         (muguet.internals.commands/async-aggregation-error ~aggregation ~'db-ctx ~'event ~'e))))]]
      (db/register-tx-fn system (first aggregation) transactional-evolve)
      (db/register-tx-fn system (str (first aggregation) "_async") async-evolve))

    ;; register the function that updates all async aggregations
    ;; it calls all functions that update async aggregations
    (let [async-aggrs (reduce-kv (fn [async-aggrs aggr-name {:keys [async] :as aggr-def}]
                                   (if (true? async)
                                     (assoc async-aggrs aggr-name aggr-def)
                                     async-aggrs))
                                 {} aggregations)]
      (db/register-tx-fn system
                         :update-async-aggregations
                         `(fn ~'[db-ctx event-ctx]
                            (vec (map (fn ~'[aggr-name]
                                        [:xtdb.api/fn (str ~'aggr-name "_async") ~'event-ctx])
                                      ~(vec (keys async-aggrs))))))))

  ;; when an event is indexed, trigger the update of aggregations
  (db/listen-events system #(xt/submit-tx (:node system) [[::xt/fn :update-async-aggregations %]]))
  system)


;; fixme introduct an aggregate "coordinate" with id and version. That will save an arity and avoid arg misplacements #truestory
(defn fetch-aggregation
  [system aggregation-name id version]
  ;; todo check the aggregation-name exists
  (db/fetch-aggregation-version system aggregation-name id version))
