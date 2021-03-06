(ns kekkonen.ring
  (:require [schema.core :as s]
            [ring.swagger.coerce :as rsc]
            [kekkonen.core :as k]
            [kekkonen.common :as kc]
            [clojure.string :as str]
            [ring.swagger.json-schema :as rsjs]
            [ring.util.http-response :refer [ok]]
            [plumbing.core :as p]
            [plumbing.map :as pm])
  (:import [kekkonen.core Dispatcher]))

(def ^:private mode-parameter "kekkonen.mode")

(s/defschema Options
  {:types {s/Keyword {:methods #{s/Keyword}
                      (s/optional-key :parameters) {[s/Keyword] [s/Keyword]}
                      (s/optional-key :allow-method-override?) s/Bool
                      (s/optional-key :interceptors) [k/FunctionOrInterceptor]}}
   :coercion {s/Keyword k/Function}
   :interceptors [k/FunctionOrInterceptor]})

(s/def +default-options+ :- Options
  ; TODO: no types in default bindings?
  ; TODO: add type-resolver?
  {:types {::handler {:methods #{:post}
                      :allow-method-override? true
                      :parameters {[:data] [:request :body-params]}}
           :handler {:methods #{:post}
                     :parameters {[:data] [:request :body-params]}}}
   :coercion {:query-params rsc/query-schema-coercion-matcher
              :path-params rsc/query-schema-coercion-matcher
              :form-params rsc/query-schema-coercion-matcher
              :header-params rsc/query-schema-coercion-matcher
              :body-params rsc/json-schema-coercion-matcher}
   :interceptors []})

(def +ring-meta+
  {::disable-mode nil
   ::method nil})

;;
;; Internals
;;

