(ns curve.client
  "Browser entry: connects a client peer to the server over a binary
  websocket and renders into a container."
  (:refer-clojure :exclude [run!])
  (:require [curve.codec :as codec]
            [curve.dom :as dom]
            [curve.mount :as mount]
            [curve.router :as router]
            [curve.runtime :as rt]))

(defn- default-url []
  (let [l (.-location js/window)]
    (str (if (= "https:" (.-protocol l)) "wss://" "ws://") (.-host l) "/curve")))

(defn start!
  "Mount ctor into container and connect. Returns a handle with :peer."
  [ctor & {:keys [url container router? seed] :or {router? true}}]
  (when router? (router/install!))
  (let [container (or container (.getElementById js/document "app") (.-body js/document))
        hooks (mount/renderer (dom/dom) container)
        up (codec/link)
        down (codec/decoder-state)
        ws (js/WebSocket. (or url (default-url)))
        outbox #js []
        scheduled (volatile! false)
        peer-ref (volatile! nil)
        send! (fn [bs] (if (= 1 (.-readyState ws)) (.send ws bs) (.push outbox bs)))
        flush! (fn []
                 (vreset! scheduled false)
                 (let [peer @peer-ref]
                   (rt/run! peer)
                   (when-let [m (rt/take-message! peer)]
                     (send! ((:encode up) m)))))
        schedule! (fn [] (when-not @scheduled
                           (vreset! scheduled true)
                           (js/queueMicrotask flush!)))
        peer (apply rt/peer :client (mapcat identity (assoc (dissoc hooks :mounter) :on-schedule schedule!)))]
    (vreset! peer-ref peer)
    (set! (.-binaryType ws) "arraybuffer")
    (set! (.-onopen ws) (fn [] (doseq [bs (array-seq outbox)] (.send ws bs)) (set! (.-length outbox) 0)))
    (set! (.-onmessage ws) (fn [e]
                             (rt/receive! peer (codec/decode down (js/Uint8Array. (.-data e))))
                             (schedule!)))
    (when seed (vreset! (:root-seed peer) seed))
    (rt/mount-root! peer ctor)
    (vreset! (:root-seed peer) nil)
    (schedule!)
    {:peer peer :ws ws :ctor ctor :opts {:url url :container container :router? false}}))

(defonce ^:private current (atom nil))

(defn run!
  "start! and remember the app so reload! can restart it."
  [ctor & {:as opts}]
  (reset! current (apply start! ctor (mapcat identity opts))))

(defn reload!
  "Development: restart the app with freshly loaded code, carrying client
  local state across by stable node ids. Hook it to shadow-cljs :after-load."
  []
  (when-let [{:keys [peer ws opts]} @current]
    (let [root (first (filter #(nil? (:parent %)) (rt/frames peer)))
          seed (when root (rt/snapshot root))
          name (:name (:ctor root))
          container (or (:container opts) (.getElementById js/document "app") (.-body js/document))]
      (set! (.-onclose ws) nil)
      (.close ws)
      (when root (rt/unmount-frame! root))
      (set! (.-innerHTML container) "")
      (reset! current (apply start! (or (rt/ctor-by-name name) (:ctor @current))
                             (mapcat identity (assoc opts :seed seed)))))))
