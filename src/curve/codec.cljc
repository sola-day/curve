(ns curve.codec
  "Binary wire format.

  A message is a sequence of sections, each `tag count entries...`:
    1 decl  [id pid node key]      2 vals [frame node delta]
    3 drop  [id]                   4 call [token frame node args]
    5 ret   [token ok value]
  Integers are LEB128 varints (signed ones zigzag). Values carry a type byte;
  keywords are interned per connection direction, so a keyword costs its
  name once and two bytes after. Deltas have their own compact encodings.
  Only the types listed here decode: no records, classes or evaluation."
  #?(:clj (:import [java.io ByteArrayOutputStream]
                   [java.nio ByteBuffer]
                   [java.nio.charset StandardCharsets])))

(def max-message-bytes (* 4 1024 1024))

;; ---------------------------------------------------------------- bytes

(defprotocol Out
  (put! [o b])
  (put-bytes! [o arr])
  (out-bytes [o]))

#?(:clj
   (extend-type ByteArrayOutputStream
     Out
     (put! [o b] (.write o (int b)))
     (put-bytes! [o ^bytes arr] (.write o arr 0 (alength arr)))
     (out-bytes [o] (.toByteArray o))))

#?(:cljs
   (deftype JsOut [^:mutable buf ^:mutable n]
     Out
     (put! [_ b]
       (when (= n (.-length buf))
         (let [nb (js/Uint8Array. (* 2 (.-length buf)))] (.set nb buf) (set! buf nb)))
       (aset buf n b)
       (set! n (inc n)))
     (put-bytes! [this arr]
       (when (> (+ n (.-length arr)) (.-length buf))
         (let [nb (js/Uint8Array. (* 2 (+ n (.-length arr))))] (.set nb buf) (set! buf nb)))
       (.set buf arr n)
       (set! n (+ n (.-length arr))))
     (out-bytes [_] (.slice buf 0 n))))

(defn- new-out [] #?(:clj (ByteArrayOutputStream. 64) :cljs (JsOut. (js/Uint8Array. 64) 0)))

(defn- utf8 [s]
  #?(:clj (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

(defn- from-utf8 [arr off len]
  #?(:clj (String. ^bytes arr (int off) (int len) StandardCharsets/UTF_8)
     :cljs (.decode (js/TextDecoder.) (.subarray arr off (+ off len)))))

(defn- byte-at [arr i]
  #?(:clj (bit-and 0xff (aget ^bytes arr (int i))) :cljs (aget arr i)))

(defn byte-count [arr] #?(:clj (alength ^bytes arr) :cljs (.-length arr)))

;; ---------------------------------------------------------------- varints

#?(:clj
   (do
     (defn- uvarint! [o ^long n]
       ;; n is treated as unsigned 64-bit
       (loop [n n]
         (if (zero? (unsigned-bit-shift-right n 7))
           (put! o n)
           (do (put! o (bit-or 0x80 (bit-and n 0x7f)))
               (recur (unsigned-bit-shift-right n 7))))))
     (defn- zigzag [^long n] (bit-xor (bit-shift-left n 1) (bit-shift-right n 63)))
     (defn- unzigzag [^long n] (bit-xor (unsigned-bit-shift-right n 1) (- (bit-and n 1))))
     (defn- read-uvarint [r]
       (let [{:keys [buf pos]} r]
         (loop [result 0 shift 0]
           (let [b (long (byte-at buf @pos))]
             (vswap! pos inc)
             (when (> shift 63) (throw (ex-info "curve.codec: varint too long" {})))
             (let [result (bit-or result (bit-shift-left (bit-and b 0x7f) shift))]
               (if (< b 128) result (recur result (+ shift 7)))))))))
   :cljs
   (do
     ;; JS numbers: arithmetic, exact up to 2^53
     (defn- uvarint! [o n]
       (loop [n n]
         (if (< n 128)
           (put! o n)
           (do (put! o (+ 128 (rem n 128)))
               (recur (js/Math.floor (/ n 128)))))))
     (defn- zigzag [n] (if (neg? n) (dec (* -2 n)) (* 2 n)))
     (defn- unzigzag [n] (if (odd? n) (- (/ (inc n) 2)) (/ n 2)))
     (defn- read-uvarint [r]
       (let [{:keys [buf pos]} r]
         (loop [result 0 mul 1]
           (let [b (byte-at buf @pos)]
             (vswap! pos inc)
             (if (< b 128)
               (+ result (* b mul))
               (recur (+ result (* (- b 128) mul)) (* mul 128)))))))))

;; ---------------------------------------------------------------- values

(def ^:private T-NIL 0) (def ^:private T-TRUE 1) (def ^:private T-FALSE 2)
(def ^:private T-INT 3) (def ^:private T-DOUBLE 4) (def ^:private T-STR 5)
(def ^:private T-KW-NEW 6) (def ^:private T-KW-REF 7) (def ^:private T-VEC 8)
(def ^:private T-MAP 9) (def ^:private T-SET 10) (def ^:private T-SYM 11)
(def ^:private T-INST 12) (def ^:private T-UUID 13)

(defn- safe-int? [x]
  #?(:clj (and (integer? x) (instance? Long (try (long x) (catch Exception _ nil))))
     :cljs (and (number? x) (js/Number.isSafeInteger x))))

(defn- double-bytes [x]
  #?(:clj (.array (.putDouble (ByteBuffer/allocate 8) (double x)))
     :cljs (let [b (js/ArrayBuffer. 8)] (.setFloat64 (js/DataView. b) 0 x) (js/Uint8Array. b))))

