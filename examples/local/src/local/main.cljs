(ns local.main
  (:require [hypercurve.client :as client]
            [hypercurve.local :as local]
            [local.app :as app]))

(defn ^:export init [] (client/start! app/App :socket (local/socket "/js/worker.js") :router? false))
