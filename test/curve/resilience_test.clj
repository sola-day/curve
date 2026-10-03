(ns curve.resilience-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.codec :as codec]
            [curve.core :as r]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.runtime :as rt]
            [curve.session :as session]
            [curve.transport :as t])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def !board (atom {:votes 0}))

(r/defn Board []
  (let [!mine (atom 0) mine (r/watch !mine)]
    [:div
     [:span.votes (r/server (:votes (r/watch !board)))]
     [:i.mine mine]
     [:button.vote {:on-click (r/server (fn [_] (swap! !board update :votes inc)))} "+1"]
     [:button.mine {:on-click (fn [_] (swap! !mine inc))} "mine"]]))

(defn client [opts]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        peer (apply rt/peer :client (mapcat identity (dissoc hooks :mounter)))
        inbox (LinkedBlockingQueue.)
        s (session/start! Board [] (merge {:send! #(.put inbox %)} opts))
        c {:peer peer :inbox inbox :session s :root root :up (codec/link) :down (codec/decoder-state)
           :tr (t/state) :controls (atom [])}]
    (rt/mount-root! peer Board)
    c))

(defn pump! [{:keys [peer inbox session up down tr controls] :as c} & {:keys [drop-n]}]
  (let [dropped (atom 0)]
    (loop [n 0]
      (rt/run! peer)
      (when-let [m (rt/take-message! peer)] (session/receive! session (t/data-frame tr ((:encode up) m))))
      (when-let [bs (.poll inbox 60 TimeUnit/MILLISECONDS)]
        (if (and drop-n (< @dropped drop-n) (zero? (aget ^bytes bs 0)))
          (swap! dropped inc) ; lost in transit
          (let [{:keys [data control]} (t/receive tr bs)]
            (when control (swap! controls conj control))
            (when data (rt/receive! peer (codec/decode down data)))))
        (when (< n 200) (recur (inc n))))))
  (rt/run! peer)
  c)

(defn text [c sel] (h/text-content (h/query (:root c) sel)))

(deftest reconnect-resends-what-was-lost
  (reset! !board {:votes 0})
  (let [c (pump! (client {}))
        s (:session c)
        token (:session (first @(:controls c)))]
    (is (= "0" (text c ".votes")))
    (testing "changes while disconnected are kept, frames lost in transit are resent"
      (session/connection-lost! s 5000)
      (swap! !board update :votes inc)
      (Thread/sleep 50)
      (swap! !board update :votes inc)
      (Thread/sleep 50)
      ;; the old connection delivered nothing more; reconnect with what we saw
      (let [inbox2 (LinkedBlockingQueue.)
            s2 (session/reconnect! token (t/seen (:tr c)) #(.put inbox2 %))
            c2 (assoc c :inbox inbox2 :session s2)]
        (is (identical? s s2))
        (pump! c2)
        (is (= "2" (text c2 ".votes")))
        (testing "a frame lost on the new connection is resent after another drop"
          (swap! !board update :votes inc)
          (pump! c2 :drop-n 1)
          (is (= "2" (text c2 ".votes")) "lost: not applied")
          (session/connection-lost! s2 5000)
          (let [inbox3 (LinkedBlockingQueue.)
                c3 (assoc c2 :inbox inbox3 :session (session/reconnect! token (t/seen (:tr c)) #(.put inbox3 %)))]
            (pump! c3)
            (is (= "3" (text c3 ".votes")) "resent and applied exactly once")
            (session/close! (:session c3))))))))

(deftest idle-sessions-hibernate-and-wake
  (reset! !board {:votes 0})
  (let [watches #(count (.getWatches ^clojure.lang.IRef !board))
        before (watches)
        c (pump! (client {:hibernate-ms 100}))
        s (:session c)]
    (is (= (inc before) (watches)))
    (h/fire! (h/query (:root c) "button.mine") "click" {}) (pump! c)
    (is (= "1" (text c ".mine")))
    (Thread/sleep 400)
    (is (session/hibernated? s))
    (is (= before (watches)) "the server released its subscription")
    (is (pos? (:hibernations (session/metrics-snapshot))))
    (let [li (h/query (:root c) ".votes")]
      (h/fire! (h/query (:root c) "button.vote") "click" {})
      (pump! c)
      (is (not (session/hibernated? s)))
      (is (= "1" (text c ".votes")) "woke up, ran the call, sent the new value")
      (is (= "1" (text c ".mine")) "client state untouched")
      (is (identical? li (h/query (:root c) ".votes")) "the client DOM was not rebuilt"))
    (session/close! s)))

(deftest version-and-migration
  (let [c (pump! (client {}))
        ctl (first @(:controls c))]
    (is (= (rt/tree-version Board) (:version ctl)))
    (session/migrate! (:session c))
    (pump! c)
    (is (some :drain @(:controls c)))))

(deftest fnv-is-portable
  ;; reference values of FNV-1a 32-bit
  (is (= 0x811c9dc5 (rt/fnv32 "")))
  (is (= 0xe40c292c (rt/fnv32 "a")))
  (is (= 0xbf9cf968 (rt/fnv32 "foobar"))))
