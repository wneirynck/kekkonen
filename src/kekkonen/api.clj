(ns kekkonen.api
  (:require [kekkonen.ring :as r]
            [kekkonen.core :as k]
            [kekkonen.middleware :as mw]
            [ring.util.http-response :refer [ok]]
            [kekkonen.swagger :as ks]
            [schema.core :as s]
            [kekkonen.common :as kc]))

(s/defschema Options
  {:core k/KeywordMap
   (s/optional-key :ring) k/KeywordMap
   (s/optional-key :mw) k/KeywordMap
   (s/optional-key :info) k/KeywordMap
   (s/optional-key :swagger) k/KeywordMap})

(s/def +default-options+ :- Options
  {:core {}, :ring {}, :info {}, :swagger {}})

(s/defn api [options :- Options]
  (s/with-fn-validation
    (let [options (kc/deep-merge +default-options+ options)
          kekkonen (k/create (:core options))
          info (merge (:info options) (mw/api-info (:mw options)))
          swagger-object (ks/swagger-object
                           (ks/swagger kekkonen info)
                           (:swagger options))
          ring-handler (r/ring-handler
                         kekkonen
                         (:ring options))]
      (mw/api-middleware
        (r/routes
          [ring-handler
           (r/match "/swagger.json" (constantly (ok swagger-object)))])
        (:mw options)))))
