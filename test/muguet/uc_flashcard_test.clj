(ns muguet.uc-flashcard-test
  "Those tests are using the real implementation of database through an in-memory instance of XTDB"
  (:require [clojure.test :refer :all]
    ;; todo should only import public apis
            [malli.core :as m]
            [malli.util :as mu]
            [muguet.api :as muga]
            [muguet.core :as mug]
            [muguet.internals.commands :as sut]
            [muguet.internals.db :as db]
            [muguet.internals.views :as views]
            [muguet.test-utils :as tu]
            [muguet.utils :as mugu]
            [unilog.config :as log-conf]
            [xtdb.api :as xt])
  (:import (java.time LocalDateTime)
           (java.util.concurrent CountDownLatch ExecutorService Executors)))

(log-conf/start-logging! {:level :info
                          :overrides {"muguet.core" :debug}})


;; todo run those test against different persistence mechanisms


;; |=-----------------------------------------------------------------------=|
;; |=----------------------=[ Flashcard Application ]=----------------------=|
;; |=-----------------------------------------------------------------------=|
;; This is a very simple schema for flashcard with spaced repetition
;; There is just a single aggregate-root with a bunch of values

(def flashcard-schema
  [:map (mugu/->collection-metadata "flashcard")
   [:question
    [:string {:min 1}]]
   [:response
    [:string {:min 1}]]
   [:due-date {:doc "The date this flashcard must be reviewed"}
    'inst?]])

(def flashcard-init-schema
  (mu/optional-keys flashcard-schema [:due-date]))

;; todo could rewrite all aggregation function as a big multimethod on :aggregation-name + event :type

(letfn [(now [] (LocalDateTime/now))
        (add-days [^LocalDateTime date days] (.plusDays date days))]

  (defn evolve-aggregate
    [flashcard event]
    (case (:type event)

      :flashcard/created
      (:body event)

      :flashcard/rated
      (do (import java.time.LocalDateTime)
          (let [due-date (add-days (now) (:body event))]
            (assoc flashcard :due-date due-date))))))

(def Rating
  [:int {:error/message "The rating should be an integer between 0 and 5"
         :min 0
         :max 5}])

(def PosInt [:int {:min 1}])

;; todo all schemas must be CamelCase
(def MeanAggregation
  [:map
   [:number {:doc "The number of time the flashcard have been rated"} PosInt]
   [:sum {:doc "The sum of all ratings"} PosInt]
   [:mean {:doc "The result of the computation sum/number"} [:double {:min 0}]]])

(defn mean
  [aggregation rating]
  (let [aggregation (-> (update aggregation :number inc)
                        (update :sum + rating))]
    (assoc aggregation :mean (double (/ (:sum aggregation) (:number aggregation))))))

(defn evolve-mean
  [aggregation event]
  (case (:type event)

    :flashcard/created
    {:number 0 :sum 0}

    :flashcard/rated
    (mean aggregation (:body event))))

(def flashcard-system-config
  {:schema flashcard-schema
   :aggregate-name :flashcard
   :events {:flashcard/created {:body-schema flashcard-init-schema}
            :flashcard/rated {:body-schema Rating}}
   ;; a command is an action on the system with an intention to change it
   :commands {:flashcard/create {:args-schema (mu/optional-keys flashcard-schema [:due-date])
                                 :steps [(mug/build-event :flashcard/created :command-params)]}
              :flashcard/rate {:args-schema Rating
                               :steps [(mug/build-event :flashcard/rated :command-params)]}}

   ;; An aggregation is a read model dedicated to a particular view of the application
   ;; An aggregation is updated for each event that is issued.
   ;; The difference between a query and an aggregation is that an aggregation
   ;; doesn't need any computation, similar in that aspect of a cache entry.
   ;; Aggregations are versioned in the database like event stream and aggregate.

   ;; Aggregations are transactional by default or asynchronous
   ;; - Transactional is the default. The aggregation gets updated at the same time
   ;;   as the event is issued. Transactional aggregations are always up-to-date.
   ;;   Commands would typically use transactional aggregations to check validity.
   ;;   When a transactional aggregation throws an error, the whole transaction is
   ;;   canceled, including the events that triggered it.
   ;;   pros: synchronicity is simpler
   ;;   cons: transactions take more time
   ;; - Asynchronous. The aggregation gets updated some time later, uncoupled
   ;;   with the event that should update the aggregation.

   ;; Here `-transactional` and `-async` suffixes are appended to the
   ;; aggregation name to make things clearer in my test case. There is no
   ;; conventions whatsoever.

   :aggregations {:flashcard/aggregate {:doc "Reference aggregate for a flashcard.
   This is the kind of document you would store in a conventional database.
   Can be fetched in commands to check the validity of the command."
                                        :evolve `evolve-aggregate
                                        :schema (m/form (mu/optional-keys flashcard-schema [:due-date]))}
                  :flashcard/mean-transactional {:doc "A transactional aggregate that represents the mean rating of a flashcard"
                                                 :evolve `evolve-mean
                                                 ;; todo test that the schema is checked
                                                 :schema MeanAggregation}
                  :flashcard/mean-async {:doc "An async aggregate that represents the mean rating of a flashcard"
                                         :evolve `evolve-mean
                                         :schema MeanAggregation
                                         :async true}}}),

;; |=-----------------------------------------------------------------------=|
;; |=--------------------------=[ Flashcard Tests ]=------------------------=|
;; |=-----------------------------------------------------------------------=|

;; fixme kaocha tests fail the first time they run

;; fixme tests are blinking sometimes

;; fixme each test should close the system
(deftest create-flashcard-test
  (let [system (mug/start! flashcard-system-config)
        id 1
        create-cmd (sut/get-command system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id id}
        v1 (create-cmd id nil flashcard-init)
        {:keys [event ::muga/command-status]} (tu/blocking-fetch-command-result system v1 id)
        aggregate (sut/fetch-aggregation system :flashcard/aggregate 1 v1)
        expected-event {:type :flashcard/created
                        :aggregate-id id
                        :body flashcard-init}]
    (is (= :muguet.api/complete command-status))
    (is (= expected-event (dissoc event :stream-version)))
    (is (= flashcard-init (dissoc aggregate :stream-version)))
    (is (and (:stream-version event) (:stream-version aggregate)))
    (is (= (:stream-version event) (:stream-version aggregate)) "aggregate and events have same stream-version")

    ;; -- the aggregate and event can be retrieved separately from a command result
    (is (= [event] (db/fetch-event-history (xt/db (:node system)) id)))
    (is (= event (db/fetch-last-event-version system (:stream-version event) id)))))

(deftest already-exists-test
  (let [system (mug/start! flashcard-system-config)
        create-cmd (sut/get-command system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id 1}
        v1 (create-cmd 1 nil flashcard-init)
        v2 (create-cmd 1 nil flashcard-init)
        res2 (tu/blocking-fetch-command-result system v2 1)]
    (is (= v1 (get-in res2 [:error :details :actual])))
    (is (= nil (get-in res2 [:error :details :expected])))))

(deftest concurrent-creation-test
  (let [system (mug/start! flashcard-system-config)
        latch (CountDownLatch. 1)
        ready (CountDownLatch. 10)
        executor ^ExecutorService (Executors/newFixedThreadPool 10)
        create-cmd (sut/get-command system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id 1}
        _ (future
            (.await ready)
            ; go !
            (.countDown latch))
        futures (.invokeAll executor ^Callable (repeat 10 (fn []
                                                            (.countDown ready)
                                                            ;; ready ?
                                                            (.await latch)
                                                            ;; go !
                                                            (create-cmd 1 nil flashcard-init))))
        res (mapv (fn [future] (tu/blocking-fetch-command-result system @future 1)) futures)
        error? #(contains? % :error)]
    (is (every? (fn [r] (= ::muga/complete (::muga/command-status r))) res) "every command can retrieve a result")
    (is (= 1 (count (remove error? res))) "only 1 command succeeds")
    (is (= 9 (count (filter error? res))) "9 commands failed")))

(deftest invalid-arguments-test
  (let [system (mug/start! flashcard-system-config)
        create (sut/get-command system :flashcard/create)
        v1 (create 1 nil nil)
        res (tu/blocking-fetch-command-result system v1 1)]
    (is (= :invalid (get-in res [:error :status])))))

(deftest rate-flashcard-test
  (let [system (mug/start! flashcard-system-config)
        id 1
        create-cmd (sut/get-command system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id id}
        v1 (create-cmd id nil flashcard-init)
        create-result (tu/blocking-fetch-command-result system v1 id)
        created-event (:event create-result)
        _ (is (= :muguet.api/complete (:muguet.api/command-status create-result)))

        rate-cmd (sut/get-command system :flashcard/rate)
        rating 4
        v2 (rate-cmd id v1 rating)
        {rated-event :event} (tu/blocking-fetch-command-result system v2 id)
        fc-rated (sut/fetch-aggregation system :flashcard/aggregate id v2)]
    (is (= {:type :flashcard/rated
            :aggregate-id id
            :body rating}
           (dissoc rated-event :stream-version)))
    (is (= flashcard-init (dissoc fc-rated :due-date ::muga/document-type :stream-version)))
    (is (pos-int? (compare (:due-date fc-rated) (LocalDateTime/now))))

    (is (= [created-event rated-event] (db/fetch-event-history (xt/db (:node system)) id)))))

(deftest invalid-version-test
  (let [system (mug/start! flashcard-system-config)
        create-cmd (sut/get-command system :flashcard/create)
        rate-cmd (sut/get-command system :flashcard/rate)
        flashcard-init {:question "q?" :response "r" :id 1}
        v1 (create-cmd 1 nil flashcard-init)
        v2 (rate-cmd 1 v1 5)
        result (tu/blocking-fetch-command-result system (rate-cmd 1 v1 3) 1)]
    (is (= v2 (get-in result [:error :details :actual])))
    (is (= v1 (get-in result [:error :details :expected])))))

(deftest view-all-test
  (let [system (mug/start! flashcard-system-config)
        create (sut/get-command system :flashcard/create)
        init-values (map (fn [id] {:question "q?" :response "r" :id id}) (range 10))
        _ (mapv (fn [init-value]
                  (let [version (create (:id init-value) nil init-value)]
                    (tu/blocking-fetch-command-result system version (:id init-value))))
                init-values)
        all (views/all-aggregations system :flashcard/aggregate)]
    (is (= init-values (sort-by :id (map #(dissoc % :stream-version) all))))))

(deftest mean-aggregation-transactional-test
  (let [system (mug/start! flashcard-system-config)
        create (sut/get-command system :flashcard/create)
        rate (sut/get-command system :flashcard/rate)

        v1 (create 1 nil {:question "q?" :response "r" :id 1})
        _ (is (tu/blocking-fetch-command-result system v1 1))
        _ (is (= {:number 0 :sum 0}
                 (dissoc (sut/fetch-aggregation system :flashcard/mean-transactional 1 v1) :stream-version)))

        v2 (rate 1 v1 5)
        _ (tu/blocking-fetch-command-result system v2 1)
        _ (is (= {:number 1 :sum 5 :mean 5.0 :stream-version v2}
                 (sut/fetch-aggregation system :flashcard/mean-transactional 1 v2)))

        v3 (rate 1 v2 0)
        _ (tu/blocking-fetch-command-result system v3 1)
        _ (is (= {:number 2 :sum 5 :mean 2.5 :stream-version v3}
                 (sut/fetch-aggregation system :flashcard/mean-transactional 1 v3)))]))

(deftest mean-aggregation-async-test
  (let [system (mug/start! flashcard-system-config)
        create (sut/get-command system :flashcard/create)
        rate (sut/get-command system :flashcard/rate)
        v1 (create 1 nil {:question "q?" :response "r" :id 1})
        _ (is (nil? (sut/fetch-aggregation system :flashcard/mean-async 1 v1)))
        v2 (rate 1 v1 5)
        ;; The aggregation update gets delayed, so we wait a bit ...
        ;; todo don't use Thread/sleep
        _ (Thread/sleep 200)
        _ (is (= {:number 1 :sum 5 :mean 5.0 :stream-version v2}
                 (sut/fetch-aggregation system :flashcard/mean-async 1 v2)))
        v3 (rate 1 v2 0)
        _ (Thread/sleep 200)
        _ (is (= {:number 2 :sum 5 :mean 2.5 :stream-version v3}
                 (sut/fetch-aggregation system :flashcard/mean-async 1 v3)))]))

(defn evolve-broken
  [aggregation event]
  (throw (ex-info "Broken aggregation" {:aggregation aggregation
                                        :event event})))

(deftest broken-transactional-rollback-test
  (testing "A broken aggregation rollbacks the whole transaction"
    (let [system (mug/start! (assoc-in flashcard-system-config
                                       [:aggregations :flashcard/broken-tx]
                                       {:doc "A transactional aggregation that can throw exceptions"
                                        :evolve `evolve-broken
                                        :schema nil}))
          v1 (mug/command system :flashcard/create 1 nil {:question "q?", :response "r", :id 1})
          result (tu/blocking-fetch-command-result system v1 1)]
      (is (= {:error "The command failed in an unexpected manner. Check the logs for details."
              ::muga/command-status ::muga/complete} result))
      (is (= nil (db/fetch-last-event-version system v1 1))))))

