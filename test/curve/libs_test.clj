(ns curve.libs-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.core :as r]
            [curve.forms :as forms]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.virtual :as virtual]
            [curve.test :as ct]))

(defn render [ctor & args]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    (assoc p :dom root)))

;; ---------------------------------------------------------------- forms

(def saved (atom []))
(def validators {:email (fn [v _] (when-not (re-find #"@" (str v)) "needs an @"))})

(r/defn Signup []
  (let [!f (forms/state {:email "" :name ""})
        save! (r/server ^{:validate map?}
                        (fn [values]
                          (if (= "taken@x.org" (:email values))
                            {:email "already registered"}
                            (do (swap! saved conj values) nil))))
        st (r/watch !f)]
    [:form {:on-submit (fn [_] (forms/submit! !f validators save!))}
     (forms/Field !f :name "Name")
     (forms/Field !f :email "Email" "email")
     [:p.status (name (:status st))]]))

(deftest forms-validate-and-submit
  (reset! saved [])
  (let [{:keys [dom] :as p} (render Signup)
        email (h/query dom "input[name=email]")
        input (fn [n v] (h/input! (first (filter #(= n (get @(:attrs %) "name")) (h/query-all dom "input"))) v) (ct/flush! p))
        submit! (fn [] (h/fire! (h/query dom "form") "submit" {}) (ct/flush! p))]
    (is (= 2 (count (h/query-all dom "input"))))
    (input "email" "nope")
    (submit!)
    (is (= "needs an @" (h/text-content (h/query dom ".error"))))
    (is (empty? @saved))
    (input "email" "taken@x.org")
    (submit!)
    (is (= "already registered" (h/text-content (h/query dom ".error"))) "server-side errors show on the field")
    (input "email" "ada@x.org") (input "name" "Ada")
    (submit!)
    (is (= [{:email "ada@x.org" :name "Ada"}] @saved))
    (is (= "saved" (h/text-content (h/query dom ".status"))))
    (is (nil? (h/query dom ".error")))))

;; ---------------------------------------------------------------- foreign

(def chart-log (atom []))

(defn chart [el props]
  (swap! chart-log conj [:mount (:tag el) props])
  {:update (fn [p] (swap! chart-log conj [:update p]))
   :unmount (fn [] (swap! chart-log conj [:unmount]))})

(r/defn Chart [!data]
  (let [data (r/watch !data)]
    [:section (if (seq data) (r/foreign chart {:points data}) [:p "empty"])]))

(deftest foreign-components
  (reset! chart-log [])
  (let [!data (atom [1 2])
        {:keys [dom] :as p} (render Chart !data)]
    (is (= [[:mount "div" {:points [1 2]}]] @chart-log))
    (is (some? (h/query dom "div.curve-foreign")))
    (swap! !data conj 3) (ct/flush! p)
    (is (= [:update {:points [1 2 3]}] (last @chart-log)))
    (reset! !data []) (ct/flush! p)
    (is (= [:unmount] (last @chart-log)))))

;; ---------------------------------------------------------------- virtual

(def all-rows (vec (for [i (range 10000)] {:id i :name (str "row " i)})))

(r/defn Rows [start end]
  (r/for [row (r/server (subvec all-rows start end)) :recycle true]
    [:div.row (:name row)]))

(r/defn Big []
  (virtual/Window {:total (r/server (count all-rows)) :row-height 20 :height 200} Rows))

(deftest virtual-window
  (let [{:keys [dom] :as p} (render Big)
        rows #(mapv h/text-content (h/query-all dom ".row"))]
    (is (= 17 (count (rows))) "10 visible + 2x3 overscan + 1")
    (is (= "row 0" (first (rows))))
    (let [first-node (h/query dom ".row")]
      (ct/clear-wire! p)
      (h/fire! (h/query dom ".curve-window") "scroll" {:scroll-top 2000})
      (ct/flush! p)
      (is (= "row 97" (first (rows))))
      (is (identical? first-node (h/query dom ".row")) "row nodes are recycled")
      (is (< (ct/bytes-sent p :s->c) 600) "only the rows in view cross the wire"))))
