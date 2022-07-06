(ns muguet.uc-flashcard-test
  "Those tests are using the real implementation of database through an in-memory instance of XTDB"
  (:require [clojure.test :refer :all]
   ;; todo should only import public apis
            [clojure.tools.logging :as log]
            [malli.util :as mu]
            [muguet.api :as muga]
            [muguet.core :as mug]
            [muguet.internals.commands :as sut]
            [muguet.test-utils :as tu]
            [muguet.utils :as mugu])
  (:import (java.time LocalDateTime)))


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

(def flashcard-system
  {:schema flashcard-schema
   :aggregate-name :flashcard
   :id-provider :id
   :event-registry (-> (group-by :type [(sut/assoc-event-builder
                                         {:type :flashcard/rated
                                          :body-schema rating-schema
                                          :event-handler `apply-rated-event})
                                        (sut/assoc-event-builder
                                         {:type :flashcard/created
                                          :body-schema flashcard-init-schema
                                          :event-handler `apply-created-event})])
                       (update-vals first))})

;; |=-----------------------------------------------------------------------=|
;; |=--------------------------=[ Flashcard Tests ]=------------------------=|
;; |=-----------------------------------------------------------------------=|

(use-fixtures :each (fn [f]
                      (log/info "-=[ Initialize Muguet ]=-")
                      (mug/start! [flashcard-system])
                      (log/info "-=[ Strarting Test ]=-")
                      (f)))

(deftest my-command-test
  (let [id 1
        create-cmd (sut/register-command
                    flashcard-system
                    :flashcard/create
                    [(sut/validate-command-params (mu/optional-keys flashcard-schema [:due-date]))
                     (sut/build-event :flashcard/created :command-params)])
        flashcard-init {:question "q?" :response "r" :id id}
        version (create-cmd id nil flashcard-init)
        create-result (tu/blocking-fetch-result version id)
        _ (is (= :muguet.api/complete (:muguet.api/command-status create-result)))
        _ (is (contains? create-result :aggregate))
        _ (is (contains? create-result :event))
        rate-cmd (sut/register-command
                  flashcard-system
                  :flashcard/rate
                  [(sut/validate-command-params rating-schema)
                   (sut/build-event :flashcard/rated :command-params)])
        rating 4
        ;; todo find api trick so we don't have to get/deref the version
        cmd-result (rate-cmd id version rating)
        {:keys [aggregate event]} (tu/blocking-fetch-result cmd-result id)]
    (is (= {:type :flashcard/rated
            :aggregate-id id
            :body rating}
           (dissoc event :stream-version)))
    (is (= flashcard-init (dissoc aggregate :due-date ::muga/document-type :stream-version)))
    (is (pos-int? (compare (:due-date aggregate) (LocalDateTime/now))))))

;; todo invalid command args

(comment
 (require 'unilog.config)
 (unilog.config/start-logging! {:level :info
                                :overrides {"xtdb.tx" :debug}}))