(deftest broken-async-aggregation-test
  (let [system (mug/start! (assoc-in flashcard-system-config
                                     [:aggregations :flashcard/broken-async]
                                     {:doc "A transactional aggregation that can throw exceptions"
                                      :evolve `evolve-broken
                                      :schema nil
                                      :async true}))
        v1 (mug/command system :flashcard/create 1 nil {:question "q?", :response "r", :id 1})
        result-v1 (tu/blocking-fetch-command-result system v1 1)
        aggregate (sut/fetch-aggregation system :flashcard/aggregate 1 v1)
        expected-error {:details {:aggregation nil
                                  :event (:event result-v1)}
                        :message "An error occurred trying to evolve the aggregation.
                 This is likely a bug in the evolve function.
                 Check the logs.
                 To reproduce the error, try to run the evolve function against the event and aggregation found in the details of this document.
                 This aggregation will stop to evolve until something is done.
                 You'll have to recompute this aggregation when the bug is fixed."
                        :status :muguet.api/error
                        :stream-version (get-in result-v1 [:event :stream-version])}]
    ;; the transaction succeeded
    (is (= :flashcard/created (get-in result-v1 [:event :type])))
    (is (= (:event result-v1) (db/fetch-last-event-version system v1 1)))
    (is (= 1 (:id aggregate)))

    ;; but the aggregate can't be found
    ;; todo still have to find a better way that thread sleeping
    (Thread/sleep 500)

    (is (= {:number 0
            :stream-version (get-in result-v1 [:event :stream-version])
            :sum 0} (sut/fetch-aggregation system :flashcard/mean-async 1 v1))
        "Other async aggregations continue to evolve")
    (is (= expected-error
           (update (sut/fetch-aggregation system :flashcard/broken-async 1 v1) :details dissoc :exception))
        "Instead of the aggregation, an error document is stored.")

    (let [v2 (mug/command system :flashcard/rate 1 v1 5)
          result-v2 (tu/blocking-fetch-command-result system v2 1)]

      (is (= :flashcard/rated (get-in result-v2 [:event :type])))

      ;; todo still have to find a better way that thread sleeping
      (Thread/sleep 500)

      (is (= {:number 1
              :stream-version (get-in result-v2 [:event :stream-version])
              :sum 5
              :mean 5.0} (sut/fetch-aggregation system :flashcard/mean-async 1 v2))
          "Other async aggregations continue to evolve")

      (is (nil? (sut/fetch-aggregation system :flashcard/broken-async 1 v2))
          "There is no new version for this aggregation")
      (is (= expected-error
             (update (sut/fetch-aggregation system :flashcard/broken-async 1 v1) :details dissoc :exception))
          "The aggregation is not updated and will be freezed forever."))))

;; todo multisystem tests`

;; todo a command that decides multiple events

;; todo an aggregation on several aggregates
