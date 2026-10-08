(ns hypercurve.reload-test
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.compiler :as compiler]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(defn render [ctor]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor)]
    (ct/flush! p)
    (assoc p :dom root)))

(defn define! [src] (binding [*ns* (the-ns 'hypercurve.reload-test)] (eval (read-string src))))

(define! "(r/defn Counter [] (let [!n (atom 0) n (r/watch !n)]
            [:div [:b n] [:button {:on-click (fn [_] (swap! !n inc))} \"+\"]]))")
(define! "(r/defn Page [] [:main (Counter)])")

(deftest hot-reload-keeps-local-state
  (let [{:keys [dom client server] :as p} (render (var-get (resolve 'hypercurve.reload-test/Page)))]
    (dotimes [_ 3] (h/fire! (h/query dom "button") "click" {}) (ct/flush! p))
    (is (= "3" (h/text-content (h/query dom "b"))))
    (testing "edit the view, keep the atom"
      (define! "(r/defn Counter [] (let [!n (atom 0) n (r/watch !n)]
                  [:div [:b n] [:i \" clicks\"] [:button {:on-click (fn [_] (swap! !n inc))} \"+\"]]))")
      (rt/reload! client) (rt/reload! server)
      (ct/flush! p)
      (is (= "3 clicks+" (h/text-content (h/query dom "div"))))
      (h/fire! (h/query dom "button") "click" {}) (ct/flush! p)
      (is (= "4" (h/text-content (h/query dom "b")))))))

(deftest stable-ids
  (binding [*ns* (the-ns 'hypercurve.reload-test)]
  (let [a (compiler/analyze 'x/F '[a] '((let [!s (atom 0)] [:p (str a) (r/watch !s)])))
        b (compiler/analyze 'x/F '[a] '((let [!s (atom 0)] [:p (str a "!") [:i "new"] (r/watch !s)])))
        state-sids (fn [code] (set (keep #(when (and (= :call (:op %)) (empty? (:in %))) (:sid %))
                                         (:nodes (eval code)))))]
    (is (= 1 (count (state-sids a))))
    (is (= (state-sids a) (state-sids b)) "unchanged subexpression keeps its id"))))

(deftest incremental-compilation
  (let [before (compiler/cache-size)]
    (define! "(r/defn Same [] [:p \"same\"])")
    (define! "(r/defn Same [] [:p \"same\"])")
    (is (= (inc before) (compiler/cache-size)) "second identical definition is a cache hit")))

;; a seed carries local state into the remount it was taken for, once: a
;; child mounted again later (another canvas at the same place) starts fresh
(define! "(r/defn Shell [] (let [!show (atom true) show (r/watch !show)]
            [:section [:button.toggle {:on-click (fn [_] (swap! !show not))} \"toggle\"]
             (when show (Counter))]))")

(deftest seeds-are-used-once
  (let [{:keys [dom client server] :as p} (render (var-get (resolve 'hypercurve.reload-test/Shell)))
        plus #(h/query dom "div button")]
    (dotimes [_ 2] (h/fire! (plus) "click" {}) (ct/flush! p))
    (define! "(r/defn Shell [] (let [!show (atom true) show (r/watch !show)]
                [:section [:button.toggle {:on-click (fn [_] (swap! !show not))} \"toggle!\"]
                 (when show (Counter))]))")
    (rt/reload! client) (rt/reload! server)
    (ct/flush! p)
    (is (= "2" (h/text-content (h/query dom "b"))) "the reload keeps the counter")
    (h/fire! (h/query dom "button.toggle") "click" {}) (ct/flush! p)
    (is (nil? (h/query dom "b")))
    (h/fire! (h/query dom "button.toggle") "click" {}) (ct/flush! p)
    (is (= "0" (h/text-content (h/query dom "b"))) "mounted again: fresh state")))
