(ns hypercurve.dom
  "Browser implementation of hypercurve.dom-api. Templates are parsed once into
  <template> elements and cloned; bubbling events are delegated to one
  listener per type on the document."
  (:require [hypercurve.dom-api :as api]))

(def ^:private templates (js/Map.))

(def ^:private svg-tags
  ;; elements that only exist inside <svg>: a template starting with one is
  ;; the child of an svg element (e.g. the body of an r/for in an <svg>)
  #{"path" "g" "circle" "ellipse" "line" "polyline" "polygon" "rect" "text" "tspan"
    "use" "defs" "clipPath" "mask" "pattern" "linearGradient" "radialGradient" "stop"
    "foreignObject" "symbol" "marker" "filter"})

(defn- svg-child? [html]
  (when-let [[_ tag] (re-find #"^\s*<([a-zA-Z][\w-]*)" html)]
    (contains? svg-tags tag)))

(defn- template [html]
  (or (.get templates html)
      (let [t (.createElement js/document "template")]
        ;; parsed inside <svg> so the elements get the SVG namespace
        (set! (.-innerHTML t) (if (svg-child? html) (str "<svg>" html "</svg>") html))
        (.set templates html t)
        t)))

(def ^:private non-bubbling
  #{"focus" "blur" "scroll" "mouseenter" "mouseleave" "load" "error" "toggle"})

(def ^:private delegated (js/Set.))

(defn- handler-key [type] (str "__hypercurve_" type))

(defn- delegate! [type]
  (when-not (.has delegated type)
    (.add delegated type)
    (.addEventListener js/document type
                       (fn [e]
                         (loop [n (.-target e)]
                           (when (and n (not (.-cancelBubble e)))
                             (when-let [hs (unchecked-get n (handler-key type))]
                               (doseq [h (vals hs)] (h e)))
                             (recur (.-parentNode n))))))))

(defn- prop? [k] (or (= k "value") (= k "checked")))

(deftype BrowserDom []
  api/Dom
  (instantiate [_ render]
    (let [html (:html render)
          frag (.cloneNode (.-content (template html)) true)
          host (if (svg-child? html) (.-firstChild frag) frag)]
      (vec (array-seq (.-childNodes host)))))
  (child-at [_ n i] (aget (.-childNodes n) i))
  (text-node [_ s] (.createTextNode js/document s))
  (replace-node! [_ old new] (.replaceWith old new))
  (insert-before! [_ p n ref] (.insertBefore p n ref))
  (remove-node! [_ n] (.remove n))
  (parent-of [_ n] (.-parentNode n))
  (next-of [_ n] (.-nextSibling n))
  (set-text! [_ n s] (set! (.-data n) s))
  (set-attr! [_ el k v]
    (cond
      (prop? k) (when-not (= (unchecked-get el k) v) (unchecked-set el k v))
      (nil? v) (.removeAttribute el k)
      :else (.setAttribute el k v)))
  (listen! [_ el type f]
    (if (contains? non-bubbling type)
      (do (.addEventListener el type f)
          #(.removeEventListener el type f))
      (let [k (handler-key type)
            id (gensym)]
        (delegate! type)
        (unchecked-set el k (assoc (or (unchecked-get el k) {}) id f))
        #(unchecked-set el k (dissoc (unchecked-get el k) id)))))
  (event-data [_ e]
    (let [t (.-target e)]
      (cond-> {:type (.-type e)}
        (and t (some? (.-value t))) (assoc :value (.-value t))
        (and t (= "checkbox" (.-type t))) (assoc :checked (.-checked t))
        (.-key e) (assoc :key (.-key e))
        (and t (= "scroll" (.-type e))) (assoc :scroll-top (.-scrollTop t))
        (and t (= "submit" (.-type e)))
        (assoc :form (into {} (for [[k v] (es6-iterator-seq (.entries (js/FormData. t)))] [(keyword k) v]))))))
  (prevent-default! [_ e] (.preventDefault e)))

(defn dom [] (BrowserDom.))
