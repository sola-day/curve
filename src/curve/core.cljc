(ns curve.core
  "User-facing API: r/defn and the reactive forms."
  (:refer-clojure :exclude [defn for binding])
  (:require [curve.runtime :as rt]
            #?(:clj [curve.compiler :as c]))
  #?(:cljs (:require-macros [curve.core])))

#?(:clj
   (do
     (defmacro defn
       "Define a reactive function. Its body is reactive Clojure: every
       expression recomputes when its inputs change. Mark the name ^:server or
       ^:client to give unannotated code a default site; otherwise it runs on
       the caller's site."
       [name & fdecl]
       (let [[doc fdecl] (if (string? (first fdecl)) [(first fdecl) (rest fdecl)] [nil fdecl])
             [params & body] fdecl
             ns-name (if (:ns &env) (:name (:ns &env)) (ns-name *ns*))
             qname (symbol (str ns-name) (str name))
             m (meta name)
             site (cond (:server m) :server (:client m) :client)]
         (swap! c/registry conj qname)
         `(def ~(vary-meta name assoc ::reactive true :doc doc)
            ~(c/compile-defn &env qname params body {:site site}))))

     (clojure.core/defn- only-in-reactive [form]
       (throw (ex-info (str "curve: " (first form) " is only valid inside r/defn") {:form form})))

     (defmacro server "Run body on the server." [& body] (only-in-reactive &form))
     (defmacro client "Run body on the client." [& body] (only-in-reactive &form))
     (defmacro for "(r/for [x coll :by key-fn] body) keyed reactive iteration." [& body] (only-in-reactive &form))
     (defmacro watch "Current value of a reference, tracked." [& body] (only-in-reactive &form))
     (defmacro call "(r/call F args...) call a reactive fn held in a value." [& body] (only-in-reactive &form))))

(clojure.core/defn event-value
  "The value of an input event's target, on either platform."
  [e]
  #?(:cljs (if (map? e) (:value e) (.. e -target -value))
     :clj (or (:value e) (some-> (:target e) :props deref (get "value")))))

(def pending rt/pending)
(def pending? rt/pending?)
(def failure? rt/failure?)
