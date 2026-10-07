(ns hypercurve.language-test
  "Phase-1 language forms (M9)."
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(defn render [ctor & args]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    (assoc p :dom root)))

(defn wait-flush [p] (dotimes [_ 20] (Thread/sleep 10) (ct/flush! p)) p)

;; ---------------------------------------------------------------- r/binding

(def ^:dynamic *theme* :light)

(r/defn Themed [] [:span.theme (name *theme*)])

(r/defn ServerThemed [] (r/server (str "server sees " (name *theme*))))

(def !theme (atom :dark))

(r/defn BindingApp []
  [:div
   (Themed)
   (r/binding [*theme* (r/watch !theme)]
     [:p (Themed) (ServerThemed)])])

(deftest binding-follows-calls-across-sites
  (reset! !theme :dark)
  (let [{:keys [dom] :as p} (render BindingApp)]
    (is (= ["light" "dark"] (mapv h/text-content (h/query-all dom "span.theme"))))
    (is (= "darkserver sees dark" (h/text-content (h/query dom "p"))))
    (reset! !theme :blue)
    (ct/flush! p)
    (is (= "blueserver sees blue" (h/text-content (h/query dom "p"))))))

;; ---------------------------------------------------------------- r/effect

(def log (atom []))

(r/defn Effects [x]
  (r/effect (swap! log conj [:start x]) (fn [] (swap! log conj [:stop x])))
  [:i x])

(r/defn EffectHost []
  (let [!x (atom 1) x (r/watch !x)]
    [:div (if (< x 3) (Effects x) "gone")
     [:button {:on-click (fn [_] (swap! !x inc))} "+"]]))

(deftest effect-runs-and-cleans-up
  (reset! log [])
  (let [{:keys [dom] :as p} (render EffectHost)]
    (is (= [[:start 1]] @log))
    (h/fire! (h/query dom "button") "click" {}) (ct/flush! p)
    (is (= [[:start 1] [:stop 1] [:start 2]] @log))
    (h/fire! (h/query dom "button") "click" {}) (ct/flush! p)
    (is (= [[:start 1] [:stop 1] [:start 2] [:stop 2]] @log))
    (is (= "gone+" (h/text-content (h/query dom "div"))))))

;; ---------------------------------------------------------------- r/boundary

(def !fail (atom false))

(defn risky [fail?] (if fail? (throw (ex-info "kaboom" {})) "fine"))

(r/defn Risky [] [:span.ok (r/server (risky (r/watch !fail)))])

(r/defn BoundaryApp []
  (r/boundary (fn [err retry] [:div.error (ex-message err) [:button.retry {:on-click (fn [_] (retry))} "retry"]])
    (Risky)))

(deftest boundary-catches-and-retries
  (reset! !fail false)
  (let [{:keys [dom] :as p} (render BoundaryApp)]
    (is (= "fine" (h/text-content (h/query dom "span.ok"))))
    (reset! !fail true) (ct/flush! p)
    (is (= "kaboomretry" (h/text-content (h/query dom ".error"))))
    (is (nil? (h/query dom "span.ok")))
    (reset! !fail false)
    (h/fire! (h/query dom ".retry") "click" {}) (ct/flush! p)
    (is (= "fine" (h/text-content (h/query dom "span.ok"))))))

;; ---------------------------------------------------------------- r/suspense, r/offload

(def gate (atom (promise)))

(r/defn Slow [] [:span.slow (r/offload (deref @gate 2000 :timeout))])

(r/defn SuspenseApp []
  (r/suspense [:p.loading "loading…"]
    [:div (Slow)]))

(deftest suspense-shows-fallback-while-pending
  (reset! gate (promise))
  (let [{:keys [dom] :as p} (render SuspenseApp)]
    (is (= "loading…" (h/text-content (h/query dom "p.loading"))))
    (is (re-find #"display:none" (h/html dom)))
    (deliver @gate "ready")
    (wait-flush p)
    (is (nil? (h/query dom "p.loading")))
    (is (= "ready" (h/text-content (h/query dom "span.slow"))))))

;; ---------------------------------------------------------------- r/flow, r/mutation

(r/defn Ticker [subscribe] [:b (r/flow subscribe)])

(deftest flow-pushes-values
  (let [emit (atom nil)
        subscribe (fn [e] (reset! emit e) (fn [] (reset! emit :closed)))
        {:keys [dom] :as p} (render Ticker subscribe)]
    (is (= "" (h/text-content (h/query dom "b"))))
    (@emit 41) (ct/flush! p)
    (is (= "41" (h/text-content (h/query dom "b"))))
    (rt/unmount-frame! (:client-root p))
    (is (= :closed @emit))))

(def !saved (atom nil))

(r/defn MutationApp []
  [:input {:on-change (r/mutation (fn [v] (reset! !saved v)))}])

(deftest mutation-sends-event-value
  (let [{:keys [dom] :as p} (render MutationApp)]
    (h/input! (h/query dom "input") "hello")
    (ct/flush! p)
    (is (= "hello" @!saved))))
