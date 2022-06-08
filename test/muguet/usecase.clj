(ns muguet.usecase
  "Contains a collection of schemas that are used to illustrate possibilities.")


;; -----------------------------------------------------------------------------
;; --------------------- POKEMON CARDS
;; This is a very simple schema of a geek who wants to manage his pokemon cards.
;; There is just a single aggregate-root with a bunch of values

(def pokemon-card
  [:map
   {:collection/kind :collection
    :collection/singular "pokemon-card",
    :collection/plural "pokemon-cards",
    :collection/display-name "A pokemon card",
    :example {:number 498
              :name "Gruiki"
              :element :fire
              :life-points 70
              :attacks [{:name "Flammèche"
                         :damage 30
                         :energies [[1 :fire] [1 :any]]}]}
    :registry {::elements [:enum :fire :water]}}

   [:number ['pos-int? {:doc "The number written on the card"
                        :muguet/unique true}]]

   [:name {:doc "Name of the pokemon as shown in the front of the card"
           :muguet/unique? true}
    [:string {:min 1}]]

   [:element [:ref ::elements]]

   [:life-points 'pos-int?]

   [:attacks
    [:sequential

     [:map
      [:name
       {:doc "Name of the attack"}
       [:string {:min 1}]]

      [:damage
       {:doc "Damages caused to enemy by this attack"}
       'pos-int?]

      [:energies
       {:doc "Energies spent for this attack"
        :example [[1 :fire] [2 :any]]}
       [:sequential [:catn
                     [:number-of-energy 'pos-int?]
                     [:enery-type [:or [:enum :any] ::elements]]]]]]]]])