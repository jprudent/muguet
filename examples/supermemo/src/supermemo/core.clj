(ns supermemo.core
  (:require [muguet.utils :as mugu]
            [sm2-plus :as sm2]))

(def flashcard
  [:map
   (mugu/gen-collection-metadata "flashcard")
   [:question {:doc "Should provide a meaningful context where the learning material was encounter in reality (examples, situation, ...)"}
    [:string {:min 1}]]
   [:response {:doc "Some clue to learn the flashcard"}
    [:string {:min 1}]]
   [:learn-map [:and [:map
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

(defn pick-a-flashcard []
      (mugu/gen-aggregate flashcard))

(defn read-flashcard [flashcard]
      (println "Question:")
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

(defn update-learn-map [flashcard rating]
      (update flashcard :learn-map #(sm2/calculate rating %)))

(defn -main
      [& _args]
      (while true
             (let [flashcard (pick-a-flashcard)]
                  (read-flashcard flashcard)
                  (let [rating (capture-user-rating)]
                       (update-learn-map flashcard rating)))))
