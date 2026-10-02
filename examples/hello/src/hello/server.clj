(ns hello.server
  (:require [curve.server :as cs]
            [hello.app :as app]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]))

(def ws (cs/websocket-handler app/App :on-error #(.printStackTrace ^Throwable %)))

(defn handler [req]
  (case (:uri req)
    "/curve" (ws req)
    "/" (-> (resp/response (cs/page {:title "Curve: hello" :script "/js/main.js"}))
            (resp/content-type "text/html; charset=utf-8"))
    (or (resp/resource-response (:uri req) {:root "public"})
        (resp/not-found "not found"))))

(defn -main [& [port]]
  (let [port (Integer/parseInt (or port "8080"))]
    (jetty/run-jetty #'handler {:port port :join? false})
    (println "hello: http://localhost:" port)))
