(ns curve.test
  "Headless two-peer harness. Client and server peers run in one process and
  talk through the real message path, so tests can assert on what crossed
  the wire. Not part of any production bundle."
  (:require [curve.runtime :as rt]))

(defn- default-codec [m] m)

(defn pair
  "Connect a client and a server peer in-process.
  opts: :codec fn applied to every message (e.g. encode+decode roundtrip),
        :size fn message -> byte count for wire accounting."
  [& {:keys [codec size client-opts server-opts] :or {codec default-codec}}]
  (let [client (apply rt/peer :client (mapcat identity client-opts))
        server (apply rt/peer :server (mapcat identity server-opts))]
    {:client client :server server :codec codec :size size
     :wire (atom [])}))

(defn mount!
  "Mount ctor as root on both peers."
  [{:keys [client server] :as p} ctor & args]
  (assoc p
    :client-root (apply rt/mount-root! client ctor args)
    :server-root (apply rt/mount-root! server ctor args)))

(defn- deliver! [{:keys [codec wire size]} from to dir]
  (when-let [m (rt/take-message! from)]
    (let [m' (codec m)]
      (swap! wire conj {:dir dir :msg m :bytes (when size (size m))})
      (rt/receive! to m')
      true)))

(defn flush!
  "Run both peers and exchange messages until nothing is left to do."
  [{:keys [client server] :as p}]
  (loop [n 0]
    (when (> n 1000) (throw (ex-info "curve.test/flush!: no quiescence" {})))
    (rt/run! server)
    (rt/run! client)
    (let [a (deliver! p server client :s->c)
          b (deliver! p client server :c->s)]
      (when (or a b) (recur (inc n)))))
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
