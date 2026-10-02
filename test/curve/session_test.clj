(ns curve.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.codec :as codec]
            [curve.core :as r]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.runtime :as rt]
            [curve.session :as session])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def !board (atom {:title "board" :votes 0}))

(r/defn Board []
  [:div
   [:h1 (r/server (:title (r/watch !board)))]
   [:span.votes (r/server (:votes (r/watch !board)))]
   [:button {:on-click (r/server (fn [_] (swap! !board update :votes inc)))} "+1"]])

(defn client
  "A real client peer + headless DOM connected to a session over bytes."
  [ctor]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        peer (apply rt/peer :client (mapcat identity (dissoc hooks :mounter)))
        inbox (LinkedBlockingQueue.)
        s (session/start! ctor [] {:send! #(.put inbox %)})
        c {:peer peer :inbox inbox :session s :root root :up (codec/link)}]
    (rt/mount-root! peer ctor)
    c))

(defn pump!
  "Exchange messages until the session has been quiet for a moment."
  [{:keys [peer inbox session up] :as c} & {:keys [quiet-ms] :or {quiet-ms 50}}]
  (do
    (loop [n 0]
      (rt/run! peer)
      (when-let [m (rt/take-message! peer)]
        (session/receive! session ((:encode up) m)))
      (when-let [bs (.poll inbox quiet-ms TimeUnit/MILLISECONDS)]
        (rt/receive! peer ((:decode-down c) bs))
        (when (< n 200) (recur (inc n)))))
    (rt/run! peer)
    c))

(defn connect [ctor]
  (let [c (client ctor)
        dec-st (codec/decoder-state)]
    (assoc c :decode-down #(codec/decode dec-st %))))

(deftest session-end-to-end
  (reset! !board {:title "board" :votes 0})
  (let [a (pump! (connect Board))
        b (pump! (connect Board))]
    (is (= "<div><h1>board</h1><span class=\"votes\">0</span><button>+1</button></div>" (h/html (:root a))))
    (testing "an event in one session updates every session"
      (h/fire! (h/query (:root a) "button") "click" {})
      (pump! a) (pump! b)
      (is (= "1" (h/text-content (h/query (:root a) ".votes"))))
      (is (= "1" (h/text-content (h/query (:root b) ".votes")))))
    (testing "concurrent writes from other threads converge"
      (dorun (pmap (fn [_] (swap! !board update :votes inc)) (range 100)))
      (pump! a) (pump! b)
      (is (= "101" (h/text-content (h/query (:root b) ".votes")))))
    (testing "closing runs cleanups (watches removed)"
      (let [watches-before (count (.getWatches ^clojure.lang.IRef !board))]
        (session/close! (:session a))
        (Thread/sleep 50)
        (is (< (count (.getWatches ^clojure.lang.IRef !board)) watches-before))))))

(deftest malformed-input-closes-only-that-session
  (reset! !board {:title "board" :votes 0})
  (let [errors (atom [])
        bad (session/start! Board [] {:send! (fn [_]) :on-error #(swap! errors conj %)})
        good (pump! (connect Board))]
    (session/receive! bad (byte-array [2 1 9 9 99]))
    (Thread/sleep 100)
    (is (= 1 (count @errors)))
    (is (false? @(:open bad)))
    (is @(:open (:session good)))
    (swap! !board update :votes inc)
    (pump! good)
    (is (= "1" (h/text-content (h/query (:root good) ".votes"))))))
