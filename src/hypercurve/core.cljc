(ns hypercurve.core
  "User-facing API: r/defn and the reactive forms."
  (:refer-clojure :exclude [defn for binding])
  (:require [hypercurve.optimistic]
            [hypercurve.router]
            [hypercurve.runtime :as rt]
            #?(:clj [hypercurve.compiler :as c]))
  #?(:cljs (:require-macros [hypercurve.core])))

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
         `(do (def ~(vary-meta name assoc ::reactive true :doc doc)
                ~(c/compile-defn &env qname params body {:site site}))
              (rt/register-ctor! ~name)
              (var ~name))))

     (clojure.core/defn- only-in-reactive [form]
       (throw (ex-info (str "hypercurve: " (first form) " is only valid inside r/defn") {:form form})))

     (defmacro server "Run body on the server." [& body] (only-in-reactive &form))
     (defmacro client "Run body on the client." [& body] (only-in-reactive &form))
     (defmacro for "(r/for [x coll :by key-fn] body) keyed reactive iteration." [& body] (only-in-reactive &form))
     (defmacro watch "Current value of a reference, tracked." [& body] (only-in-reactive &form))
     (defmacro call "(r/call F args...) call a reactive fn held in a value." [& body] (only-in-reactive &form))
     (defmacro binding "(r/binding [*v* expr ...] body) dynamic scope that follows reactive calls across sites." [& body] (only-in-reactive &form))
     (defmacro effect "(r/effect body) side effect re-run when its inputs change; a returned fn is its cleanup." [& body] (only-in-reactive &form))
     (defmacro flow "(r/flow (fn [emit!] ... cleanup)) a value pushed by an external source." [& body] (only-in-reactive &form))
     (defmacro offload "(r/offload body) run blocking server code off the session's turn; pending until done." [& body] (only-in-reactive &form))
     (defmacro mutation "(r/mutation f) a client handler calling server fn f with the event's value." [& body] (only-in-reactive &form))
     (defmacro boundary "(r/boundary (fn [err retry] fallback) body) show fallback when body fails." [& body] (only-in-reactive &form))
     (defmacro suspense "(r/suspense fallback body) show fallback while body has pending values." [& body] (only-in-reactive &form))
     (defmacro -with-env [& body] (only-in-reactive &form))
     (defmacro projection
       "(r/projection server-value) a client projection of a server value for
       optimistic updates; read it with r/watch, target it with
       (r/mutation f {:optimistic [projection transform]})."
       [& body] (only-in-reactive &form))
     (defmacro static
       "(r/static expr) evaluate expr once at build time on the JVM (read a
       file, render Markdown); the value is a constant in both builds."
       [& body] (only-in-reactive &form))
     (defmacro foreign
       "(r/foreign mount props) give a DOM element to a JS component: (mount el
       props) returns {:update (fn [props]) :unmount (fn [])}; props are reactive.
       Diff-aware components return :patch (fn [props delta]) instead of
       :update and receive what changed (hypercurve.delta form)."
       [& body] (only-in-reactive &form))
     (defmacro declassify
       "(r/declassify expr \"reason\") let a secret value reach the client.
       The reason is recorded in the boundary report."
       [x _reason] x)
     (defmacro route "(r/route routes) the current route, matched on the client." [& body] (only-in-reactive &form))
     (defmacro defer
       "(r/defer {:when :idle|:interaction|:visible :placeholder hiccup :margin px} body)
       :visible mounts body when the placeholder scrolls into view (:margin px
       earlier); give the placeholder a height.
       mount body later. A deferred subtree is also a natural code-split point."
       [& body] (only-in-reactive &form))
     (defmacro shared
       "(r/shared key body...) a server value computed once per process for
       each distinct key (and captured values) and followed by every session."
       [& body] (only-in-reactive &form))))

(clojure.core/defn event-value
  "The value of an input event's target, on either platform."
  [e]
  #?(:cljs (if (map? e) (:value e) (.. e -target -value))
     :clj (or (:value e) (some-> (:target e) :props deref (get "value")))))

(clojure.core/defn event-scroll-top
  "scrollTop of a scroll event's target, on either platform."
  [e]
  #?(:cljs (if (map? e) (:scroll-top e) (.. e -target -scrollTop))
     :clj (:scroll-top e)))

(clojure.core/defn event-key
  "The key of a keyboard event, on either platform."
  [e]
  #?(:cljs (if (map? e) (:key e) (.-key e)) :clj (:key e)))

(clojure.core/defn event-checked
  "The checked state of a checkbox event's target, on either platform."
  [e]
  #?(:cljs (if (map? e) (:checked e) (.. e -target -checked))
     :clj (boolean (or (:checked e) (some-> (:target e) :props deref (get "checked"))))))

(clojure.core/defn -mutate
  "Call server fn g; a single DOM event argument is replaced by its value.
  opts {:optimistic [projection f]} applies f to the projection until the
  server answers (then the server's value takes over, or it rolls back)."
  ([g args] (-mutate g args nil))
  ([g args {:keys [optimistic]}]
   (let [[a & more] args
         event? #?(:cljs (and (some? a) (instance? js/Event a))
                   :clj (and (map? a) (contains? a :target)))
         args (if (and event? (empty? more)) [(event-value a)] args)
         [p f] optimistic
         id (when p (hypercurve.optimistic/push! p f))
         result (apply g args)]
     (when id
       (let [done (fn [] (hypercurve.optimistic/drop! p id))]
         (if (rt/pending? @result)
           (add-watch result ::optimistic (fn [_ r _ v] (when-not (rt/pending? v) (remove-watch r ::optimistic) (done))))
           (done))))
     result)))

(def pending rt/pending)
(def pending? rt/pending?)
(def failure? rt/failure?)