(defn- read-double [{:keys [buf pos]}]
  (let [p @pos]
    (vswap! pos + 8)
    #?(:clj (.getDouble (ByteBuffer/wrap ^bytes buf (int p) 8))
       :cljs (.getFloat64 (js/DataView. (.-buffer buf) (+ (.-byteOffset buf) p) 8) 0))))

(defn- str! [o s] (let [b (utf8 s)] (uvarint! o (byte-count b)) (put-bytes! o b)))

(defn- read-str [{:keys [buf pos] :as r}]
  (let [n (read-uvarint r) p @pos]
    (vswap! pos + n)
    (from-utf8 buf p n)))

(declare write-value!)

(defn- kw-name [k] (if-let [n (namespace k)] (str n "/" (name k)) (name k)))

(defn write-value! [st o x]
  (cond
    (nil? x) (put! o T-NIL)
    (true? x) (put! o T-TRUE)
    (false? x) (put! o T-FALSE)
    (safe-int? x) (do (put! o T-INT) (uvarint! o (zigzag (long x))))
    (number? x) (do (put! o T-DOUBLE) (put-bytes! o (double-bytes x)))
    (string? x) (do (put! o T-STR) (str! o x))
    (keyword? x) (if-let [i (get @(:kws st) x)]
                   (do (put! o T-KW-REF) (uvarint! o i))
                   (do (vswap! (:kws st) assoc x (count @(:kws st)))
                       (put! o T-KW-NEW) (str! o (kw-name x))))
    (map? x) (do (put! o T-MAP) (uvarint! o (count x))
                 (doseq [[k v] x] (write-value! st o k) (write-value! st o v)))
    (set? x) (do (put! o T-SET) (uvarint! o (count x)) (doseq [v x] (write-value! st o v)))
    (or (vector? x) (seq? x)) (do (put! o T-VEC) (uvarint! o (count x)) (doseq [v x] (write-value! st o v)))
    (symbol? x) (do (put! o T-SYM) (str! o (str x)))
    (inst? x) (do (put! o T-INST) (uvarint! o (zigzag (long (inst-ms x)))))
    (uuid? x) (do (put! o T-UUID) (str! o (str x)))
    :else (throw (ex-info (str "curve.codec: cannot send value of type " (type x)) {:value x}))))