(defn- uri->action [path]
  (let [i (.lastIndexOf path "/")]
    (if-not (= (count path) 1)
      (keyword (subs (str (str/replace (subs path 0 i) #"/" ".") (subs path i)) 1)))))

(defn- handler-uri [handler]
  (str
    (if-let [ns (some-> handler :ns name)]
      (str "/" (str/replace ns #"\." "/")))
    "/" (name (:name handler))))

(defn- ring-coercion [parameters coercion]
  (let [coercions (pm/unflatten
                    (for [[k matcher] coercion
                          :when matcher]
                      [[:request k] (fn [schema value]
                                      (k/coerce! schema matcher (or value {}) k ::request))]))]
    (k/multi-coercion
      (if parameters
        (reduce kc/copy-to-from coercions parameters)
        coercions))))

(defn- coerce-response! [response handler options]
  (if-let [responses (-> handler :meta :responses)]
    (let [status (or (:status response) 200)
          schema (get-in responses [status :schema])
          matcher (get-in options [:coercion :body-params])
          value (:body response)]
      (if schema
        (let [coerced (k/coerce! schema matcher value :response ::response)]
          (assoc response :body coerced))
        response))
    response))

(defn- ring-input-schema [input parameters]
  (if parameters
    (reduce kc/move-from-to input parameters)
    input))

(defn- attach-mode-parameter [schema]
  (let [key (s/optional-key mode-parameter)
        value (rsjs/describe (s/enum "invoke" "validate") "mode" :default "invoke")
        extra-keys-schema (s/find-extra-keys-schema (get-in schema [:request :header-params]))]
    (update-in schema [:request :header-params] merge {key value} (if-not extra-keys-schema {s/Any s/Any}))))

(defn- is-validate-request? [request]
  (= (get-in request [:headers mode-parameter]) "validate"))

(defn- attach-ring-meta [options handler]
  (let [{:keys [parameters allow-method-override?] :as type-config} (get (:types options) (:type handler))
        coercion (:coercion options)
        method (some-> handler :meta ::method)
        methods (if (and allow-method-override? method)
                  (conj #{} method)
                  (:methods type-config))
        input-schema (-> (:input handler)
                         (ring-input-schema parameters)
                         (cond-> (not (get-in handler [:meta ::disable-mode])) attach-mode-parameter))]
    (assoc handler :ring {:type-config type-config
                          :methods methods
                          :coercion (ring-coercion parameters coercion)
                          :uri (handler-uri handler)
                          :input input-schema})))

;;
;; Ring-handler
;;

(s/defn ring-handler
  "Creates a ring handler from Dispatcher and options."
  ([dispatcher :- Dispatcher]
    (ring-handler dispatcher {}))
  ([dispatcher :- Dispatcher, options :- k/KeywordMap]
    (let [options (-> (kc/deep-merge +default-options+ options)
                      (update :interceptors (partial mapv k/interceptor))
                      (update :types (fn [types]
                                       (p/for-map [[k v] types]
                                         k (if (:interceptors v)
                                             (update v :interceptors (partial mapv k/interceptor))
                                             v)))))
          dispatcher (k/transform-handlers dispatcher (partial attach-ring-meta options))
          router (p/for-map [handler (k/all-handlers dispatcher nil)] (-> handler :ring :uri) handler)]
      (fn [{:keys [request-method uri] :as request}]
        ;; match a handlers based on uri
        (if-let [{{:keys [type-config methods coercion] :as ring} :ring action :action :as handler} (router uri)]
          ;; only allow calls to ring-mapped handlers with matching method
          (if (and ring (methods request-method))
            ;; TODO: create an interceptor chain
            (let [context (as-> {:request request} context

                                ;; base-context from Dispatcher
                                (kc/deep-merge (:context dispatcher) context)

                                ;; add lazy-coercion
                                (assoc context ::k/coercion coercion)

                                ;; map parameters from ring-request into common keys
                                (reduce kc/deep-merge-to-from context (:parameters type-config))

                                ;; global interceptors enter
                                (reduce
                                  (fn [ctx {:keys [enter]}]
                                    (if enter (or (enter ctx) (reduced nil)) ctx))
                                  context
                                  (:interceptors options))

                                ;; type-level interceptors enter
                                (reduce
                                  (fn [ctx {:keys [enter]}]
                                    (if enter (or (enter ctx) (reduced nil)) ctx))
                                  context
                                  (reverse (:interceptors type-config))))

                  response (if (is-validate-request? request)
                             (ok (k/validate dispatcher action context))
                             (let [response (k/invoke dispatcher action context)]
                               (coerce-response! response handler options)))

                  context (as-> (assoc context :response response) context

                                ;; type-level interceptor leave
                                (reduce
                                  (fn [ctx {:keys [leave]}]
                                    (if leave (or (leave ctx) (reduced nil)) ctx))
                                  context
                                  (:interceptors type-config))


                                ;; global interceptors leave
                                (reduce
                                  (fn [ctx {:keys [leave]}]
                                    (if leave (or (leave ctx) (reduced nil)) ctx))
                                  context
                                  (reverse (:interceptors options))))]

              (:response context))))))))

;;
;; Routing
;;

(s/defn routes :- k/Function
  "Creates a ring handler of multiples handlers, matches in order."
  [ring-handlers :- [k/Function]]
  (apply some-fn ring-handlers))

(s/defn match
  "Creates a ring-handler for given uri & request-method"
  ([match-uri ring-handler]
    (match match-uri identity ring-handler))
  ([match-uri match-request-method ring-handler]
    (fn [{:keys [uri request-method] :as request}]
      (if (and (= match-uri uri)
               (match-request-method request-method))
        (ring-handler request)))))

;;
;; Special handlers
;;

(defn- clean-context [context]
  (-> context
      (kc/dissoc-in [:request :query-params :kekkonen.action])
      (kc/dissoc-in [:request :query-params :kekkonen.mode])
      (kc/dissoc-in [:request :query-params :kekkonen.ns])))

(def +kekkonen-handlers+
  {:kekkonen
   [(k/handler
      {:name "handler"
       :type ::handler
       ::disable-mode true
       ::method :get
       :input {:request
               {:query-params
                {(s/optional-key :kekkonen.action) s/Keyword
                 s/Keyword s/Any}
                s/Keyword s/Any}
               s/Keyword s/Any}
       :description "Returns a handler info or nil."}
      (fn [{{{action :kekkonen.action} :query-params} :request :as context}]
        (ok (k/public-handler
              (k/some-handler
                (k/get-dispatcher context)
                action)))))
    (k/handler
      {:name "handlers"
       :type ::handler
       ::disable-mode true
       ::method :get
       :input {:request
               {:query-params
                {(s/optional-key :kekkonen.ns) s/Keyword
                 s/Keyword s/Any}
                s/Keyword s/Any}
               s/Keyword s/Any}
       :description "Return a list of available handlers from kekkonen.ns namespace"}
      (fn [{{{ns :kekkonen.ns} :query-params} :request :as context}]
        (ok (->> context
                 k/get-dispatcher
                 (p/<- (k/available-handlers ns (clean-context context)))
                 (filter (p/fn-> :ring))
                 (remove (p/fn-> :ns (= :kekkonen)))
                 (remove (p/fn-> :meta :no-doc))
                 (map k/public-handler)))))
    (k/handler
      {:name "actions"
       :type ::handler
       ::disable-mode true
       ::method :post
       :input {:request
               {:body-params {s/Keyword s/Any}
                :query-params
                {(s/optional-key :kekkonen.ns) s/Keyword
                 (s/optional-key :kekkonen.mode) (with-meta
                                                   (s/enum :check :validate)
                                                   {:json-schema {:default :check}})
                 s/Keyword s/Any}
                s/Keyword s/Any}
               s/Keyword s/Any}
       :description "Return a map of action -> error of all available handlers"}
      (fn [{{{mode :kekkonen.mode ns, :kekkonen.ns} :query-params} :request :as context}]
        (ok (->> context
                 k/get-dispatcher
                 (p/<- (k/dispatch-handlers (or mode :check) ns (clean-context context)))
                 (filter (p/fn-> first :ring))
                 (remove (p/fn-> first :ns (= :kekkonen)))
                 (remove (p/fn-> first :meta :no-doc))
                 (map (fn [[k v]] [(:action k) (k/stringify-schema v)]))
                 (into {})))))]})
