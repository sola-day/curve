(ns curve.mount
  "Mounts rendered frames into a DOM (browser or headless).

  Each rendered frame owns a contiguous range of sibling nodes: its template
  roots, where a root that is a child anchor is preceded by the ranges of the
  frames rendered into it. A range is never empty (the anchor itself is in
  it), so moving or removing a frame is a walk over its range."
  (:require [clojure.string :as str]
            [curve.dom-api :as d]
            [curve.runtime :as rt]))

(defn- resolve-path [dom roots [r & more]]
  (reduce #(d/child-at dom %1 %2) (nth roots r) more))

(defn- display [v]
  (cond (rt/pending? v) nil
        (rt/failure? v) ""
        (nil? v) ""
        :else (str v)))

(defn attr-value
  "DOM value for attribute name from a reactive value."
  [name v prefix]
  (if (contains? #{"value" "checked"} name)
    (if (= name "checked") (boolean v) (if (nil? v) "" (str v)))
    (let [s (cond (or (nil? v) (false? v)) nil
                  (true? v) ""
                  (and (= name "class") (coll? v)) (str/join " " (map #(if (keyword? %) (clojure.core/name %) (str %)) (remove nil? v)))
                  (and (= name "style") (map? v)) (apply str (for [[k x] v :when (some? x)]
                                                              (str (clojure.core/name k) ":" (if (keyword? x) (clojure.core/name x) x) ";")))
                  (keyword? v) (clojure.core/name v)
                  :else (str v))]
      (if (and prefix (= name "class")) (if (seq s) (str prefix " " s) prefix) s))))

(defn range-nodes
  "The DOM nodes of a rendered frame, in order."
  [m f]
  (when-let [st (get @(:frames m) (:seq-id f))]
    (mapcat (fn [r i]
              (if-let [nid (get (:root-anchors st) i)]
                (concat (mapcat #(range-nodes m %) (rt/ordered-children f nid)) [r])
                [r]))
            (:roots st) (range))))

(defn- render-frame! [m f]
  (let [dom (:dom m)
        render (:render (:ctor f))
        roots (d/instantiate dom render)
        holes (:holes render)
        targets (mapv #(resolve-path dom roots (second %)) holes)
        st (volatile! {:roots roots :anchors {} :root-anchors {}})]
    (doseq [[[kind path & more] node] (map vector holes targets)]
      (case kind
        :text (let [[id] more
                    t (d/text-node dom "")
                    upd (fn [] (when-let [s (display (rt/value f id))] (d/set-text! dom t s)))]
                (if (= 1 (count path))
                  (vswap! st assoc-in [:roots (first path)] t)
                  (d/replace-node! dom node t))
                (upd)
                (rt/on-cleanup! f (rt/subscribe! f id upd)))
        :attr (let [[name id prefix] more
                    upd (fn [] (let [v (rt/value f id)]
                                 (when-not (rt/pending? v)
                                   (d/set-attr! dom node name (attr-value name (when-not (rt/failure? v) v) prefix)))))]
                (upd)
                (rt/on-cleanup! f (rt/subscribe! f id upd)))
        :event (let [[type id] more]
                 (rt/on-cleanup! f (d/listen! dom node type
                                              (fn [e] (let [h (rt/value f id)]
                                                        (when (fn? h) (h e)))))))
        :child (let [[id] more]
                 (vswap! st assoc-in [:anchors id] node)
                 (when (= 1 (count path))
                   (vswap! st assoc-in [:root-anchors (first path)] id)))))
    (vswap! (:frames m) assoc (:seq-id f) @st)))

(defn- place! [m f]
  (let [dom (:dom m)
        p (:parent f)]
    (if (nil? p)
      (doseq [n (range-nodes m f)] (d/insert-before! dom (:container m) n nil))
      (let [anchor (get-in @(:frames m) [(:seq-id p) :anchors (:node f)])
            parent (d/parent-of dom anchor)]
        (doseq [n (range-nodes m f)] (d/insert-before! dom parent n anchor))))))

(defn- reorder! [m f i]
  (when-let [anchor (get-in @(:frames m) [(:seq-id f) :anchors i])]
    (let [dom (:dom m)
          parent (d/parent-of dom anchor)]
      (loop [cursor anchor kids (reverse (rt/ordered-children f i))]
        (when-let [c (first kids)]
          (let [ns (range-nodes m c)]
            (when-not (identical? (d/next-of dom (last ns)) cursor)
              (doseq [n ns] (d/insert-before! dom parent n cursor)))
            (recur (first ns) (rest kids))))))))

(defn renderer
  "Peer hooks that render a client peer's frames into container."
  [dom container]
  (let [m {:dom dom :container container :frames (volatile! {})}]
    {:mounter m
     :on-mount (fn [f]
                 (when @(:rendered f)
                   (render-frame! m f)
                   (place! m f)))
     :on-unmount (fn [f]
                   (when-let [st (get @(:frames m) (:seq-id f))]
                     (doseq [r (:roots st)] (d/remove-node! (:dom m) r))
                     (vswap! (:frames m) dissoc (:seq-id f))))
     :on-children (fn [f i] (when @(:rendered f) (reorder! m f i)))}))
