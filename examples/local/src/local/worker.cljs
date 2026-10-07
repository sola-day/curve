(ns local.worker
  (:require [hypercurve.local :as local]
            [local.app :as app]))

(defn ^:export init [] (local/serve! app/App))