(defn read-value [st r]
  (let [t (byte-at (:buf r) @(:pos r))]
    (vswap! (:pos r) inc)
    (condp = t
      T-NIL nil T-TRUE true T-FALSE false
      T-INT (unzigzag (read-uvarint r))
      T-DOUBLE (read-double r)
      T-STR (read-str r)
      T-KW-NEW (let [k (keyword (read-str r))] (vswap! (:kws st) conj k) k)
      T-KW-REF (nth @(:kws st) (read-uvarint r))
      T-VEC (let [n (read-uvarint r)] (loop [i 0 acc (transient [])] (if (< i n) (recur (inc i) (conj! acc (read-value st r))) (persistent! acc))))
      T-MAP (let [n (read-uvarint r)] (loop [i 0 acc (transient {})] (if (< i n) (recur (inc i) (assoc! acc (read-value st r) (read-value st r))) (persistent! acc))))
      T-SET (let [n (read-uvarint r)] (loop [i 0 acc (transient #{})] (if (< i n) (recur (inc i) (conj! acc (read-value st r))) (persistent! acc))))
      T-SYM (symbol (read-str r))
      T-INST (let [ms (unzigzag (read-uvarint r))] #?(:clj (java.util.Date. (long ms)) :cljs (js/Date. ms)))
      T-UUID (parse-uuid (read-str r))
      (throw (ex-info "curve.codec: bad value tag" {:tag t})))))

;; ---------------------------------------------------------------- deltas

(def ^:private D-VAL 0) (def ^:private D-MAP 1) (def ^:private D-SEQ 2)
(def ^:private D-SET 3) (def ^:private D-PENDING 4) (def ^:private D-ERROR 5) (def ^:private D-FN 6)
(def ^:private D-RAW 7)

(declare state decoder-state)

(defn- write-delta! [st o [t x]]
  (case t
    ;; a pre-encoded, self-contained delta (shared values: encoded once,
    ;; sent to every session as is)
    :raw (do (put! o D-RAW) (uvarint! o (byte-count x)) (put-bytes! o x))
    :v (do (put! o D-VAL) (write-value! st o x))
    :p (put! o D-PENDING)
    :e (do (put! o D-ERROR) (str! o (str x)))
    :f (put! o D-FN)
    :t (do (put! o D-SET)
           (uvarint! o (count (:add x))) (doseq [v (:add x)] (write-value! st o v))
           (uvarint! o (count (:remove x))) (doseq [v (:remove x)] (write-value! st o v)))
    ;; map and seq deltas start with a flags byte saying which parts follow
    :m (let [{:keys [set dissoc patch]} x]
         (put! o D-MAP)
         (put! o (cond-> 0 (seq set) (bit-or 1) (seq dissoc) (bit-or 2) (seq patch) (bit-or 4)))
         (when (seq set) (uvarint! o (count set)) (doseq [[k v] set] (write-value! st o k) (write-value! st o v)))
         (when (seq dissoc) (uvarint! o (count dissoc)) (doseq [k dissoc] (write-value! st o k)))
         (when (seq patch) (uvarint! o (count patch)) (doseq [[k d] patch] (write-value! st o k) (write-delta! st o d))))
    :s (let [{:keys [degree grow shrink permutation change patch]} x
             sized (or (pos? (or grow 0)) (pos? (or shrink 0)))]
         ;; without growth or shrinkage the receiver knows the degree
         (put! o D-SEQ)
         (put! o (cond-> 0 sized (bit-or 1) (seq permutation) (bit-or 2) (seq change) (bit-or 4) (seq patch) (bit-or 8)))
         (when sized (uvarint! o degree) (uvarint! o grow) (uvarint! o shrink))
         (when (seq permutation) (uvarint! o (count permutation)) (doseq [[a b] permutation] (uvarint! o a) (uvarint! o b)))
         (when (seq change) (uvarint! o (count change)) (doseq [[i v] change] (uvarint! o i) (write-value! st o v)))
         (when (seq patch) (uvarint! o (count patch)) (doseq [[i d] patch] (uvarint! o i) (write-delta! st o d))))))

(defn- read-n [r f] (let [n (read-uvarint r)] (loop [i 0 acc []] (if (< i n) (recur (inc i) (conj acc (f))) acc))))

(defn- read-delta [st r]
  (let [t (byte-at (:buf r) @(:pos r))]
    (vswap! (:pos r) inc)
    (condp = t
      D-VAL [:v (read-value st r)]
      D-PENDING [:p]
      D-ERROR [:e (read-str r)]
      D-FN [:f]
      D-RAW (let [n (read-uvarint r)
                  end (+ @(:pos r) n)
                  d (read-delta (decoder-state) r)]
              (when-not (= end @(:pos r)) (throw (ex-info "curve.codec: bad raw delta" {})))
              d)
      D-SET [:t (let [add (read-n r #(read-value st r)) rm (read-n r #(read-value st r))]
                  (cond-> {} (seq add) (assoc :add (set add)) (seq rm) (assoc :remove (set rm))))]
      D-MAP [:m (let [fl (read-uvarint r)
                      set (when (pos? (bit-and fl 1)) (read-n r #(vector (read-value st r) (read-value st r))))
                      dis (when (pos? (bit-and fl 2)) (read-n r #(read-value st r)))
                      pat (when (pos? (bit-and fl 4)) (read-n r #(vector (read-value st r) (read-delta st r))))]
                  (cond-> {} (seq set) (assoc :set (into {} set))
                          (seq dis) (assoc :dissoc (clojure.core/set dis))
                          (seq pat) (assoc :patch (into {} pat))))]
      D-SEQ [:s (let [fl (read-uvarint r)
                      [degree grow shrink] (if (pos? (bit-and fl 1))
                                             [(read-uvarint r) (read-uvarint r) (read-uvarint r)]
                                             [nil 0 0])
                      perm (when (pos? (bit-and fl 2)) (read-n r #(vector (read-uvarint r) (read-uvarint r))))
                      change (when (pos? (bit-and fl 4)) (read-n r #(vector (read-uvarint r) (read-value st r))))
                      patch (when (pos? (bit-and fl 8)) (read-n r #(vector (read-uvarint r) (read-delta st r))))]
                  (cond-> {:grow grow :shrink shrink
                           :permutation (into {} perm) :change (into {} change)}
                    degree (assoc :degree degree)
                    (seq patch) (assoc :patch (into {} patch))))]
      (throw (ex-info "curve.codec: bad delta tag" {:tag t})))))

;; ---------------------------------------------------------------- messages

(defn state
  "Per-direction codec state (keyword interning). Use one for encoding on the
  sender and a matching one for decoding on the receiver."
  [] {:kws (volatile! {})})

(defn decoder-state [] {:kws (volatile! [])})

(defn encode-delta-blob
  "Encode a delta with no connection state, for reuse across connections."
  [d]
  (let [o (new-out)] (write-delta! (state) o d) (out-bytes o)))

(defn decode-delta-blob [bs]
  (read-delta (decoder-state) {:buf bs :pos (volatile! 0)}))

(defn encode
  "Encode a message map to bytes."
  [st {:keys [decl vals drop call ret]}]
  (let [o (new-out)
        section (fn [tag xs f] (when (seq xs) (put! o tag) (uvarint! o (count xs)) (doseq [x xs] (f x))))]
    (section 1 decl (fn [[id pid node key]] (uvarint! o id) (uvarint! o pid) (uvarint! o node) (write-value! st o key)))
    (section 2 vals (fn [[id node d]] (uvarint! o id) (uvarint! o node) (write-delta! st o d)))
    (section 3 drop (fn [id] (uvarint! o id)))
    (section 4 call (fn [[token id node args]] (uvarint! o token) (uvarint! o id) (uvarint! o node) (write-value! st o args)))
    (section 5 ret (fn [[token ok v]] (uvarint! o token) (write-value! st o ok) (write-value! st o v)))
    (out-bytes o)))

(defn decode
  "Decode bytes to a message map. Throws on malformed or oversized input."
  [st arr]
  (when (> (byte-count arr) max-message-bytes)
    (throw (ex-info "curve.codec: message too large" {:bytes (byte-count arr)})))
  (let [r {:buf arr :pos (volatile! 0)}
        n (byte-count arr)]
    (loop [m {}]
      (if (>= @(:pos r) n)
        m
        (let [tag (read-uvarint r)
              entries (read-n r (case tag
                                  1 #(vector (read-uvarint r) (read-uvarint r) (read-uvarint r) (read-value st r))
                                  2 #(vector (read-uvarint r) (read-uvarint r) (read-delta st r))
                                  3 #(read-uvarint r)
                                  4 #(vector (read-uvarint r) (read-uvarint r) (read-uvarint r) (read-value st r))
                                  5 #(vector (read-uvarint r) (read-value st r) (read-value st r))
                                  (throw (ex-info "curve.codec: bad section" {:tag tag}))))]
          (recur (assoc m (case tag 1 :decl 2 :vals 3 :drop 4 :call 5 :ret) entries)))))))

(defn combine
  "Messages form a monoid under section-wise concatenation."
  [a b]
  (merge-with into a b))

(defn link
  "A pair of matching encoder/decoder states for one direction:
  returns {:encode f :decode f}."
  []
  (let [enc (state) dec (decoder-state)]
    {:encode #(encode enc %) :decode #(decode dec %)}))
