(ns muguet.uc-flashcard-test
  "Those tests are using the real implementation of database through an in-memory instance of XTDB"
  (:require [clojure.test :refer :all]
    ;; todo should only import public apis
            [clojure.tools.logging :as log]
            [malli.util :as mu]
            [muguet.api :as muga]
            [muguet.core :as mug]
            [muguet.internals.commands :as sut]
            [muguet.internals.db :as db]
            [muguet.internals.views :as views]
            [muguet.test-utils :as tu]
            [muguet.utils :as mugu]
            [xtdb.api :as xt])
  (:import (java.time LocalDateTime)
           (java.util.concurrent CountDownLatch ExecutorService Executors)))


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

(letfn [(now [] (LocalDateTime/now))
        (add-days [^LocalDateTime date days] (.plusDays date days))]

  (defn apply-created-event
    [flashcard created-event]
    {:pre [(not flashcard)]}
    (:body created-event))

  (defn apply-rated-event
    [flashcard rated-event]
    (import java.time.LocalDateTime)
    (let [due-date (add-days (now) (:body rated-event))]
      (assoc flashcard :due-date due-date))))

(def rating-schema
  [:int {:error/message "The rating should be an integer between 0 and 5"
         :min 0
         :max 5}])

(def flashcard-system-config
  {:schema flashcard-schema
   :aggregate-name :flashcard
   :event-registry {:flashcard/rated {:body-schema rating-schema
                                      :event-handler `apply-rated-event}
                    :flashcard/created {:body-schema flashcard-init-schema
                                        :event-handler `apply-created-event}}
   :commands {:flashcard/create [(sut/validate-command-params (mu/optional-keys flashcard-schema [:due-date]))
                                 (sut/build-event :flashcard/created :command-params)]
              :flashcard/rate [(sut/validate-command-params rating-schema)
                               (sut/build-event :flashcard/rated :command-params)]}})

(def flashcard-system (atom nil))

;; |=-----------------------------------------------------------------------=|
;; |=--------------------------=[ Flashcard Tests ]=------------------------=|
;; |=-----------------------------------------------------------------------=|

(use-fixtures :each (fn [f]
                      (log/info "-=[ Initialize Muguet ]=-")
                      (reset! flashcard-system (mug/start! flashcard-system-config))
                      (log/info "-=[ Strarting Test ]=-")
                      (f)))

(deftest create-flashcard-test
  (let [id 1
        ;; todo find trick so we don't have to register commands in tests
        create-cmd (sut/get-command @flashcard-system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id id}
        stream-version (create-cmd id nil flashcard-init)
        {:keys [aggregate event ::muga/command-status]} (tu/blocking-fetch-result stream-version id)
        expected-event {:type :flashcard/created
                        :aggregate-id id
                        :body flashcard-init}]
    (is (= :muguet.api/complete command-status))
    (is (= expected-event (dissoc event :stream-version)))
    (is (= flashcard-init (dissoc aggregate :stream-version)))
    (is (and (:stream-version event) (:stream-version aggregate)))
    (is (= (:stream-version event) (:stream-version aggregate)) "aggregate and events have same stream-version")

    ;; -- the aggregate and event can be retrieved separately from a command result
    (is (= [event] (db/fetch-event-history (xt/db @db/node) id)))
    (is (= event (db/fetch-last-event-version (:stream-version event) id)))
    (is (= aggregate (db/fetch-aggregate-version (:stream-version event) id)))))

(deftest already-exists-test
  (let [create-cmd (sut/get-command @flashcard-system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id 1}
        v1 (create-cmd 1 nil flashcard-init)
        v2 (create-cmd 1 nil flashcard-init)
        res2 (tu/blocking-fetch-result v2 1)]
    (is (= v1 (get-in res2 [:error :details :actual])))
    (is (= nil (get-in res2 [:error :details :expected])))))

(deftest concurrent-test
  (testing "concurrent hatching"
    (let [latch (CountDownLatch. 1)
          ready (CountDownLatch. 10)
          executor ^ExecutorService (Executors/newFixedThreadPool 10)
          create-cmd (sut/get-command @flashcard-system :flashcard/create)
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
          res (mapv (fn [future] (tu/blocking-fetch-result @future 1)) futures)
          error? #(contains? % :error)]
      (is (every? (fn [r] (= ::muga/complete (::muga/command-status r))) res) "every command can retrieve a result")
      (is (= 1 (count (remove error? res))) "only 1 command succeeds")
      (is (= 9 (count (filter error? res))) "9 commands failed"))))

;; todo invalid command args

(deftest invalid-arguments-test
  (let [create (sut/get-command @flashcard-system :flashcard/create)
        v1 (create 1 nil nil)
        res (tu/blocking-fetch-result v1 1)]
    (is (= :invalid (get-in res [:error :status])))))

(deftest rate-flashcard-test
  (let [id 1
        create-cmd (sut/get-command @flashcard-system :flashcard/create)
        flashcard-init {:question "q?" :response "r" :id id}
        version (create-cmd id nil flashcard-init)
        create-result (tu/blocking-fetch-result version id)
        created-event (:event create-result)
        _ (is (= :muguet.api/complete (:muguet.api/command-status create-result)))

        rate-cmd (sut/get-command @flashcard-system :flashcard/rate)
        rating 4
        cmd-result (rate-cmd id version rating)
        {fc-rated :aggregate rated-event :event} (tu/blocking-fetch-result cmd-result id)]
    (is (= {:type :flashcard/rated
            :aggregate-id id
            :body rating}
           (dissoc rated-event :stream-version)))
    (is (= flashcard-init (dissoc fc-rated :due-date ::muga/document-type :stream-version)))
    (is (pos-int? (compare (:due-date fc-rated) (LocalDateTime/now))))

    (is (= [created-event rated-event] (db/fetch-event-history (xt/db @db/node) id)))))

(deftest invalid-version-test
  (let [create-cmd (sut/get-command @flashcard-system :flashcard/create)
        rate-cmd (sut/get-command @flashcard-system :flashcard/rate)
        flashcard-init {:question "q?" :response "r" :id 1}
        v1 (create-cmd 1 nil flashcard-init)
        v2 (rate-cmd 1 v1 5)
        result (tu/blocking-fetch-result (rate-cmd 1 v1 3) 1)]
    (is (= v2 (get-in result [:error :details :actual])))
    (is (= v1 (get-in result [:error :details :expected])))))


(deftest view-all-test
  (let [create (sut/get-command @flashcard-system :flashcard/create)
        init-values (map (fn [id] {:question "q?" :response "r" :id id}) (range 10))
        results (map (fn [init-value]
                       (let [future-res (create (:id init-value) nil init-value)]
                         (tu/blocking-fetch-result future-res (:id init-value))))
                     init-values)
        all (views/all (last results) @flashcard-system)]
    (is (= init-values (sort-by :id (map #(dissoc % :stream-version) all))))))


(comment
  (require 'unilog.config)
  (unilog.config/start-logging! {:level :info
                                 :overrides {"xtdb.tx" :debug}}))