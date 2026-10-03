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
  [ctor & {:keys [args-fn on-error grace-ms hibernate-ms] :or {args-fn (constantly []) grace-ms 30000}}]
  (fn [request]
    (if (ws/upgrade-request? request)
      (let [s (atom nil)
            q (or (:query-string request) "")
            param #(second (re-find (re-pattern (str "(?:^|&)" % "=([^&]+)")) q))
            token (param "resume")
            session-token (param "session")
            seen (some-> (param "seen") parse-long)]
        {::ws/listener
         {:on-open (fn [socket]
                     (let [send! (fn [^bytes bs] (ws/send socket (ByteBuffer/wrap bs)))]
                       (cond
                         ;; continue the session a server render started
                         (and token ((requiring-resolve 'curve.ssr/peek-detached) token))
                         (let [resumed ((requiring-resolve 'curve.ssr/take-detached!) token)]
                           (reset! s resumed) (session/attach! resumed send!))
                         ;; a client coming back after a dropped connection
                         (and session-token (reset! s (session/reconnect! session-token (or seen 0) send!)))
                         nil
                         :else
                         (reset! s (session/start! ctor (args-fn request)
                                                   {:send! send!
                                                    :on-error on-error
                                                    :hibernate-ms hibernate-ms
                                                    :on-close #(try (ws/close socket) (catch Exception _ nil))})))))
          :on-message (fn [_ msg] (session/receive! @s (buffer->bytes msg)))
          :on-close (fn [_ _ _] (some-> @s (session/connection-lost! grace-ms)))
          :on-error (fn [_ e] (when on-error (on-error e)) (some-> @s session/close!))}})
      {:status 400 :body "websocket expected"})))

(defn- form-params [^String body]
  (into {} (for [kv (clojure.string/split (or body "") #"&") :when (seq kv)
                 :let [[k v] (clojure.string/split kv #"=" 2)]]
             [(keyword (java.net.URLDecoder/decode k "UTF-8")) (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn action-handler
  "POST /curve/action: a form submitted without JS. Calls the server fn
  the form's submit handler names, in the session the page was rendered
  with, then redirects back (design §7.5)."
  [request]
  (let [q (into {} (for [kv (clojure.string/split (or (:query-string request) "") #"&")
                         :let [[k v] (clojure.string/split kv #"=" 2)] :when k]
                     [k v]))
        s (when-let [t (get q "token")] ((requiring-resolve 'curve.ssr/peek-detached) t))
        frame (some-> (get q "frame") parse-long)
        node (some-> (get q "node") parse-long)]
    (if (and s frame node (= :post (:request-method request)))
      (let [form (form-params (slurp (:body request)))]
        (session/call s (fn [] ((requiring-resolve 'curve.runtime/receive!)
                                (:peer s) {:call [[0 frame node [{:type "submit" :form form}]]]})))
        {:status 303 :headers {"Location" (or (get-in request [:headers "referer"]) "/")}})
      {:status 400 :body "unknown or expired form"})))

(defn page
  "Minimal HTML shell that loads the client bundle."
  [{:keys [title script]}]
  (str "<!doctype html><html><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" title "</title></head><body><div id=\"app\"></div>"
       "<script src=\"" script "\"></script></body></html>"))
