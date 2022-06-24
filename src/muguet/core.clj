(ns muguet.core)

(def meta-schema [:meta {:optional true} map?])

(def object-schema
  [:map
   ;; the id is not mixed with attributes to enforce the segregation between identity and value
   [:id pos-int?]
   [:attributes map?]
   meta-schema])

;; inspired by https://docs.strapi.io/developer-docs/latest/developer-resources/database-apis-reference/rest-api.html#unified-response-format
(def response-schema
  [:map
   [:data
    [:orn
     [:single object-schema]
     [:list object-schema]]]
   meta-schema])

(def error-schema
  [:map
   ;; inspired by https://docs.strapi.io/developer-docs/latest/developer-resources/error-handling.html#rest-errors
   [:error [:map
            [:status [:enum [:invalid :conflict :not-exist :exception]]
             :message [:string {:min 1}]
             :details map?]]]
   meta-schema])

(def api-return-schema
  [:orn
   [:ko error-schema]
   [:ok response-schema]])


(defn find-one [id collection-system])

(defn destroy
  ";; There is no \"delete\" but a \"destroy\" to enforce the destructive outcome
  this operation. User can implement a non-destructive method of his own."
  [id collection-system])
