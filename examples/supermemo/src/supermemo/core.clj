(ns supermemo.core
  (:require [muguet.core :as mug]
            [muguet.internals.db :as db]
            [muguet.utils :as mugu]
            [sm2-plus :as sm2]
            [xtdb.api :as xt]))

(def flashcard
  [:map
   (mugu/->collection-metadata "flashcard")
   [:question {:doc "Should provide a meaningful context where the learning material was encounter in reality (examples, situation, ...)"}
    [:string {:min 1}]]
   [:response {:doc "Some clue to learn the flashcard"}
    [:string {:min 1}]]
   [:learn-map
    {:optional true}
    [:and [:map
           [:difficulty {:doc "How difficult the item is to learn"} [:double {:min 0 :max 1}]]
           [:interval {:doc "how many days before next play"} [:int {:min 0}]]
           [:percent-overdue {:doc "criticality to learn the flashcard"} [:double {:min 0 :max 2}]]
           [:updated {:doc "when this flashcard have been played"} 'inst?]
           [:due-date {:doc "when should it be played"} 'inst?]]
     [:fn {:error/message "The due date cannot be in the past"}
      '(fn [{:keys [updated due-date]}]
           (and updated due-date
                (not= 1 (compare updated due-date))))]]]])

(prn (or (mugu/check-schema flashcard) "no problem with schema"))

(def systems [{:schema flashcard
               :id-provider (fn [_x] (random-uuid))
               :aggregate-name :flashcard}])

(defprotocol
 FlashcardService
 (draw [this version])
 (rate [this flashcard rating]))

(defrecord FlashcardServiceStub []
           FlashcardService
           (draw [_this _version]
                 (mugu/gen-aggregate flashcard))
           (rate [_this flashcard rating]
                 (update flashcard :learn-map #(sm2/calculate rating %))))

(def max-overdue-query
  '{:find [(pull p [*])]
    :where [[(q '{:find [(max (get learn-map :percent-overdue))]
                  :where [[p :learn-map learn-map]]}) [[max-po]]]
            [p :learn-map learn-map]
            [(get learn-map :percent-overdue) po]
            [(= po max-po)]]
    :limit 1})

(defrecord FlashcardServiceXtdb [node]
           FlashcardService
           (draw [_this stream-version]
                 ;; todo ensure previous rating has been indexed before running the query
                 (let [future-response (mug/find-one max-overdue-query stream-version)
                       flashcard (deref future-response 1000 nil)]
                      (if flashcard
                        flashcard
                        (do (future-cancel future-response)
                            (throw (ex-info "Can't find flashcard in reasonable time" {:stream-version stream-version}))))))
           (rate [_this flashcard rating]
                 (xt/submit-tx node [[::xt/put (update flashcard :learn-map #(sm2/calculate rating %))]])))

(defn print-question [flashcard]
      (println (str (pr-str flashcard) "Question:"))
      (println (:question flashcard)))

(defn capture-user-rating []
      (let [rating (atom nil)]
           (while (nil? @rating)
                  (do
                    (println "0 (don't have a clue) 1 2 3 (correct but very hard) 4 5 (correct and very easy)")
                    (flush)
                    (let [user-input (read-line)]
                         (if (re-matches #"[0-5]" (or user-input ""))
                           (do (reset! rating (Integer/parseInt user-input)))
                           (println (str "invalid input: " user-input))))))
           @rating))

(defn main [svc]
      (loop [flashcard (draw svc)]
            (when flashcard
                  (print-question flashcard)
                  (let [rating (capture-user-rating)
                        aggregate-version (rate svc flashcard rating)]
                       ;; todo the db should process the transaction before draw again, because same flashcard will be drawn again
                       (recur (draw svc aggregate-version))))))

(defn -main [& _args]
      (main (->FlashcardServiceXtdb @db/node)))


(comment
 (do
   (mug/start! systems)
   (doseq [sample (repeatedly 10 #(mugu/gen-aggregate flashcard))]
          (mug/hatch sample (first systems)))))
