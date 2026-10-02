(ns todomvc.server
  (:require [curve.server :as cs]
            [todomvc.app :as app]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]))

(def ws (cs/websocket-handler app/App))

(defn handler [req]
  (case (:uri req)
    "/curve" (ws req)
    "/" (-> (resp/response (cs/page {:title "Curve • TodoMVC" :script "/js/main.js"}))
            (resp/content-type "text/html; charset=utf-8"))
    (or (resp/resource-response (:uri req) {:root "public"}) (resp/not-found "not found"))))

(defn -main [& [port]]
  (jetty/run-jetty #'handler {:port (Integer/parseInt (or port "8092")) :join? false}))
