(ns sqlite-table.server
  (:require [hypercurve.server :as cs]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]
            [sqlite-table.app :as app]
            [sqlite-table.db :as db]))

(def ws (cs/websocket-handler app/App :on-error #(.printStackTrace ^Throwable %)))

(defn handler [req]
  (case (:uri req)
    "/hypercurve" (ws req)
    "/" (-> (resp/response (cs/page {:title "Hypercurve • products" :script "/js/main.js"}))
            (resp/content-type "text/html; charset=utf-8"))
    (or (resp/resource-response (:uri req) {:root "public"}) (resp/not-found "not found"))))

(defn -main [& [port path]]
  (db/init! (or path "products.db"))
  (jetty/run-jetty #'handler {:port (Integer/parseInt (or port "8093")) :join? false}))
