(ns muguet.commands-test
  "Those tests are using the real implementation of database through an in-memory instance of XTDB"
  (:require [clojure.test :refer :all]
            [muguet.api :as mug]
            [muguet.commands :as sut]
            [muguet.db :as db]
            [muguet.main :as main]
            [muguet.usecase :as uc]
            [xtdb.api :as xt]))

(def some-id "some fake id")
(def id-provider (constantly some-id))

(def pokemon-system {:schema uc/pokemon-card
                     :aggregate-name :pokemon-card
                     :id-provider id-provider})

(defn blocking-fetch
  "This implementation should never be used in production because it's spinning"
  [version id]
  (future
    (loop []
      (let [r (sut/fetch-command-result version id)]
        (if (= ::mug/pending (::mug/command-status r))
          (recur)
          r)))))

(use-fixtures :each main/start!)

(deftest hatch-test
  (testing "hatching an empty pokemon card"
    (let [cmd-result (sut/hatch nil pokemon-system)]
      (is (= ::mug/pending (::mug/command-status cmd-result)))
      (let [{:keys [event on-aggregate aggregate]} (deref (blocking-fetch @(:version cmd-result) some-id) 100 nil)
            aggregate-in-db (db/fetch-aggregate (xt/db @db/node) some-id)
            aggregate-stream-version (:stream-version aggregate)
            expected-aggregate {:id some-id :stream-version aggregate-stream-version}
            aggregate-at-version (db/fetch-aggregate-version aggregate-stream-version some-id)

            event-history-in-db (db/fetch-event-history (xt/db @db/node) some-id)
            event-stream-version (:stream-version event)
            expected-event {:type :pokemon-card/hatched
                            :version 1
                            :aggregate-id some-id
                            ;; in that case the body of the event is the aggregate,
                            ;; but in other circumstances it should be something different
                            :body (dissoc expected-aggregate :stream-version)
                            :stream-version event-stream-version}
            event-at-version (db/fetch-last-event-version event-stream-version some-id)]

        (is (= on-aggregate nil) "the aggregate is applied upon a nil (non existant value)")

        (is (= expected-event event event-at-version (first event-history-in-db))
            "Event is returned and durably persisted")
        (is (= 1 (count event-history-in-db)))

        (is (= expected-aggregate aggregate aggregate-in-db aggregate-at-version)
            "Aggregate is returned and durably persisted")

        (is (some? event-stream-version))
        (is (= event-stream-version aggregate-stream-version)
            "Aggregate and event has same stream version"))))

  (testing "concurrent hatching"))

(deftest hatch-bad-values-test
  (testing "hatching when initial values doesn't match schema"
    (let [cmd-result (sut/hatch {:number "pika!"} pokemon-system)]
      (is (= {:error {:details {:number ["should be a positive int"]}
                      :message "Invalid attributes"
                      :status :muguet.api/invalid}
              :muguet.api/command-status :muguet.api/complete} cmd-result)))))

(deftest hatch-init-vals-test
  (testing "hatching a pokemon card with initial values"
    (let [initial-value {:number 121}
          cmd-result (sut/hatch initial-value pokemon-system)]
      (is (= ::mug/pending (::mug/command-status cmd-result)))
      (let [{:keys [aggregate event] :as x} (deref (blocking-fetch @(:version cmd-result) some-id) 100 nil)
            expected-aggregate (assoc initial-value :id some-id
                                                    :stream-version (:stream-version aggregate))]
        #_(sc.api/brk)
        (is (= event {:type :pokemon-card/hatched
                      :version 1
                      :aggregate-id some-id
                      :stream-version (:stream-version event)
                      :body (dissoc expected-aggregate :stream-version)}))
        (is (= aggregate expected-aggregate))))))

(deftest hatch-id-exists-test
  (testing "hatching when id already exists"
    (let [first-pending-result (sut/hatch nil pokemon-system)
          _ (is (= ::mug/pending (::mug/command-status first-pending-result)))
          second-pending-result (sut/hatch {:number 12} pokemon-system)
          _ (is (= ::mug/pending (::mug/command-status second-pending-result)))
          second-result (deref (blocking-fetch @(:version second-pending-result) some-id) 500 :couic!)]
      (is (= {:error {:details {:actual {:id "some fake id"}
                                :expected nil}
                      :message "the specified aggregate version couldn't be find"
                      :status :muguet.api/not-found}
              :muguet.api/command-status :muguet.api/complete}
             (-> second-result
                 (update-in [:error :details :actual] dissoc :stream-version)
                 (update :error dissoc :stream-version)))))))

;; todo atomicity of several events by a single command
;; todo test async
;; todo activate instrumentation of schemas
