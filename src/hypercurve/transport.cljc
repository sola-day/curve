(ns hypercurve.transport
  "Reliable framing over a connection that may drop (design §8.3, §8.4).

  frame = kind (1 byte) + seq (varint) + ack (varint) + payload
    kind 0  data: a protocol message; seq counts data frames from 1
    kind 1  control: a small map (session token, version, stale, drain)
  ack is the highest data seq received so far. Each side keeps data frames
  until they are acknowledged; after a reconnect it resends what the other
  side has not seen, and the receiver drops anything it already applied.
  Delivery is therefore exactly-once across reconnects."
  (:require [hypercurve.codec :as codec]))

(defn state [] {:out-seq (volatile! 0) :in-seq (volatile! 0) :unacked (volatile! (sorted-map))
                :peer-ack (volatile! 0)})

(defn- varint-bytes [n]
  (loop [n n acc []]
    (if (< n 128) (conj acc n) (recur (quot n 128) (conj acc (+ 128 (rem n 128)))))))

(defn- concat-bytes [header payload]
  #?(:clj (let [out (byte-array (+ (count header) (alength ^bytes payload)))]
            (dotimes [i (count header)] (aset out i (unchecked-byte (nth header i))))
            (System/arraycopy payload 0 out (count header) (alength ^bytes payload))
            out)
     :cljs (let [out (js/Uint8Array. (+ (count header) (.-length payload)))]
             (dotimes [i (count header)] (aset out i (nth header i)))
             (.set out payload (count header))
             out)))

(defn- byte-at [arr i] #?(:clj (bit-and 0xff (aget ^bytes arr (int i))) :cljs (aget arr i)))

(defn- read-varint [arr pos]
  (loop [p pos result 0 mul 1]
    (let [b (byte-at arr p)]
      (if (< b 128) [(+ result (* b mul)) (inc p)] (recur (inc p) (+ result (* (- b 128) mul)) (* mul 128))))))

(defn- slice [arr from]
  #?(:clj (java.util.Arrays/copyOfRange ^bytes arr (int from) (alength ^bytes arr))
     :cljs (.subarray arr from)))

(defn data-frame
  "Wrap a protocol message for sending; it is kept until acknowledged."
  [st payload]
  (let [seq (vswap! (:out-seq st) inc)
        f (concat-bytes (into [0] (concat (varint-bytes seq) (varint-bytes @(:in-seq st)))) payload)]
    (vswap! (:unacked st) assoc seq f)
    f))

(defn control-frame [st m]
  (concat-bytes (into [1] (concat (varint-bytes 0) (varint-bytes @(:in-seq st))))
                (codec/encode-delta-blob [:v m])))

(defn receive
  "Unwrap a frame: {:data payload} to apply, {:control m}, or nil for a
  duplicate. Acknowledged frames are released."
  [st frame]
  (let [kind (byte-at frame 0)
        [seq p] (read-varint frame 1)
        [ack p] (read-varint frame p)
        payload (slice frame p)]
    (vreset! (:peer-ack st) (max @(:peer-ack st) ack))
    (vswap! (:unacked st) #(into (sorted-map) (filter (fn [[s _]] (> s ack))) %))
    (case kind
      0 (when (= seq (inc @(:in-seq st)))
          (vreset! (:in-seq st) seq)
          {:data payload})
      1 {:control (second (codec/decode-delta-blob payload))})))

(defn resend
  "Frames the other side has not acknowledged (or has not seen: seen is the
  last seq it reports), to send again after a reconnect."
  ([st] (resend st @(:peer-ack st)))
  ([st seen] (vals (subseq @(:unacked st) > seen))))

(defn seen "Highest data seq received." [st] @(:in-seq st))

(defn restore
  "Transport state continuing from a snapshot {:in seen :out sent}."
  [{:keys [in out]}]
  (let [st (state)]
    (vreset! (:in-seq st) (or in 0))
    (vreset! (:out-seq st) (or out 0))
    st))
