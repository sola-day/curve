(ns showcase.server
  (:require [hypercurve.server :as cs]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]
            [showcase.app :as app]))

(def ws (cs/websocket-handler app/App))

(defn handler [req]
  (case (:uri req)
    "/hypercurve" (ws req)
    "/" (-> (resp/response (cs/page {:title "Hypercurve • showcase" :script "/js/main.js"}))
            (resp/content-type "text/html; charset=utf-8"))
    (or (resp/resource-response (:uri req) {:root "public"}) (resp/not-found "not found"))))

(defn -main [& [port]]
  (jetty/run-jetty #'handler {:port (Integer/parseInt (or port "8095")) :join? false}))
