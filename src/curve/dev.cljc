(ns curve.dev
  "Development tooling: inspection, \"why did this update\", diagnostics and
  the shadow-cljs reload hook. Nothing here belongs in a production bundle;
  tracing costs one volatile read per change when off."
  (:require [curve.runtime :as rt]))

;; ------------------------------------------------------------------ inspect

(defn- summary [v]
  (cond (rt/pending? v) :pending
        (rt/failure? v) {:error (str (:error v))}
        (fn? v) (if (rt/remote-fn? v) :remote-fn :fn)
        (and (coll? v) (> (count v) 10)) {:count (count v) :type (str (type v))}
        :else v))

(defn inspect-frame [f]
  {:ctor (:name (:ctor f)) :site (:site f) :key (:key f) :rendered @(:rendered f)
   :nodes (vec (map-indexed (fn [i nd] (cond-> {:i i :op (:op nd) :sid (:sid nd) :value (summary (rt/value f i))}
                                         (:site nd) (assoc :site (rt/node-site f nd))
                                         (aget ^objects (:exported f) i) (assoc :exported true)))
                            (:nodes (:ctor f))))
   :children (vec (for [[i kids] (sort-by key @(:children f))
                        c (rt/kids-seq kids)]
                    (assoc (inspect-frame c) :at i)))})

(defn inspect
  "The live frame tree of a peer as data (browse it with Portal, Reveal, tap>)."
  [peer]
  (when-let [root (first (filter #(nil? (:parent %)) (rt/frames peer)))]
    (inspect-frame root)))

;; ------------------------------------------------------------------ why

(defn trace!
  "Start recording changes and their causes."
  []
  (vreset! rt/trace (volatile! {:log [] :causes {}})))

(defn untrace! [] (vreset! rt/trace nil))

(defn log "Recorded changes, oldest first: {:cell [frame node] :ctor :value}." []
  (some-> @rt/trace deref :log))

(defn why
  "Why does cell [frame-seq-id node] have its value? The chain of changes
  that led to it, nearest first, ending at its source (a watched reference,
  a value from the other peer, or a mount)."
  ([f i] (why [(:seq-id f) i]))
  ([cell]
   (let [causes (some-> @rt/trace deref :causes)]
     (loop [c cell acc [] seen #{}]
       (let [nxt (get causes c)]
         (if (or (nil? nxt) (contains? seen nxt) (> (count acc) 100))
           (conj acc c)
           (recur nxt (conj acc c) (conj seen c))))))))

;; ------------------------------------------------------------------ diagnostics

#?(:clj
   (defn diagnostics
     "Editor/CI data: network crossings and arguments that reach the client."
     []
     {:boundary ((requiring-resolve 'curve.compiler/boundary-report))
      :args-reaching-client @@(requiring-resolve 'curve.compiler/reach)}))

;; ------------------------------------------------------------------ shadow hook

#?(:clj
   (defn reload-clj
     "shadow-cljs build hook: after a compile, reload on the JVM the .cljc
     namespaces that were just recompiled, so the server runs the same program
     tables as the browser. Use with curve.client/reload! as :after-load.
       :build-hooks [(curve.dev/reload-clj)]"
     {:shadow.build/stage :compile-finish}
     [build-state]
     (let [compiled (get-in build-state [:shadow.build/build-info :compiled])
           nses (for [rid compiled
                      :let [{:keys [ns resource-name]} (get-in build-state [:sources rid])]
                      :when (and ns resource-name (.endsWith ^String resource-name ".cljc"))]
                  ns)]
       (doseq [ns nses]
         (try (require ns :reload)
              (catch Throwable e
                (println "curve.dev/reload-clj:" ns (ex-message e)))))
       build-state)))

;; ------------------------------------------------------------------ wire, replay, tests
;; These work on message logs: curve.test/wire-log, or a session recording
;; (record-session!). Messages are plain data (a monoid), so a log can be
;; measured, replayed to any point and turned into a regression test.

#?(:clj
   (defn wire-stats
     "Per [direction node]: how many values crossed and roughly how many bytes
     (each value encoded on its own)."
     [log]
     (let [codec-blob (requiring-resolve 'curve.codec/encode-delta-blob)]
       (->> (for [{:keys [dir msg]} log
                  [_ i d] (:vals msg)]
              [[dir i] (alength ^bytes (codec-blob d))])
            (reduce (fn [m [k n]] (-> m (update-in [k :count] (fnil inc 0)) (update-in [k :bytes] (fnil + 0) n))) {})
            (sort-by (comp - :bytes val))
            (into [])))))

#?(:clj
   (defn replay
     "Time travel: a fresh client of ctor, on a headless DOM, fed the first n
     server-to-client messages of log. Returns {:html :peer}."
     ([ctor log] (replay ctor log Long/MAX_VALUE))
     ([ctor log n]
      (let [h (requiring-resolve 'curve.headless/root)
            dom ((requiring-resolve 'curve.headless/dom))
            html (requiring-resolve 'curve.headless/html)
            renderer (requiring-resolve 'curve.mount/renderer)
            root (h)
            peer (apply rt/peer :client (mapcat identity (dissoc (renderer dom root) :mounter)))]
        (rt/mount-root! peer ctor)
        (rt/run! peer)
        (doseq [{:keys [msg]} (take n (filter #(= :s->c (:dir %)) log))]
          (rt/receive! peer msg)
          (rt/run! peer))
        {:html (html root) :peer peer}))))

#?(:clj
   (defn log->test
     "A regression test (source) that replays log and checks the final page."
     [test-name ctor-sym log]
     (let [ctor @(requiring-resolve ctor-sym)
           expected (:html (replay ctor log))
           msgs (vec (map :msg (filter #(= :s->c (:dir %)) log)))]
       (with-out-str
         ((requiring-resolve 'clojure.pprint/pprint)
          `(clojure.test/deftest ~test-name
             (clojure.test/is (= ~expected
                                 (:html (curve.dev/replay ~ctor-sym (mapv (fn [m#] {:dir :s->c :msg m#}) '~msgs)))))))))))
