(ns curve.canvas
  "Rendering to a canvas (design §16.2). The scene graph is the headless
  DOM tree (so the mounter, templates and keyed updates are the same);
  every change marks the scene dirty and it is redrawn once per frame.

  Shapes: [:g {:x :y}] groups and translates; [:rect {:x :y :w :h :fill}];
  [:circle {:cx :cy :r :fill}]; [:line {:x1 :y1 :x2 :y2 :stroke}];
  [:label {:x :y :fill} text]. Pointer events are hit-tested in reverse
  paint order and dispatched to the shape (bubbling through groups)."
  (:refer-clojure :exclude [num])
  (:require [curve.dom-api :as api]
            [curve.headless :as h]))

(defprotocol Ctx
  (save! [c]) (restore! [c]) (translate! [c x y])
  (fill-rect! [c x y w h color]) (fill-circle! [c x y r color])
  (stroke-line! [c x1 y1 x2 y2 color]) (fill-text! [c s x y color]) (clear! [c w h]))

(defn- num [n k d] (let [v (get @(:attrs n) k)] (if v #?(:clj (Double/parseDouble v) :cljs (js/parseFloat v)) d)))
(defn- attr [n k d] (get @(:attrs n) k d))

(defn paint!
  "Draw the scene under root."
  [ctx root w h]
  (clear! ctx w h)
  ((fn walk [n]
     (when (= :root (:kind n)) (run! walk (h/children n)))
     (when (= :element (:kind n))
       (case (:tag n)
         "g" (do (save! ctx) (translate! ctx (num n "x" 0) (num n "y" 0))
                 (run! walk (h/children n)) (restore! ctx))
         "rect" (fill-rect! ctx (num n "x" 0) (num n "y" 0) (num n "w" 0) (num n "h" 0) (attr n "fill" "black"))
         "circle" (fill-circle! ctx (num n "cx" 0) (num n "cy" 0) (num n "r" 0) (attr n "fill" "black"))
         "line" (stroke-line! ctx (num n "x1" 0) (num n "y1" 0) (num n "x2" 0) (num n "y2" 0) (attr n "stroke" "black"))
         "label" (fill-text! ctx (h/text-content n) (num n "x" 0) (num n "y" 0) (attr n "fill" "black"))
         (run! walk (h/children n)))))
   root))

(defn- contains-point? [n x y]
  (case (:tag n)
    "rect" (and (<= (num n "x" 0) x (+ (num n "x" 0) (num n "w" 0))) (<= (num n "y" 0) y (+ (num n "y" 0) (num n "h" 0))))
    "circle" (let [dx (- x (num n "cx" 0)) dy (- y (num n "cy" 0)) r (num n "r" 0)] (<= (+ (* dx dx) (* dy dy)) (* r r)))
    false))

(defn hit
  "The topmost shape at (x, y), or nil."
  [root x y]
  (let [found (volatile! nil)]
    ((fn walk [n ox oy]
       (when (= :root (:kind n)) (doseq [c (h/children n)] (walk c ox oy)))
       (when (= :element (:kind n))
         (if (= "g" (:tag n))
           (doseq [c (h/children n)] (walk c (+ ox (num n "x" 0)) (+ oy (num n "y" 0))))
           (do (when (contains-point? n (- x ox) (- y oy)) (vreset! found n))
               (doseq [c (h/children n)] (walk c ox oy))))))
     root 0 0)
    @found))

(defn dispatch!
  "A pointer event at canvas coordinates: fire it on the shape hit."
  [root type x y]
  (when-let [n (hit root x y)]
    (h/fire! n type {:x x :y y})
    n))

(deftype CanvasDom [inner on-change]
  api/Dom
  (instantiate [_ r] (api/instantiate inner r))
  (child-at [_ n i] (api/child-at inner n i))
  (text-node [_ s] (api/text-node inner s))
  (replace-node! [_ o n] (api/replace-node! inner o n) (on-change))
  (insert-before! [_ p n r] (api/insert-before! inner p n r) (on-change))
  (remove-node! [_ n] (api/remove-node! inner n) (on-change))
  (parent-of [_ n] (api/parent-of inner n))
  (next-of [_ n] (api/next-of inner n))
  (set-text! [_ n s] (api/set-text! inner n s) (on-change))
  (set-attr! [_ el k v] (api/set-attr! inner el k v) (on-change))
  (listen! [_ el t f] (api/listen! inner el t f))
  (event-data [_ e] (select-keys e [:type :x :y]))
  (prevent-default! [_ _] nil))

(defn scene
  "A scene root plus a Dom that schedules repaints through schedule-paint!
  (requestAnimationFrame in a browser; anything in tests)."
  [schedule-paint!]
  {:root (h/root) :dom (CanvasDom. (h/dom) schedule-paint!)})

#?(:cljs
   (deftype Ctx2D [^js c]
     Ctx
     (save! [_] (.save c)) (restore! [_] (.restore c)) (translate! [_ x y] (.translate c x y))
     (fill-rect! [_ x y w h color] (set! (.-fillStyle c) color) (.fillRect c x y w h))
     (fill-circle! [_ x y r color] (set! (.-fillStyle c) color) (.beginPath c) (.arc c x y r 0 (* 2 js/Math.PI)) (.fill c))
     (stroke-line! [_ x1 y1 x2 y2 color] (set! (.-strokeStyle c) color) (.beginPath c) (.moveTo c x1 y1) (.lineTo c x2 y2) (.stroke c))
     (fill-text! [_ s x y color] (set! (.-fillStyle c) color) (.fillText c s x y))
     (clear! [_ w h] (.clearRect c 0 0 w h))))

#?(:cljs
   (defn attach
     "Render a scene into canvas el: repaint once per animation frame after
     changes; forward clicks and pointer events. Returns {:root :dom} for
     curve.client/start! (:dom and :container)."
     [^js el]
     (let [ctx (Ctx2D. (.getContext el "2d"))
           pending (volatile! false)
           sc (volatile! nil)
           paint (fn [] (vreset! pending false)
                   (paint! ctx (:root @sc) (.-width el) (.-height el)))
           s (scene (fn [] (when-not @pending (vreset! pending true) (js/requestAnimationFrame paint))))]
       (vreset! sc s)
       (doseq [t ["click" "pointerdown" "pointerup" "pointermove"]]
         (.addEventListener el t (fn [e] (let [r (.getBoundingClientRect el)]
                                           (dispatch! (:root s) t (- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r)))))))
       s)))
