(ns kekkonen.swagger-test
  (:require [kekkonen.core :as k]
            [kekkonen.swagger :as ks]
            [midje.sweet :refer :all]
            [schema.core :as s]
            [ring.util.http-response :refer [ok]]
            [plumbing.core :as p]
            [kekkonen.ring :as r]))

(p/defnk ^:handler echo
  {:summary "summary"
   :responses {200 {:schema {:x [s/Str]
                             :y s/Int
                             :z s/Bool}}}}
  [[:request
    body-params :- {:country (s/enum :FI :CA)}
    [:query-params x :- [s/Str]]
    [:path-params y :- s/Int]
    [:header-params z :- s/Bool]]]
  (ok [x y z body-params]))


(fact "swagger-docs"
  (let [kekkonen (k/create {:handlers {:api {:admin #'echo}}})
        swagger (ks/swagger
                  kekkonen
                  {:info {:version "1.0.0"
                          :title "Kekkonen"
                          :description "Kekkonen Swagger API"}}
                  r/+default-options+)]

    (fact "swagger-object is created"

      swagger => {:info {:version "1.0.0"
                         :title "Kekkonen"
                         :description "Kekkonen Swagger API"}
                  :paths {"/api/admin/echo"
                          {:post
                           {:parameters {:body {:country (s/enum :CA :FI)}
                                         :header {:z s/Bool, s/Keyword s/Any}
                                         :path {:y s/Int, s/Keyword s/Any}
                                         :query {:x [s/Str], s/Keyword s/Any}}
                            :responses {200 {:schema {:x [s/Str]
                                                      :y s/Int
                                                      :z s/Bool}}}
                            :summary "summary"
                            :tags [:api.admin]}}}})

    (fact "swagger-json can be generated"
      (s/with-fn-validation
        (ks/swagger-object swagger {}) => truthy))))
