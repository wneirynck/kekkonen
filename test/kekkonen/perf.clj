(ns kekkonen.perf
  (:require [criterium.core :as cc]
            [kekkonen.core :as k]
            [plumbing.core :as p]
            [kekkonen.cqrs :refer :all]
            [schema.core :as s]))

(defn title [s]
  (println (str "\u001B[35m" (apply str (repeat (count s) "#")) "\u001B[0m"))
  (println (str "\u001B[35m" s "\u001B[0m"))
  (println (str "\u001B[35m" (apply str (repeat (count s) "#")) "\u001B[0m")))

;;
;; handlers & dispatcher
;;

(p/defnk ^:handler plus1 :- {:body {:result s/Int}, s/Keyword s/Any}
  "adds numbers together"
  [[:data x :- s/Int, y :- s/Int]]
  (success {:result (+ x y)}))

(def d1 (k/dispatcher {:handlers {:api {:math #'plus1}}}))
(def d2 (k/dispatcher {:handlers {:api {:math #'plus1}}
                       :coercion {:input nil, :output nil}}))

;;
;; clojure multimethod
;;

(defmulti multi-method-invoke (fn [key _] key))
(defmethod multi-method-invoke :api.math/plus1 [_ data] (plus1 data))

;;
;; benchmarks
;;

(defn core-bench []

  (title "with coercion")
  (cc/quick-bench (k/invoke d1 :api.math/plus1 {:data {:x 10, :y 20}}))
  ; 28.0µs => 8.2µs (memoized) => 7.0µs (lookup)

  (title "without coercion")
  (cc/quick-bench (k/invoke d2 :api.math/plus1 {:data {:x 10, :y 20}}))
  ; 3.7µs -> 3.7µs (memoized) => 2.0µs (lookup)

  (title "clojure multimethod")
  (cc/quick-bench (multi-method-invoke :api.math/plus1 {:data {:x 10, :y 20}}))
  ; 0.3µs

  (println))

;;
;; ring-handlers
;;

(require '[kekkonen.ring :as kr])

(def r1 (kr/ring-handler d1))
(def r2 (kr/ring-handler d2))
(def r3 (kr/ring-handler d2 {:coercion nil}))

(defn ring-bench []

  (title "ring & dispatcher coercion")
  (cc/quick-bench (r1 {:uri "/api/math/plus1"
                       :request-method :post
                       :body-params {:x 10, :y 20}}))
  ; 20.7µs => 17.1µs => 11.3µs

  (title "ring coercion")
  (cc/quick-bench (r2 {:uri "/api/math/plus1"
                       :request-method :post
                       :body-params {:x 10, :y 20}}))
  ; 15.7µs => 12.2µs => 7.6µs

  (title "no coercion")
  (cc/quick-bench (r3 {:uri "/api/math/plus1"
                       :request-method :post
                       :body-params {:x 10, :y 20}}))
  ; ................ => 3.5µs

  (println))

(comment
  (core-bench)
  (ring-bench))
