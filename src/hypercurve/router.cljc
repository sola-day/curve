(ns hypercurve.router
  "Routing: the URL is a reactive input (design §3.8).

  Routes are data: [[\"/\" :home] [\"/products\" :products] [\"/products/:id\" :product]].
  (r/route routes) inside a reactive fn is the matched route, e.g.
  {:page :product :params {:id \"3\"} :query {...} :path \"/products/3\"}; it is
  computed on the client and read by the server like any other value.
  Links rendered with (href routes :product {:id 3}) navigate on the client
  without reloading; history and back/forward update the same value."
  (:require [clojure.string :as str]))

(defonce ^{:doc "The browser's location: {:path \"/a/b\" :query \"x=1\"}."} !location
  (atom {:path "/" :query ""}))

(def ^:dynamic *location*
  "The location atom routes read. On the JVM, bind it per render (SSR, tests)."
  !location)

(defn- split-path [p] (vec (remove str/blank? (str/split (or p "") #"/"))))

(defn- decode [s]
  #?(:clj (java.net.URLDecoder/decode (str s) "UTF-8") :cljs (js/decodeURIComponent s)))

(defn- encode [s]
  #?(:clj (str/replace (java.net.URLEncoder/encode (str s) "UTF-8") "+" "%20")
     :cljs (js/encodeURIComponent (str s))))

(defn parse-query [q]
  (into {} (for [kv (str/split (or q "") #"&") :when (seq kv)
                 :let [[k v] (str/split kv #"=" 2)]]
             [(keyword (decode k)) (decode (or v ""))])))

(defn- match-one [segs [pattern page]]
  (let [ps (split-path pattern)]
    (when (= (count ps) (count segs))
      (loop [ps ps segs segs params {}]
        (if (empty? ps)
          {:page page :params params}
          (let [p (first ps) s (first segs)]
            (cond
              (str/starts-with? p ":") (recur (rest ps) (rest segs) (assoc params (keyword (subs p 1)) (decode s)))
              (= p s) (recur (rest ps) (rest segs) params)
              :else nil)))))))

(defn match
  "Match a location against routes. Unmatched paths give {:page nil}."
  [routes {:keys [path query]}]
  (let [segs (split-path path)]
    (merge (or (some #(match-one segs %) routes) {:page nil :params {}})
           {:path path :query (parse-query query)})))

(defn href
  "Path for page with params (and optional query map)."
  ([routes page] (href routes page {} nil))
  ([routes page params] (href routes page params nil))
  ([routes page params query]
   (let [[pattern] (or (first (filter #(= page (second %)) routes))
                       (throw (ex-info (str "hypercurve.router: no route " page) {:page page})))
         path (str "/" (str/join "/" (for [p (split-path pattern)]
                                       (if (str/starts-with? p ":")
                                         (encode (get params (keyword (subs p 1))))
                                         p))))]
     (if (seq query)
       (str path "?" (str/join "&" (for [[k v] query] (str (encode (name k)) "=" (encode v)))))
       path))))

(defn- location-of [url]
  (let [[path query] (str/split (str url) #"\?" 2)]
    {:path (if (str/blank? path) "/" path) :query (or query "")}))

(defn navigate!
  "Go to url (a path, or [routes page params]) without reloading."
  ([url]
   #?(:cljs (.pushState js/history nil "" url))
   (reset! *location* (location-of url)))
  ([routes page params] (navigate! (href routes page params))))

(defn set-location!
  "Set the location directly (server-side rendering, tests)."
  [url]
  (reset! *location* (location-of url)))

#?(:cljs
   (defn install!
     "Follow the browser: read the current URL, track back/forward, and turn
     same-origin link clicks into client navigation (opt out per link with
     a data-reload attribute; download links are left alone)."
     []
     (let [sync! #(reset! !location (location-of (str (.. js/location -pathname) (.. js/location -search)))) ]
       (sync!)
       (.addEventListener js/window "popstate" sync!)
       (.addEventListener js/document "click"
                          (fn [e]
                            (when-let [a (.. e -target (closest "a[href]"))]
                              (when (and (= 0 (.-button e))
                                         (not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e)))
                                         (not (.hasAttribute a "data-reload"))
                                         (not (.hasAttribute a "target"))
                                         ;; downloads and blob:/data: links are not pages
                                         (not (.hasAttribute a "download"))
                                         (#{"http:" "https:"} (.-protocol a))
                                         (= (.-origin a) (.. js/location -origin)))
                                (.preventDefault e)
                                (navigate! (str (.-pathname a) (.-search a))))))))))
