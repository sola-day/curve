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

(defn- base64->bytes [s]
  (let [bin (js/atob s)
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    out))

(defn- resume-data
  "The state a server render left in the page, if any (used once)."
  []
  (when-let [c (.-__CURVE__ js/window)]
    (set! (.-__CURVE__ js/window) nil)
    {:token (.-token c)
     :data (second (codec/decode-delta-blob (base64->bytes (.-state c))))}))

(defn start!
  "Mount ctor into container and connect. Returns a handle with :peer."
  [ctor & {:keys [url container router? seed resume?] :or {router? true resume? true}}]
  (when router? (router/install!))
  (let [container (or container (.getElementById js/document "app") (.-body js/document))
        resume (when resume? (resume-data))
        hooks (mount/renderer (dom/dom) container)
        up (if resume
             (let [st (codec/import-state (:up (:data resume)))] {:encode #(codec/encode st %)})
             (codec/link))
        down (if resume (codec/import-state (:down (:data resume))) (codec/decoder-state))
        ws (js/WebSocket. (str (or url (default-url)) (when resume (str "?resume=" (:token resume)))))
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
    (set! (.-onopen ws) (fn []
                          (doseq [bs (array-seq outbox)] (.send ws bs))
                          (set! (.-length outbox) 0)
                          ;; tests and tooling can wait for this
                          (.setAttribute (.-documentElement js/document) "data-curve" "ready")))
    (set! (.-onmessage ws) (fn [e]
                             (rt/receive! peer (codec/decode down (js/Uint8Array. (.-data e))))
                             (schedule!)))
    (if resume
      ;; replace the server-rendered DOM within this task: the browser does
      ;; not paint in between, and the values come from the snapshot
      (do (.replaceChildren container)
          (rt/resume! peer ctor (:peer (:data resume)))
          (rt/run! peer))
      (do (when seed (vreset! (:root-seed peer) seed))
          (rt/mount-root! peer ctor)
          (vreset! (:root-seed peer) nil)))
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
