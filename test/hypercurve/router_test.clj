(ns hypercurve.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.router :as router]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(def routes [["/" :home] ["/products" :products] ["/products/:id" :product]])

(deftest match-and-href
  (is (= {:page :product :params {:id "3"} :path "/products/3" :query {:sort "name"}}
         (router/match routes {:path "/products/3" :query "sort=name"})))
  (is (= :home (:page (router/match routes {:path "/" :query ""}))))
  (is (nil? (:page (router/match routes {:path "/nope" :query ""}))))
  (is (= "/products/a%20b" (router/href routes :product {:id "a b"})))
  (is (= "/products?q=x" (router/href routes :products {} {:q "x"}))))

(def server-lookups (atom []))
(defn lookup! [id] (swap! server-lookups conj id) (str "#" id))

(r/defn Product [id]
  [:div.product "product " (r/server (lookup! id))])

(r/defn App []
  (let [{:keys [page params]} (r/route routes)]
    [:main
     [:nav [:a {:href (router/href routes :products)} "all"]]
     (case page
       :home [:h1 "home"]
       :products [:ul [:li [:a {:href (router/href routes :product {:id 1})} "one"]]]
       :product (Product (:id params))
       [:h1.not-found "not found"])]))

(defn render [ctor]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor)]
    (ct/flush! p)
    (assoc p :dom root)))

(deftest route-is-a-reactive-input
  (binding [router/*location* (atom {:path "/" :query ""})]
    (reset! server-lookups [])
    (let [{:keys [dom] :as p} (render App)]
      (is (= "home" (h/text-content (h/query dom "h1"))))
      (router/navigate! "/products")
      (ct/flush! p)
      (is (= "one" (h/text-content (h/query dom "li a"))))
      (router/navigate! routes :product {:id 7})
      (ct/flush! p)
      (is (= "product #7" (h/text-content (h/query dom ".product"))))
      (is (= ["7"] @server-lookups) "the server read the client's route")
      (router/navigate! "/nowhere")
      (ct/flush! p)
      (is (some? (h/query dom ".not-found"))))))

(r/defn Lazy [x] [:em "lazy " x])

(r/defn Holder [] (let [F (r/server Lazy)] [:p (r/call F "x")]))

(deftest reactive-fns-cross-the-wire-by-name
  (let [{:keys [dom]} (render Holder)]
    (is (= "lazy x" (h/text-content (h/query dom "em")))))
  (is (= Lazy (rt/ctor-by-name 'hypercurve.router-test/Lazy))))

(r/defn Deferred []
  [:div (r/defer {:when :idle :placeholder [:i "…"]} [:b "later"])])

(deftest defer-mounts-later
  (let [{:keys [dom]} (render Deferred)]
    (is (= "later" (h/text-content (h/query dom "b"))))))
