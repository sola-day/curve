(ns hypercurve.bench
  "Phase-0 benchmarks (design §18). Run: clojure -M:bench -m hypercurve.bench"
  (:require [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.runtime :as rt]
            [hypercurve.session :as session]
            [hypercurve.shared :as shared]
            [hypercurve.test :as ct]
            [hypercurve.virtual]))

(defn- now [] (System/nanoTime))

(defn- measure
  "Median ns of (f) over n runs after warmup."
  [n f]
  (dotimes [_ (max 5 (quot n 2))] (f))
  (let [xs (sort (for [_ (range n)] (let [t (now)] (f) (- (now) t))))]
    (nth xs (quot n 2))))

(defn- ms [ns] (format "%.3f ms" (/ ns 1e6)))

;; ---------------------------------------------------------------- A. interpreter overhead

(defn chain-ctor [!a n]
  (rt/ctor {:name 'chain :ret (inc n)
            :nodes (into [{:op :const :v !a}
                          {:op :watch :site :server :in [0]}]
                         (for [i (range n)] {:op :call :site :server :f inc :in [(inc i)]}))}))

(defn closure-chain
  "What a closure-emitting compiler would produce for the same graph: one
  specialised closure per node, same dirty-bit scheduling, no table lookups."
  [!a n]
  (let [vals (object-array (+ n 2))
        dirty (boolean-array (+ n 2))
        fns (object-array (+ n 2))]
    (aset fns 1 (fn [] @!a))
    (doseq [i (range 2 (+ n 2))]
      (let [j (dec i)] (aset fns i (fn [] (inc (aget vals j))))))
    {:run! (fn []
             (aset dirty 1 true)
             (loop [i 1]
               (when (< i (+ n 2))
                 (when (aget dirty i)
                   (aset dirty i false)
                   (let [v ((aget fns i))]
                     (when-not (= v (aget vals i))
                       (aset vals i v)
                       (when (< (inc i) (+ n 2)) (aset dirty (inc i) true)))))
                 (recur (inc i)))))
     :value #(aget vals (inc n))}))

(defn closure-chain-same-semantics
  "Closure product with the same per-node semantics as the table evaluator:
  three-state inputs, exceptions as values, subscriber and export checks."
  [!a n]
  (let [vals (object-array (+ n 2))
        dirty (boolean-array (+ n 2))
        subs (object-array (+ n 2))
        exported (object-array (+ n 2))
        fns (object-array (+ n 2))
        settled? (fn [x] (not (or (rt/pending? x) (rt/failure? x))))]
    (aset fns 1 (fn [] @!a))
    (doseq [i (range 2 (+ n 2))]
      (let [j (dec i)]
        (aset fns i (fn [] (let [x (aget vals j)]
                             (if (settled? x) (try (inc x) (catch Throwable e (rt/failure e))) x))))))
    {:run! (fn []
             (aset dirty 1 true)
             (loop [i 1]
               (when (< i (+ n 2))
                 (when (aget dirty i)
                   (aset dirty i false)
                   (let [v ((aget fns i)) old (aget vals i)]
                     (when-not (or (identical? v old) (and (not (fn? v)) (= v old)))
                       (aset vals i v)
                       (when (< (inc i) (+ n 2)) (aset dirty (inc i) true))
                       (when-let [s (aget subs i)] (doseq [cb @s] (cb)))
                       (when (aget exported i) (throw (ex-info "unused" {}))))))
                 (recur (inc i)))))}))

(defn bench-interpreter []
  (let [n 1000
        !a (atom 0)
        peer (rt/peer :server)
        f (rt/mount-root! peer (chain-ctor !a n))
        _ (rt/run! peer)
        table-ns (measure 2000 #(do (swap! !a inc) (rt/run! peer)))
        !b (atom 0)
        c (closure-chain !b n)
        closure-ns (measure 2000 #(do (swap! !b inc) ((:run! c))))
        !c (atom 0)
        c2 (closure-chain-same-semantics !c n)
        same-ns (measure 2000 #(do (swap! !c inc) ((:run! c2))))
        pct (fn [a b] (format "%.0f%%" (* 100.0 (/ (- a b) b))))]
    (assert (= (+ n @!a) (rt/value f (inc n))))
    {:chain n :table (ms table-ns)
     :closure-same-semantics (ms same-ns) :overhead (pct table-ns same-ns)
     :closure-minimal (ms closure-ns) :overhead-vs-minimal (pct table-ns closure-ns)}))

;; ---------------------------------------------------------------- B/C. table rendering

(def !rows (atom []))

(r/defn Row [{:keys [id name price]}]
  [:tr [:td id] [:td name] [:td price]])

(r/defn Table []
  [:table (r/for [row (r/server (r/watch !rows)) :by :id] (Row row))])

(defn rows [n] (vec (for [i (range n)] {:id i :name (str "product " i) :price (* 1.5 i)})))

(defn render-pair []
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) Table)]
    (assoc p :dom-root root)))

(defn bench-table []
  (let [create-ns (measure 10 #(do (reset! !rows (rows 1000))
                                   (let [p (render-pair)] (ct/flush! p)
                                     (rt/unmount-frame! (:client-root p)) (rt/unmount-frame! (:server-root p)))))
        p (do (reset! !rows (rows 1000)) (ct/flush! (render-pair)))
        i (atom 0)
        update-ns (measure 100 #(do (swap! !rows assoc-in [500 :name] (str "n" (swap! i inc))) (ct/flush! p)))
        _ (ct/clear-wire! p)
        _ (do (swap! !rows assoc-in [500 :name] "x") (ct/flush! p))
        bytes (ct/bytes-sent p)]
    {:create-1k-rows (ms create-ns) :update-1-of-1k (ms update-ns)
     :update-bytes bytes
     :server-frames (count (rt/frames (:server p)))
     :client-frames (count (rt/frames (:client p)))}))

;; ---------------------------------------------------------------- D. session memory

(r/defn SharedTable []
  [:table (r/for [row (r/shared ::rows (r/watch !rows)) :by :id] (Row row))])

(defn per-session-query
  "What a per-session database query returns: fresh maps, no sharing."
  [rows]
  (mapv #(into {} %) rows))

(r/defn PrivateTable []
  [:table (r/for [row (r/server (per-session-query (r/watch !rows))) :by :id] (Row row))])

(defn- heap []
  (dotimes [_ 3] (System/gc) (Thread/sleep 100))
  (let [rt (Runtime/getRuntime)] (- (.totalMemory rt) (.freeMemory rt))))

(defn- sessions-bytes [ctor n]
  (let [before (heap)
        ss (doall (for [_ (range n)] (session/start! ctor [] {:send! (fn [_])})))]
    (Thread/sleep 1500)
    (shared/await-idle)
    (Thread/sleep 500)
    (let [after (heap)]
      (run! session/close! ss)
      (Thread/sleep 500)
      (quot (- after before) n))))

(defn bench-sessions []
  (reset! !rows (rows 1000))
  {:sessions 1000
   :shared-bytes-per-session (sessions-bytes SharedTable 1000)
   :private-bytes-per-session (sessions-bytes PrivateTable 1000)})

(declare bench-scenarios)

(defn -main [& args]
  (when (= ["scenarios"] args)
    (println "Scenarios:" (bench-scenarios))
    (System/exit 0))
  (when-not (= ["sessions"] args)
    (println "A. interpreter overhead:" (bench-interpreter))
    (println "B/C. 1000-row table:" (bench-table)))
  (println "D. 1000 sessions on one 1000-row table:" (bench-sessions))
  (shutdown-agents)
  (System/exit 0))

;; ---------------------------------------------------------------- scenarios (design §16)

(def big (vec (for [i (range 100000)] {:id i :name (str "row " i)})))

(r/defn BigRows [start end]
  (r/for [row (r/server (subvec big start end)) :recycle true] [:div.row (:name row)]))

(r/defn BigTable* []
  (hypercurve.virtual/Window {:total (r/server (count big)) :row-height 20 :height 600} BigRows))

(def !shapes (atom []))

(r/defn Shapes []
  [:g (r/for [{:keys [id x y]} (r/server (r/watch !shapes)) :by :id] [:rect {:x x :y y :w 10 :h 10}])])

(defn bench-scenarios []
  (require 'hypercurve.virtual 'hypercurve.agg 'hypercurve.dynamic)
  (let [render (fn [ctor] (let [root (h/root) hooks (mount/renderer (h/dom) root)
                                p (ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor)]
                            (ct/flush! p) (assoc p :dom-root root)))
        ;; 1. 100k-row table: bytes per scroll step
        p (render BigTable*)
        _ (ct/clear-wire! p)
        _ (h/fire! (h/query (:dom-root p) ".hypercurve-window") "scroll" {:scroll-top 600000})
        _ (ct/flush! p)
        scroll-bytes (ct/bytes-sent p :s->c)
        ;; 2. whiteboard: move one of 1000 shapes
        _ (reset! !shapes (vec (for [i (range 1000)] {:id i :x i :y i})))
        w (render Shapes)
        _ (ct/clear-wire! w)
        _ (swap! !shapes assoc-in [500 :x] 9999)
        _ (ct/flush! w)
        move-bytes (ct/bytes-sent w :s->c)
        ;; 3. dataviz: append one point to a 100k series
        series (vec (for [i (range 100000)] {:t (* i 10) :v i}))
        b ((requiring-resolve 'hypercurve.agg/bucketer) :sum :t 1000 :v)
        _ (b series)
        s2 (conj series {:t 1000000 :v 1})
        !s (atom series)
        inc-ns (measure 50 #(b (swap! !s conj {:t (+ 1000000 (count @!s)) :v 1})))
        full-ns (measure 10 #(((requiring-resolve 'hypercurve.agg/bucketer) :sum :t 1000 :v) s2))
        ;; 4. notebook: compile + load a cell at run time
        cell-ns (measure 20 #((requiring-resolve 'hypercurve.dynamic/load)
                              ((requiring-resolve 'hypercurve.dynamic/compile-table) 'nb/c '[x] '([:pre (str (* 2 x))]))))]
    {:table-100k-scroll-bytes scroll-bytes
     :whiteboard-move-1-of-1000-bytes move-bytes
     :series-100k-append (ms inc-ns) :series-100k-full (ms full-ns)
     :notebook-cell-compile+load (ms cell-ns)}))
