(ns curve.client
  "Browser entry: connects a client peer to the server over a binary
  websocket and renders into a container.

  The connection is resilient (design §8.3, §8.4): frames are numbered and
  acknowledged (curve.transport); a dropped connection is retried with
  backoff and resumes the same session, resending what was lost; if the
  session is gone, or the node asks us to move, the app remounts on a new
  session keeping its local state; if the server runs a different version
  of the program, local state is parked in sessionStorage and the page
  reloads to pick up the new code."
  (:refer-clojure :exclude [run!])
  (:require [curve.codec :as codec]
            [curve.dom :as dom]
            [curve.mount :as mount]
            [curve.router :as router]
            [curve.runtime :as rt]
            [curve.transport :as transport]))

(defn- default-url []
  (let [l (.-location js/window)]
    (str (if (= "https:" (.-protocol l)) "wss://" "ws://") (.-host l) "/curve")))

(defn- base64->bytes [s]
  (let [bin (js/atob s)
        out (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset out i (.charCodeAt bin i)))
    out))

(defn- bytes->base64 [arr]
  (js/btoa (apply str (map #(js/String.fromCharCode %) (array-seq arr)))))

(defn- resume-data
  "The state a server render left in the page, if any (used once)."
  []
  (when-let [c (.-__CURVE__ js/window)]
    (set! (.-__CURVE__ js/window) nil)
    {:token (.-token c)
     :data (second (codec/decode-delta-blob (base64->bytes (.-state c))))}))

;; ---- local state parked across a page reload (version change)

(defn- seed-key [ctor] (str "curve-seed:" (:name ctor)))

(defn- portable-seed [snap]
  (-> snap
      (update :state (fn [m] (into {} (keep (fn [[k v]]
                                              (cond (instance? cljs.core/Atom v) [k {:curve.runtime/atom @v}]
                                                    (fn? v) nil
                                                    :else [k v])))
                                        m)))
      (update :kids (fn [m] (into {} (map (fn [[k v]] [k (portable-seed v)])) m)))))

(defn- park-seed! [ctor snap]
  (try (.setItem js/sessionStorage (seed-key ctor)
                 (bytes->base64 (codec/encode-delta-blob [:v (portable-seed snap)])))
       (catch :default _ nil)))

(defn- take-parked-seed [ctor]
  (try (when-let [s (.getItem js/sessionStorage (seed-key ctor))]
         (.removeItem js/sessionStorage (seed-key ctor))
         (second (codec/decode-delta-blob (base64->bytes s))))
       (catch :default _ nil)))

(defn- root-of [peer] (first (filter #(nil? (:parent %)) (rt/frames peer))))

(declare start!)

(defn start!
  "Mount ctor into container and connect. Returns a handle atom.
  opts: :url; :container; :router? (true); :resume? (true: continue a server
  render left in the page); :connect :eager (default) or :lazy (open the
  websocket when the client first has something to send)."
  [ctor & {:keys [url container router? seed resume? connect] :or {router? true resume? true}}]
  (when router? (router/install!))
  (let [container (or container (.getElementById js/document "app") (.-body js/document))
        resume (when resume? (resume-data))
        data (:data resume)
        static? (boolean (:static data))
        lazy? (or static? (= connect :lazy))
        seed (or seed (when-not resume (take-parked-seed ctor)))
        hooks (mount/renderer (dom/dom) container)
        up (if resume
             (let [st (codec/import-state (:up data))] {:encode #(codec/encode st %)})
             (codec/link))
        down (if resume (codec/import-state (:down data)) (codec/decoder-state))
        tr (if resume (transport/restore (:transport data)) (transport/state))
        session-token (volatile! (:session data))
        ws (volatile! nil)
        closing (volatile! false)
        retries (volatile! 0)
        outbox #js []
        scheduled (volatile! false)
        peer-ref (volatile! nil)
        restarted (volatile! false)
        restart! (fn []
                   ;; continue on a fresh session, keeping local state
                   (when-not @restarted
                     (vreset! restarted true)
                     (vreset! closing true)
                     (when-let [w @ws] (.close w))
                     (let [root (root-of @peer-ref)
                           snap (when root (rt/snapshot root))]
                       (when root (rt/unmount-frame! root))
                       (set! (.-innerHTML container) "")
                       (start! ctor :url url :container container :router? false :resume? false :seed snap))))
        on-control (fn [{:keys [session version drain]}]
                     (cond
                       drain (restart!)
                       (and version (not= version (rt/tree-version ctor))
                            ;; reload at most once per server version
                            (not= (str version) (try (.getItem js/sessionStorage "curve-reloaded-for") (catch :default _ nil))))
                       (do (js/console.warn "curve: the server runs a different version of this app; reloading"
                                            version (rt/tree-version ctor))
                           (vreset! closing true)
                           (try (.setItem js/sessionStorage "curve-reloaded-for" (str version)) (catch :default _ nil))
                           (when-let [root (root-of @peer-ref)] (park-seed! ctor (rt/snapshot root)))
                           (.reload (.-location js/window)))
                       (and session @session-token (not= session @session-token)) (restart!)
                       session (vreset! session-token session)))
        schedule-ref (volatile! nil)
        on-message (fn [e]
                     (let [{:keys [data control]} (transport/receive tr (js/Uint8Array. (.-data e)))]
                       (when control (on-control control))
                       (when data
                         (rt/receive! @peer-ref (codec/decode down data))
                         (@schedule-ref))))
        open! (fn open! [params]
                (let [w (js/WebSocket. (str (or url (default-url)) params))]
                  (set! (.-binaryType w) "arraybuffer")
                  (set! (.-onmessage w) on-message)
                  (set! (.-onopen w)
                        (fn []
                          (vreset! retries 0)
                          ;; after a reconnect: what the server has not acknowledged
                          (doseq [f (transport/resend tr)] (.send w f))
                          (doseq [bs (array-seq outbox)] (.send w bs))
                          (set! (.-length outbox) 0)
                          (.setAttribute (.-documentElement js/document) "data-curve" "ready")))
                  (set! (.-onclose w)
                        (fn []
                          (when-not @closing
                            (let [delay (min 5000 (* 200 (js/Math.pow 2 @retries)))]
                              (vswap! retries inc)
                              (js/setTimeout
                                #(vreset! ws (open! (str "?session=" @session-token "&seen=" (transport/seen tr))))
                                delay)))))
                  w))
        ensure-ws! (fn [] (or @ws (vreset! ws (open! (when (:token resume) (str "?resume=" (:token resume)))))))
        send! (fn [bs]
                (if static?
                  (restart!)
                  (let [w (ensure-ws!)
                        f (transport/data-frame tr bs)]
                    (if (= 1 (.-readyState w)) (.send w f) (.push outbox f)))))
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
    (vreset! schedule-ref schedule!)
    (vreset! peer-ref peer)
    (when-not lazy? (ensure-ws!))
    (when static? (.setAttribute (.-documentElement js/document) "data-curve" "ready"))
    ;; a background tab's session may hibernate; coming back wakes it
    (.addEventListener js/document "visibilitychange"
                       (fn [] (when (and (= "visible" (.-visibilityState js/document)) @ws
                                         (= 1 (.-readyState @ws)))
                                (.send @ws (transport/control-frame tr {:ping true})))))
    (if resume
      ;; replace the server-rendered DOM within this task: the browser does
      ;; not paint in between, and the values come from the snapshot
      (do (.replaceChildren container)
          (rt/resume! peer ctor (:peer data))
          (rt/run! peer))
      (do (when seed (vreset! (:root-seed peer) seed))
          (rt/mount-root! peer ctor)
          (vreset! (:root-seed peer) nil)))
    (schedule!)
    {:peer peer :ws ws :ctor ctor :close! (fn [] (vreset! closing true) (some-> @ws .close))
     :opts {:url url :container container :router? false}}))

(defonce ^:private current (atom nil))

(defn run!
  "start! and remember the app so reload! can restart it."
  [ctor & {:as opts}]
  (reset! current (apply start! ctor (mapcat identity opts))))

(defn reload!
  "Development: restart the app with freshly loaded code, carrying client
  local state across by stable node ids. Hook it to shadow-cljs :after-load."
  []
  (when-let [{:keys [peer opts close!]} @current]
    (close!)
    (let [root (root-of peer)
          seed (when root (rt/snapshot root))
          name (:name (:ctor root))
          container (or (:container opts) (.getElementById js/document "app") (.-body js/document))]
      (when root (rt/unmount-frame! root))
      (set! (.-innerHTML container) "")
      (reset! current (apply start! (or (rt/ctor-by-name name) (:ctor @current))
                             (mapcat identity (assoc opts :seed seed :resume? false)))))))
