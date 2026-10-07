(ns hello.server
  (:require [hypercurve.server :as cs]
            [hypercurve.ssr :as ssr]
            [hello.app :as app]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as resp]))

(def ws (cs/websocket-handler app/App :on-error #(.printStackTrace ^Throwable %)))

(defn handler [req]
  (case (:uri req)
    "/hypercurve" (ws req)
    "/" (-> (resp/response (ssr/page (ssr/render app/App [] :url "/")
                                     {:title "Hypercurve: hello" :script "/js/main.js"}))
            (resp/content-type "text/html; charset=utf-8"))
    (or (resp/resource-response (:uri req) {:root "public"})
        (resp/not-found "not found"))))

(defn -main [& [port]]
  (let [port (Integer/parseInt (or port "8080"))]
    (jetty/run-jetty #'handler {:port port :join? false})
    (println "hello: http://localhost:" port)))
