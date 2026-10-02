(ns curve.static
  "Static builds (design §7.4): render each URL at build time and classify
  the page by what it still needs after rendering:
    :html   no client logic: plain HTML, no script at all
    :client client logic only: HTML + resume state + script, no server
    :live   live server inputs: needs a running server (reported)"
  (:require [clojure.java.io :as io]
            [curve.ssr :as ssr]))

(defn- file-for [out url]
  (let [path (if (or (= url "/") (.endsWith ^String url "/")) (str url "index.html") (str url "/index.html"))]
    (io/file out (subs path 1))))

(defn build!
  "Render ctor at each url into out. Returns [{:url :tier :file}]."
  [{:keys [ctor args urls out title script]}]
  (vec (for [url urls]
         (let [r (ssr/render ctor (or args []) :url url :grace-ms 1)
               f (file-for out url)]
           (io/make-parents f)
           (spit f (ssr/page r {:title title :script script}))
           {:url url :tier (:tier r) :file (str f)}))))
