(ns curve.ssr
  "Server-side rendering with resumption (design §7).

  render runs the app the way a browser would: a headless client peer with
  the real binary protocol against a real session. When the page is stable
  (or a timeout passes) it returns the HTML and a snapshot of the client
  peer. The session stays alive, detached, under a token; the browser
  restores the snapshot and attaches to the same session, so nothing is
  queried twice and only later changes cross the wire.

  render-stream does the same but yields HTML in chunks: the page with
  suspense fallbacks as soon as it is first stable, then each suspense
  region as it resolves, then the resume state."
  (:require [clojure.string :as str]
            [curve.codec :as codec]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.router :as router]
            [curve.runtime :as rt]
            [curve.session :as session]
            [curve.shared :as shared])
  (:import [java.util Base64]
           [java.util.concurrent ConcurrentLinkedQueue Executors LinkedBlockingQueue ScheduledExecutorService TimeUnit]))

(defonce ^:private detached (atom {}))
(defonce ^:private ^ScheduledExecutorService reaper (Executors/newSingleThreadScheduledExecutor))

(defn- new-token [] (str (java.util.UUID/randomUUID)))

(defn peek-detached [token] (get @detached token))

(defn take-detached!
  "The detached session for token, removed from the registry (resume once)."
  [token]
  (let [[old _] (swap-vals! detached dissoc token)]
    (get old token)))

(defn- keep-detached! [s grace-ms]
  (let [token (new-token)]
    (session/detach! s)
    (swap! detached assoc token s)
    (.schedule reaper ^Runnable (fn [] (when-let [s (take-detached! token)] (session/close! s)))
               (long grace-ms) TimeUnit/MILLISECONDS)
    token))

