(ns hypercurve.static-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.server :as server]
            [hypercurve.ssr :as ssr]
            [hypercurve.static :as static]))

(def builds (atom 0))

(r/defn Doc []
  [:article [:h1 "Docs"] [:p (r/static (swap! builds inc) (str "built " "at compile time"))]])

(r/defn Toggle []
  (let [!open (atom false) open (r/watch !open)]
    [:div [:button {:on-click (fn [_] (swap! !open not))} "more"] (when open [:p "details"])]))

(def !guestbook (atom []))

(r/defn Guestbook []
  [:div
   [:ul (r/for [e (r/server (r/watch !guestbook))] [:li e])]
   [:form {:on-submit (r/server (fn [{:keys [form]}] (swap! !guestbook conj (:text form))))}
    [:input {:name "text"}]
    [:button "sign"]]])

(deftest static-values
  (is (= 1 @builds) "evaluated once, when the fn was compiled")
  (is (str/includes? (:html (ssr/render Doc [])) "built at compile time")))

(deftest tiers
  (let [out (io/file (System/getProperty "java.io.tmpdir") (str "hypercurve-static-" (System/nanoTime)))
        pages (static/build! {:ctor Doc :urls ["/" "/about"] :out out :title "t" :script "/main.js"})]
    (is (= [:html :html] (map :tier pages)))
    (is (not (str/includes? (slurp (:file (first pages))) "<script")) "no JS at all")
    (is (.exists (io/file out "about" "index.html"))))
  (is (= :client (:tier (ssr/render Toggle []))))
  (is (= :live (:tier (ssr/render Guestbook [])))))

(deftest forms-work-without-js
  (reset! !guestbook [])
  (let [{:keys [html]} (ssr/render Guestbook [])
        action (second (re-find #"action=\"([^\"]+)\"" html))]
    (is (str/includes? html "method=\"post\""))
    (is (some? action))
    (let [resp (server/action-handler {:request-method :post
                                       :query-string (str/replace (subs action (inc (str/index-of action "?"))) "&amp;" "&")
                                       :headers {"referer" "/guestbook"}
                                       :body (java.io.ByteArrayInputStream. (.getBytes "text=hello+world"))})]
      (is (= 303 (:status resp)))
      (is (= "/guestbook" (get-in resp [:headers "Location"])))
      (Thread/sleep 50)
      (is (= ["hello world"] @!guestbook))
      (is (str/includes? (:html (ssr/render Guestbook [])) "<li>hello world</li>")
          "the next GET shows the result"))))
