(ns curve.server
  "Ring integration: a websocket endpoint that runs one session per
  connection, plus a minimal page shell."
  (:require [curve.session :as session]
            [ring.websocket :as ws])
  (:import [java.nio ByteBuffer]))

(defn- buffer->bytes [msg]
  (if (instance? ByteBuffer msg)
    (let [^ByteBuffer b msg
          arr (byte-array (.remaining b))]
      (.get (.duplicate b) arr)
      arr)
    (throw (ex-info "curve.server: text frames are not accepted" {}))))

(defn websocket-handler
  "Ring handler that upgrades to a websocket and runs (ctor args...) as a
  session. args-fn: request -> root args (e.g. the authenticated user, read
  from the session cookie; never from client-controlled input)."
  [ctor & {:keys [args-fn on-error] :or {args-fn (constantly [])}}]
  (fn [request]
    (if (ws/upgrade-request? request)
      (let [s (atom nil)
            token (second (re-find #"(?:^|&)resume=([^&]+)" (or (:query-string request) "")))]
        {::ws/listener
         {:on-open (fn [socket]
                     (let [send! (fn [^bytes bs] (ws/send socket (ByteBuffer/wrap bs)))]
                       (if-let [resumed (when token ((requiring-resolve 'curve.ssr/take-detached!) token))]
                         ;; continue the session a server render started
                         (do (reset! s resumed) (session/attach! resumed send!))
                         (reset! s (session/start! ctor (args-fn request)
                                                   {:send! send!
                                                    :on-error on-error
                                                    :on-close #(try (ws/close socket) (catch Exception _ nil))})))))
          :on-message (fn [_ msg] (session/receive! @s (buffer->bytes msg)))
          :on-close (fn [_ _ _] (some-> @s session/close!))
          :on-error (fn [_ e] (when on-error (on-error e)) (some-> @s session/close!))}})
      {:status 400 :body "websocket expected"})))

(defn page
  "Minimal HTML shell that loads the client bundle."
  [{:keys [title script]}]
  (str "<!doctype html><html><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" title "</title></head><body><div id=\"app\"></div>"
       "<script src=\"" script "\"></script></body></html>"))