(defn- start [ctor args url]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        posted (ConcurrentLinkedQueue.)
        inbox (LinkedBlockingQueue.)
        s (session/start! ctor args {:send! #(.put inbox %)})
        up (codec/link)
        down (codec/decoder-state)
        location (atom {:path "/" :query ""})
        client (apply rt/peer :client (mapcat identity (merge (dissoc hooks :mounter)
                                                              {:post! #(.add posted %)})))]
    (binding [router/*location* location]
      (when url (router/set-location! url))
      (rt/mount-root! client ctor))
    {:root root :posted posted :inbox inbox :session s :up up :down down :client client :location location}))

(defn- step!
  "Run the client and exchange messages once. True if anything happened."
  [{:keys [posted inbox session up down client location]} wait-ms]
  (binding [router/*location* location]
    (loop [] (when-let [g (.poll ^ConcurrentLinkedQueue posted)] (g) (recur)))
    (rt/run! client)
    (let [sent (when-let [m (rt/take-message! client)]
                 (session/receive! session ((:encode up) m))
                 true)
          got (when-let [bs (.poll inbox wait-ms TimeUnit/MILLISECONDS)]
                (rt/receive! client (codec/decode down bs))
                (loop [] (when-let [bs (.poll inbox)] (rt/receive! client (codec/decode down bs)) (recur)))
                (rt/run! client)
                true)]
      (boolean (or sent got (not (.isEmpty ^ConcurrentLinkedQueue posted)))))))

(defn- settle! [st quiet-ms deadline]
  (loop [quiet 0]
    (when (and (< quiet 2) (< (System/currentTimeMillis) deadline))
      (if (step! st quiet-ms) (recur 0) (do (shared/await-idle) (recur (inc quiet)))))))

(defn- live-server?
  "Does the server still have live inputs after the render (watched refs,
  shared values, effects) or did it hand the client a server fn?"
  [session client]
  (or (session/call session
                    (fn [] (boolean (some (fn [f] (some (fn [nd] (and (= :server (rt/node-site f nd))
                                                                       (contains? #{:watch :shared :effect} (:op nd))))
                                                        (:nodes (:ctor f))))
                                          (rt/frames (:peer session))))))
      (boolean (some (fn [f] (some #(rt/remote-fn? (rt/value f %)) (range (count (:nodes (:ctor f))))))
                     (rt/frames client)))))

(defn- client-logic?
  "Does the page need JS at all: handlers, client state or effects?"
  [client]
  (boolean (some (fn [f] (or (some #(contains? #{:event :spread :foreign} (first %)) (:holes (:render (:ctor f))))
                             (some (fn [nd] (and (= :client (rt/node-site f nd))
                                                 (contains? #{:watch :effect} (:op nd))))
                                   (:nodes (:ctor f)))))
                 (rt/frames client))))

(defn- finish!
  "Exchange final acks, snapshot the client and detach (or close) the session."
  [{:keys [session up down client] :as st} grace-ms]
  (step! st 5)
  (session/call session (fn [] nil))
  (let [live? (live-server? session client)
        tier (cond live? :live (client-logic? client) :client :else :html)
        state {:peer (rt/resume-snapshot client)
               :down (codec/export-state down)
               :up (codec/export-state (:enc up))
               :static (not live?)}
        token (if live? (keep-detached! session grace-ms) (do (session/close! session) nil))]
    {:token token :tier tier
     :state (.encodeToString (Base64/getEncoder) ^bytes (codec/encode-delta-blob [:v state]))}))

(defn- with-token
  "Forms that post without JS need the session token."
  [html token]
  (if token (str/replace html "/curve/action?" (str "/curve/action?token=" token "&amp;")) html))

(defn- body-html [root] (h/html root))

(defn render
  "Render (ctor args...) at url. Returns {:html :token :state}.
  opts: :timeout-ms (default 2000), :quiet-ms (20), :grace-ms (30000)."
  [ctor args & {:keys [url timeout-ms quiet-ms grace-ms] :or {timeout-ms 2000 quiet-ms 20 grace-ms 30000}}]
  (let [st (start ctor args url)]
    (settle! st quiet-ms (+ (System/currentTimeMillis) timeout-ms))
    (let [r (finish! st grace-ms)]
      (assoc r :html (with-token (body-html (:root st)) (:token r))))))

(defn- esc-js [s] (str/replace (str s) "</" "<\\/"))

(defn resume-script [{:keys [token state]}]
  (str "<script>window.__CURVE__={token:" (if token (str "\"" token "\"") "null") ",state:\"" (esc-js state) "\"};</script>"))

(defn page
  "A full HTML page around a render result. A page with no client logic
  (tier :html) gets no script at all."
  [{:keys [html tier] :as result} {:keys [title script head]}]
  (str "<!doctype html><html><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" title "</title>" head "</head><body><div id=\"app\">" html "</div>"
       (when-not (= tier :html)
         (str (resume-script result) "<script src=\"" script "\"></script>"))
       "</body></html>"))

;; ---------------------------------------------------------------- streaming

(def swap-script
  "<script>function __curveSwap(i){var t=document.querySelector('template[data-curve-chunk=\"'+i+'\"]'),
s=document.querySelector('[data-curve-s=\"'+i+'\"]');if(t&&s){s.replaceWith(t.content.firstElementChild);t.remove();}}</script>")

(defn- suspense-nodes [root] (h/query-all root ".curve-suspense"))

(defn render-stream
  "Like render, as a lazy seq of HTML strings for a streaming response:
  shell with fallbacks first, then resolved suspense regions, then state."
  [ctor args {:keys [title script head]} & {:keys [url timeout-ms quiet-ms grace-ms first-ms]
                                             :or {timeout-ms 5000 quiet-ms 20 grace-ms 30000 first-ms 200}}]
  (let [st (start ctor args url)
        deadline (+ (System/currentTimeMillis) timeout-ms)
        emitted (atom {})
        tag! (fn []
               (doseq [[i n] (map-indexed vector (suspense-nodes (:root st)))]
                 (vswap! (:attrs n) assoc "data-curve-s" (str i))))]
    (lazy-seq
      (settle! st quiet-ms (min deadline (+ (System/currentTimeMillis) first-ms)))
      (tag!)
      (doseq [n (suspense-nodes (:root st))] (swap! emitted assoc (get @(:attrs n) "data-curve-s") (h/html n)))
      (cons (str "<!doctype html><html><head><meta charset=\"utf-8\"><title>" title "</title>" head
                 swap-script "</head><body><div id=\"app\">" (body-html (:root st)) "</div>")
            (letfn [(more []
                      (lazy-seq
                        (if (< (System/currentTimeMillis) deadline)
                          (do (settle! st quiet-ms (min deadline (+ (System/currentTimeMillis) 50)))
                              (tag!)
                              (let [changed (for [n (suspense-nodes (:root st))
                                                  :let [i (get @(:attrs n) "data-curve-s") html (h/html n)]
                                                  :when (not= html (get @emitted i))]
                                              (do (swap! emitted assoc i html)
                                                  (str "<template data-curve-chunk=\"" i "\">" html "</template>"
                                                       "<script>__curveSwap(" i ")</script>")))
                                    chunks (doall changed)
                                    pending? (some #(re-find #"display:none" (h/html %)) (suspense-nodes (:root st)))]
                                (if (or (seq chunks) pending?)
                                  (concat chunks (more))
                                  (final))))
                          (final))))
                    (final []
                      (let [r (finish! st grace-ms)]
                        [(str (resume-script r) "<script src=\"" script "\"></script></body></html>")]))]
              (more))))))
