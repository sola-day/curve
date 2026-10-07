(ns hypercurve.local
  "A server site running in a Web Worker (design §2, optional module): the
  same program, the same protocol, no network. Useful for offline-capable
  apps and as the local half of a local-first architecture built on top.

  Main thread:  (client/start! App :socket (local/socket \"/js/worker.js\"))
  Worker:       (local/serve! App)
  The build needs :compiler-options {:hypercurve/local-server true} so server
  sites are compiled into the browser bundle."
  (:require [hypercurve.codec :as codec]
            [hypercurve.runtime :as rt]
            [hypercurve.transport :as transport]))

(defn socket
  "A WebSocket-shaped connection to a worker running serve!."
  [worker-url]
  (fn [_params]
    (let [w (js/Worker. worker-url)
          s #js {:readyState 0 :binaryType "arraybuffer"}]
      (set! (.-send s) (fn [bytes] (.postMessage w bytes)))
      (set! (.-close s) (fn [] (.terminate w)))
      (set! (.-onmessage w)
            (fn [e]
              (if (= "ready" (.-data e))
                (do (set! (.-readyState s) 1)
                    (when-let [f (.-onopen s)] (f)))
                (when-let [f (.-onmessage s)] (f #js {:data (.-data e)})))))
      s)))

(defn serve!
  "Run ctor as the server peer of the page that started this worker."
  [ctor & args]
  (let [tr (transport/state)
        out (codec/link)
        in (codec/decoder-state)
        scheduled (volatile! false)
        peer-ref (volatile! nil)
        post (fn [bytes] (.postMessage js/self bytes))
        flush! (fn []
                 (vreset! scheduled false)
                 (rt/run! @peer-ref)
                 (when-let [m (rt/take-message! @peer-ref)]
                   (post (transport/data-frame tr ((:encode out) m)))))
        schedule! (fn [] (when-not @scheduled (vreset! scheduled true) (js/queueMicrotask flush!)))
        peer (rt/peer :server :on-schedule schedule!)]
    (vreset! peer-ref peer)
    (set! (.-onmessage js/self)
          (fn [e]
            (let [{:keys [data]} (transport/receive tr (js/Uint8Array. (.-data e)))]
              (when data (rt/receive! peer (codec/decode in data)) (schedule!)))))
    (apply rt/mount-root! peer ctor args)
    (post "ready")
    (post (transport/control-frame tr {:session "local" :version (rt/tree-version ctor)}))
    (schedule!)))
