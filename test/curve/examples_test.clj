(ns curve.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.shared :as shared]
            [curve.test :as ct]
            [sqlite-table.app :as sq]
            [sqlite-table.db :as db]
            [todomvc.app :as todo]))

(defn render [ctor]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor)]
    (ct/flush! p)
    (assoc p :dom root)))

(defn texts [root sel] (mapv h/text-content (h/query-all root sel)))

(deftest todomvc
  (let [{:keys [dom] :as p} (render todo/App)
        type! (fn [s] (h/input! (h/query dom ".new-todo") s) (ct/flush! p)
                (h/fire! (h/query dom ".new-todo") "keydown" {:key "Enter"}) (ct/flush! p))]
    (type! "buy milk") (type! "write docs") (type! "   ")
    (is (= ["buy milk" "write docs"] (texts dom ".todo-list label")))
    (is (= "2 items left" (h/text-content (h/query dom ".todo-count"))))
    (h/fire! (h/query dom ".toggle") "change" {}) (ct/flush! p)
    (is (= "1 item left" (h/text-content (h/query dom ".todo-count"))))
    (is (= 1 (count (h/query-all dom "li.completed"))))
    (testing "filters"
      (h/fire! (second (h/query-all dom ".filters a")) "click" {}) (ct/flush! p)
      (is (= ["write docs"] (texts dom ".todo-list label")))
      (h/fire! (last (h/query-all dom ".filters a")) "click" {}) (ct/flush! p)
      (is (= ["buy milk"] (texts dom ".todo-list label")))
      (h/fire! (first (h/query-all dom ".filters a")) "click" {}) (ct/flush! p))
    (testing "clear completed, toggle all, destroy"
      (h/fire! (h/query dom ".clear-completed") "click" {}) (ct/flush! p)
      (is (= ["write docs"] (texts dom ".todo-list label")))
      (h/fire! (h/query dom ".toggle-all") "change" {}) (ct/flush! p)
      (is (= "0 items left" (h/text-content (h/query dom ".todo-count"))))
      (h/fire! (h/query dom ".destroy") "click" {}) (ct/flush! p)
      (is (nil? (h/query dom ".main"))))
    (testing "client-only app: nothing but the initial handshake crossed the wire"
      (is (every? #(empty? (:vals (:msg %))) (filter #(= :s->c (:dir %)) (ct/wire-log p)))))))

(defn- settle! [& ps] (dotimes [_ 3] (shared/await-idle) (run! ct/flush! ps)))

(deftest sqlite-table-app
  (let [f (java.io.File/createTempFile "curve-products" ".db")]
    (db/init! (.getPath f))
    (let [a (render sq/App) b (render sq/App)
          login! (fn [{:keys [dom] :as p} name pw]
                   (h/input! (h/query dom "input.user") name)
                   (h/input! (h/query dom "input.password") pw)
                   (ct/flush! p)
                   (h/fire! (h/query dom "form.login") "submit" {})
                   (settle! p))]
      (testing "login"
        (login! a "admin" "wrong")
        (is (some? (h/query (:dom a) "form.login")))
        (login! a "admin" "admin")
        (login! b "admin" "admin")
        (is (= "Logged in as admin log out" (h/text-content (h/query (:dom a) ".who")))))
      (settle! a b)
      (is (= ["Apple" "Banana" "Cherry"] (mapv h/value (h/query-all (:dom a) "input.name"))))
      (testing "an edit in one session reaches the other"
        (h/input! (second (h/query-all (:dom a) "input.name")) "Blueberry")
        (settle! a b)
        (is (= ["Apple" "Blueberry" "Cherry"] (mapv h/value (h/query-all (:dom b) "input.name")))))
      (testing "add and delete"
        (h/fire! (h/query (:dom b) "button.add") "click" {})
        (settle! a b)
        (is (= "4 products" (h/text-content (h/query (:dom a) ".count"))))
        (h/fire! (first (h/query-all (:dom a) "button.delete")) "click" {})
        (settle! a b)
        (is (= ["Blueberry" "Cherry" "New product"] (mapv h/value (h/query-all (:dom b) "input.name")))))
      (testing "logout drops back to the form"
        (h/fire! (h/query (:dom a) "button.logout") "click" {})
        (settle! a)
        (is (some? (h/query (:dom a) "form.login")))))))
