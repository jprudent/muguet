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

(defn register-event-handlers!
  [{:keys [aggregate-name events] :as system}]
  (doseq [{:keys [event-handler type]} (vals events)]
    (db/register-tx-fn
      type
      `(fn ~'[db-ctx event-ctx]
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
  [db-ctx event-ctx aggregations]
  (let [{:keys [event aggregate-name aggregate-id stream-version]} event-ctx
        db (xt/db db-ctx)
        last-event (db/fetch-last-event db aggregate-id)]

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
          [[::xt/put event]
           ;; todo on-aggregate could be computed in the function
           #_[::xt/fn (-> event :type) (assoc event-ctx :on-aggregate existing-aggregate)]]
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
  [aggregate-system command-name command]
  (let [interceptors (concat [{:error (fn [_ctx error] {:error error
                                                        ::muga/command-status ::muga/complete})}
                              (validate-command-params (:args-schema command))]
                             (:steps command)
                             [(submit-event (keyword command-name))])
        log-before (fn [stage-f execution-context]
                     (int/before-stage stage-f
                                       (fn [context]
                                         (log/info "Before" (:name (:interceptor execution-context)) ":" (dissoc context :aggregate-system ::int/queue ::int/stack))
                                         context)))
        stages (int/into-stages interceptors [:enter] log-before)
        command-fn (fn [id stream-version command-params]
                     (int/execute
                       {:aggregate-system aggregate-system
                        :aggregate-name (:aggregate-name aggregate-system)
                        :aggregate-id id
                        :stream-version stream-version
                        :command-params command-params}
                       stages))
        tx-aggrs (reduce-kv (fn [tx-aggrs aggr-name {:keys [async] :as aggr-def}]
                              (if (true? async)
                                tx-aggrs
                                (assoc tx-aggrs aggr-name aggr-def)))
                            {} (:aggregations-per-aggregate-id aggregate-system))]
    (db/register-tx-fn
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

;; we don't need to check stream-version because events are called event after
;; event, so they are indexed in that order
(defn call-event-handler
  [aggregation db-ctx event]
  (let [{:keys [aggregate-id ::muga/aggregate-name]} event
        [aggr-name aggr-desc] aggregation
        {:keys [event-handler]} aggr-desc
        db (xt/db db-ctx)
        existing-aggregation (db/fetch-aggregation db aggr-name aggregate-id)]
    ;; todo check schema of the aggregation here
    [[::xt/put (assoc (event-handler existing-aggregation event)
                 :xt/id (db/id->xt-aggregation-id aggr-name aggregate-id)
                 ::muga/document-type aggr-name
                 ::muga/aggregate-name aggregate-name
                 :stream-version (:stream-version event))]]))

(defn register-aggregations!
  [system]
  (when-let [aggregations (not-empty (get system :aggregations-per-aggregate-id))]
    ;; register all functions that update aggregations
    ;; there is one such function per aggregation, be it async or not
    (doseq [aggregation aggregations]
      ;; this function put new version of the aggregation
      (db/register-tx-fn
        (first aggregation)
        `(fn ~'[db-ctx event] (muguet.internals.commands/call-event-handler ~aggregation ~'db-ctx ~'event))))

    ;; register the function that updates all async aggregations
    ;; it calls all functions that update async aggregations
    (let [async-aggrs (reduce-kv (fn [async-aggrs aggr-name {:keys [async] :as aggr-def}]
                                   (if (true? async)
                                     (assoc async-aggrs aggr-name aggr-def)
                                     async-aggrs))
                                 {} aggregations)]
      (db/register-tx-fn :update-async-aggregations
                         `(fn ~'[db-ctx event-ctx]
                            (vec (map (fn ~'[aggr-name]
                                        [:xtdb.api/fn ~'aggr-name ~'event-ctx])
                                      ~(vec (keys async-aggrs))))))))

  ;; when an event is indexed, trigger the update of aggregations
  (db/listen-events #(xt/submit-tx @db/node [[::xt/fn :update-async-aggregations %]]))
  system)

(defn fetch-aggregation
  [aggregation-name id version]
  (db/fetch-aggregation-version aggregation-name id version))
