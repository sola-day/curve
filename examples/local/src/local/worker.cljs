(ns local.worker
  (:require [curve.local :as local]
            [local.app :as app]))

(defn ^:export init [] (local/serve! app/App))
