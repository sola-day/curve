(ns curve.test
  "Headless two-peer harness. Client and server peers run in one process and
  talk through the real message path, so tests can assert on what crossed
  the wire. Not part of any production bundle."
  (:require [curve.codec :as codec]
            [curve.runtime :as rt]))

(defn pair
  "Connect a client and a server peer in-process. Messages go through the
  binary codec (a link per direction) unless :binary? false."
  [& {:keys [binary? client-opts server-opts] :or {binary? true}}]
  (let [;; notifications from other threads (watches, shared values) are
        ;; queued and applied on the test thread during flush!, as a real
        ;; session would
        cq (atom #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs cljs.core/PersistentQueue.EMPTY))
        sq (atom #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs cljs.core/PersistentQueue.EMPTY))
        client (apply rt/peer :client (mapcat identity (merge {:post! #(swap! cq conj %)} client-opts)))
        server (apply rt/peer :server (mapcat identity (merge {:post! #(swap! sq conj %)} server-opts)))]
    {:client client :server server :binary? binary? :posted {:client cq :server sq}
     :links {:s->c (codec/link) :c->s (codec/link)}
     :wire (atom [])}))

(defn mount!
  "Mount ctor as root on both peers."
  [{:keys [client server] :as p} ctor & args]
  (assoc p
    :client-root (apply rt/mount-root! client ctor args)
    :server-root (apply rt/mount-root! server ctor args)))

(defn- deliver! [{:keys [binary? links wire]} from to dir]
  (when-let [m (rt/take-message! from)]
    (if binary?
      (let [{:keys [encode decode]} (get links dir)
            bs (encode m)]
        (swap! wire conj {:dir dir :msg m :bytes (codec/byte-count bs)})
        (rt/receive! to (decode bs)))
      (do (swap! wire conj {:dir dir :msg m})
          (rt/receive! to m)))
    true))

(defn- drain-posted! [q]
  (when q
    (loop []
      (let [[old _] (swap-vals! q pop)]
        (when-let [g (peek old)] (g) (recur))))))

(defn flush!
  "Run both peers and exchange messages until nothing is left to do."
  [{:keys [client server] :as p}]
  (loop [n 0]
    (when (> n 1000) (throw (ex-info "curve.test/flush!: no quiescence" {})))
    (drain-posted! (get-in p [:posted :server]))
    (rt/run! server)
    (drain-posted! (get-in p [:posted :client]))
    (rt/run! client)
    (let [a (deliver! p server client :s->c)
          b (deliver! p client server :c->s)]
      (when (or a b (seq @(get-in p [:posted :server] (atom nil))) (seq @(get-in p [:posted :client] (atom nil))))
        (recur (inc n)))))
  p)

(defn wire-log [p] @(:wire p))
(defn clear-wire! [p] (reset! (:wire p) []) p)

(defn bytes-sent
  ([p] (reduce + 0 (keep :bytes (wire-log p))))
  ([p dir] (reduce + 0 (keep #(when (= dir (:dir %)) (:bytes %)) (wire-log p)))))

(defn slots-changed
  "Set of [direction node-id] pairs whose values crossed the wire."
  [p]
  (set (for [{:keys [dir msg]} (wire-log p) [_ i _] (:vals msg)] [dir i])))
