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

(declare start!)

(defn- open-ws! [{:keys [url token on-message on-open]}]
  (let [ws (js/WebSocket. (str (or url (default-url)) (when token (str "?resume=" token))))]
    (set! (.-binaryType ws) "arraybuffer")
    (set! (.-onopen ws) on-open)
    (set! (.-onmessage ws) on-message)
    ws))

(defn start!
  "Mount ctor into container and connect. Returns a handle with :peer.
  opts: :url; :container; :router? (true); :resume? (true: continue a server
  render left in the page); :connect :eager (default) or :lazy (open the
  websocket when the client first has something to send)."
  [ctor & {:keys [url container router? seed resume? connect] :or {router? true resume? true}}]
  (when router? (router/install!))
  (let [container (or container (.getElementById js/document "app") (.-body js/document))
        resume (when resume? (resume-data))
        static? (boolean (and resume (:static (:data resume))))
        lazy? (or static? (= connect :lazy))
        hooks (mount/renderer (dom/dom) container)
        up (if resume
             (let [st (codec/import-state (:up (:data resume)))] {:encode #(codec/encode st %)})
             (codec/link))
        down (if resume (codec/import-state (:down (:data resume))) (codec/decoder-state))
        ws (volatile! nil)
        outbox #js []
        scheduled (volatile! false)
        peer-ref (volatile! nil)
        handle (volatile! nil)
        on-message (fn [e]
                     (rt/receive! @peer-ref (codec/decode down (js/Uint8Array. (.-data e))))
                     (when-let [h (:schedule! @handle)] (h)))
        on-open (fn []
                  (doseq [bs (array-seq outbox)] (.send @ws bs))
                  (set! (.-length outbox) 0)
                  ;; tests and tooling can wait for this
                  (.setAttribute (.-documentElement js/document) "data-curve" "ready"))
        ensure-ws! (fn [] (or @ws (vreset! ws (open-ws! {:url url :token (:token resume)
                                                         :on-message on-message :on-open on-open}))))
        go-live! (fn []
                   ;; a statically built page has no session behind it: when it
                   ;; first needs the server, remount live, keeping local state
                   (let [root (first (filter #(nil? (:parent %)) (rt/frames @peer-ref)))
                         seed (when root (rt/snapshot root))]
                     (when root (rt/unmount-frame! root))
                     (set! (.-innerHTML container) "")
                     (vreset! handle (start! ctor :url url :container container :router? false
                                             :resume? false :seed seed))))
        send! (fn [bs]
                (cond
                  static? (when-not (:live @handle) (vswap! handle assoc :live true) (go-live!))
                  :else (let [w (ensure-ws!)]
                          (if (= 1 (.-readyState w)) (.send w bs) (.push outbox bs)))))
        flush! (fn []
                 (vreset! scheduled false)
                 (let [peer @peer-ref]
                   (rt/run! peer)
                   (when-let [m (rt/take-message! peer)]
                     ;; acks alone do not justify opening a connection
                     (when (or @ws (seq (dissoc m :ack)))
                       (send! ((:encode up) m))))))
        schedule! (fn [] (when-not @scheduled
                           (vreset! scheduled true)
                           (js/queueMicrotask flush!)))
        peer (apply rt/peer :client (mapcat identity (assoc (dissoc hooks :mounter) :on-schedule schedule!)))]
    (vreset! peer-ref peer)
    (vreset! handle {:schedule! schedule!})
    (when-not lazy? (ensure-ws!))
    (when static? (.setAttribute (.-documentElement js/document) "data-curve" "ready"))
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
    (when-let [w @ws] (set! (.-onclose w) nil) (.close w))
    (let [root (first (filter #(nil? (:parent %)) (rt/frames peer)))
          seed (when root (rt/snapshot root))
          name (:name (:ctor root))
          container (or (:container opts) (.getElementById js/document "app") (.-body js/document))]
      (when root (rt/unmount-frame! root))
      (set! (.-innerHTML container) "")
      (reset! current (apply start! (or (rt/ctor-by-name name) (:ctor @current))
                             (mapcat identity (assoc opts :seed seed)))))))
