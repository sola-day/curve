(ns curve.ssr-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [curve.codec :as codec]
            [curve.core :as r]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.router :as router]
            [curve.runtime :as rt]
            [curve.session :as session]
            [curve.ssr :as ssr])
  (:import [java.util Base64]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def queries (atom 0))
(def !items (atom []))
(defn load-items [xs] (swap! queries inc) xs)

(def routes [["/" :home] ["/items" :items]])

(r/defn App []
  (let [{:keys [page]} (r/route routes)
        !open (atom true)
        open (r/watch !open)]
    [:main
     [:h1 (if (= page :items) "Items" "Home")]
     [:p.count (r/server (count (r/watch !items)))]
     [:button.toggle {:on-click (fn [_] (swap! !open not))} "toggle"]
     (when open
       [:ul (r/for [it (r/server (load-items (r/watch !items))) :by :id] [:li (:name it)])])]))

(defn resume-client
  "What the browser does with a render result, on a headless DOM."
  [{:keys [token state]}]
  (let [data (second (codec/decode-delta-blob (.decode (Base64/getDecoder) ^String state)))
        root (h/root)
        hooks (mount/renderer (h/dom) root)
        inbox (LinkedBlockingQueue.)
        peer (apply rt/peer :client (mapcat identity (dissoc hooks :mounter)))
        up-enc (codec/import-state (:up data))
        down (codec/import-state (:down data))
        s (ssr/take-detached! token)]
    (session/attach! s #(.put inbox %))
    (rt/resume! peer App (:peer data))
    {:root root :peer peer :inbox inbox :session s
     :send #(session/receive! s (codec/encode up-enc %))
     :decode #(codec/decode down %)}))

(defn pump! [{:keys [peer inbox send decode] :as c}]
  (loop [n 0]
    (rt/run! peer)
    (when-let [m (rt/take-message! peer)] (send m))
    (when-let [bs (.poll inbox 60 TimeUnit/MILLISECONDS)]
      (rt/receive! peer (decode bs))
      (when (< n 100) (recur (inc n)))))
  (rt/run! peer)
  c)

(deftest render-and-resume
  (reset! !items [{:id 1 :name "one"} {:id 2 :name "two"}])
  (reset! queries 0)
  (binding [router/*location* (atom {:path "/" :query ""})]
    (let [result (ssr/render App [] :url "/items")]
      (testing "html"
        (is (= "<main><h1>Items</h1><p class=\"count\">2</p><button class=\"toggle\">toggle</button><ul><li>one</li><li>two</li></ul></main>"
               (:html result)))
        (is (= 1 @queries)))
      (testing "resume: same DOM, no second query"
        (router/set-location! "/items") ; the browser is at the rendered URL
        (let [c (pump! (resume-client result))]
          (is (= (:html result) (h/html (:root c))))
          (is (= 1 @queries) "the server did not run the query again")
          (testing "the session continues"
            (swap! !items conj {:id 3 :name "three"})
            (pump! c)
            (is (= ["one" "two" "three"] (mapv h/text-content (h/query-all (:root c) "li")))))
          (testing "client state was resumed too"
            (h/fire! (h/query (:root c) "button.toggle") "click" {})
            (pump! c)
            (is (nil? (h/query (:root c) "ul"))))
          (session/close! (:session c)))))))

(r/defn Slow []
  [:div
   [:h1 "fast"]
   (r/suspense [:p.loading "loading"]
     [:p.slow (r/offload (Thread/sleep 300) "slow done")])])

(deftest streaming
  (let [chunks (vec (ssr/render-stream Slow [] {:title "t" :script "/main.js"} :first-ms 100))
        html (apply str chunks)]
    (is (< 2 (count chunks)))
    (is (str/includes? (first chunks) "loading") "the first chunk shows the fallback")
    (is (str/includes? html "<template data-curve-chunk=\"0\">"))
    (is (str/includes? html "slow done"))
    (is (str/includes? (last chunks) "__CURVE__"))))
