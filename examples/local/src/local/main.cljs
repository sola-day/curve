(ns local.main
  (:require [curve.client :as client]
            [curve.local :as local]
            [local.app :as app]))

(defn ^:export init [] (client/start! app/App :socket (local/socket "/js/worker.js") :router? false))
