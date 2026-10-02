(ns curve.headless
  "A small DOM for tests and server-side rendering: elements, text and
  comment nodes, attributes, properties and listeners. Implements exactly
  what curve.mount needs, plus HTML serialization and query helpers."
  (:require [clojure.string :as str]
            [curve.dom-api :as api]))

(defn- node [kind m]
  (merge {:kind kind :parent (volatile! nil) :children (volatile! [])
          :attrs (volatile! {}) :props (volatile! {}) :text (volatile! nil)
          :listeners (volatile! {})}
         m))

(defn element [tag] (node :element {:tag tag}))
(defn text [s] (let [n (node :text {})] (vreset! (:text n) s) n))
(defn comment-node [] (node :comment {}))

(defn- index-of [v x] (first (keep-indexed (fn [i y] (when (identical? x y) i)) v)))

(defn- detach! [n]
  (when-let [p @(:parent n)]
    (vswap! (:children p) (fn [cs] (let [i (index-of cs n)] (into (subvec cs 0 i) (subvec cs (inc i))))))
    (vreset! (:parent n) nil)))

(defn insert-before [p n ref]
  (detach! n)
  (vswap! (:children p) (fn [cs] (if-let [i (and ref (index-of cs ref))]
                                   (into (conj (subvec cs 0 i) n) (subvec cs i))
                                   (conj cs n))))
  (vreset! (:parent n) p)
  n)

(defn- build [t]
  (cond
    (= :hole t) (comment-node)
    (string? t) (text t)
    :else (let [e (element (:tag t))]
            (vreset! (:attrs e) (:attrs t))
            (doseq [c (:children t)] (insert-before e (build c) nil))
            e)))

(defn children [n] @(:children n))

(defrecord Headless []
  api/Dom
  (instantiate [_ render] (mapv build (:tree render)))
  (child-at [_ n i] (nth @(:children n) i))
  (text-node [_ s] (text s))
  (replace-node! [_ old new]
    (let [p @(:parent old)]
      (when p (insert-before p new old) (detach! old))))
  (insert-before! [_ p n ref] (insert-before p n ref))
  (remove-node! [_ n] (detach! n))
  (parent-of [_ n] @(:parent n))
  (next-of [_ n]
    (when-let [p @(:parent n)]
      (let [cs @(:children p) i (index-of cs n)] (get cs (inc i)))))
  (set-text! [_ n s] (vreset! (:text n) s))
  (set-attr! [_ el k v]
    (if (contains? #{"value" "checked"} k)
      (vswap! (:props el) assoc k v)
      (if (nil? v) (vswap! (:attrs el) dissoc k) (vswap! (:attrs el) assoc k (str v)))))
  (listen! [_ el type f]
    (vswap! (:listeners el) update type (fnil conj #{}) f)
    (fn [] (vswap! (:listeners el) update type disj f)))
  (event-data [_ e]
    (let [t (:target e)]
      (cond-> {:type (:type e)}
        (some? (get @(:props t) "value")) (assoc :value (get @(:props t) "value"))
        (some? (get @(:props t) "checked")) (assoc :checked (get @(:props t) "checked"))
        (:key e) (assoc :key (:key e))))))

(defn dom [] (->Headless))

;; ---------------------------------------------------------------- inspection

(def ^:private void-tags #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(defn- esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn html
  "Serialize. Comments print as <!--x--> only with :comments? true."
  ([n] (html n {}))
  ([n opts]
   (case (:kind n)
     :text (esc @(:text n))
     :comment (if (:comments? opts) "<!---->" "")
     :element (let [tag (:tag n)
                    attrs (merge @(:attrs n)
                                 (when-let [v (get @(:props n) "value")] {"value" (str v)})
                                 (when (get @(:props n) "checked") {"checked" ""}))]
                (str "<" tag
                     (apply str (for [[k v] (sort attrs)] (if (= "" v) (str " " k) (str " " k "=\"" (esc v) "\""))))
                     ">"
                     (when-not (void-tags tag)
                       (str (apply str (map #(html % opts) @(:children n))) "</" tag ">"))))
     :root (apply str (map #(html % opts) @(:children n))))))

(defn root [] (node :root {}))

(defn text-content [n]
  (case (:kind n)
    :text @(:text n)
    :comment ""
    (apply str (map text-content @(:children n)))))

(defn- matches? [n sel]
  (and (= :element (:kind n))
       (let [[_ tag rest] (re-matches #"([^.#]*)(.*)" sel)
             parts (re-seq #"([.#])([^.#]+)" rest)
             classes (set (str/split (get @(:attrs n) "class" "") #"\s+"))]
         (and (or (= "" tag) (= tag (:tag n)))
              (every? (fn [[_ t v]] (if (= t "#") (= v (get @(:attrs n) "id")) (contains? classes v))) parts)))))

(defn- descendants-matching [roots sel]
  (let [acc (volatile! [])]
    (doseq [r roots]
      (run! (fn walk [x] (when (matches? x sel) (vswap! acc conj x)) (run! walk @(:children x)))
            @(:children r)))
    (vec (distinct @acc))))

(defn query-all
  "Elements matching a selector of simple parts (tag, .class, #id) joined by
  descendant spaces, in document order."
  [n sel]
  (reduce (fn [roots part] (descendants-matching roots part))
          [n] (str/split (str/trim sel) #"\s+")))

(defn query [n sel] (first (query-all n sel)))

(defn fire!
  "Dispatch an event to n and its ancestors. event is a map; :target is set."
  [n type event]
  (let [e (assoc event :type type :target n)]
    (loop [x n]
      (when x
        (doseq [f (get @(:listeners x) type)] (f e))
        (recur @(:parent x))))))

(defn value [el] (get @(:props el) "value"))

(defn input!
  "Simulate typing: set the value property and fire input + change."
  [el v]
  (vswap! (:props el) assoc "value" v)
  (fire! el "input" {})
  (fire! el "change" {}))
