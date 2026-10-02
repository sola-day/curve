(ns curve.client
  "Browser entry: connects a client peer to the server over a binary
  websocket and renders into a container."
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
  [ctor & {:keys [url container router?] :or {router? true}}]
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
    (rt/mount-root! peer ctor)
    (schedule!)
    {:peer peer :ws ws}))
