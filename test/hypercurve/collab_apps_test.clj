(ns hypercurve.collab-apps-test
  "Stage 4 (M26–M30): persistent shared atoms, change metadata, the shared
  send path for watched atoms, rate control and 2D windows."
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.clock :as clock]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.optimistic]
            [hypercurve.presence :as presence]
            [hypercurve.runtime :as rt]
            [hypercurve.shared :as shared]
            [hypercurve.test :as ct]
            [hypercurve.timing :as timing]
            [hypercurve.virtual :as virtual]))

(defn render [ctor & args]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    (assoc p :dom root)))

;; ---------------------------------------------------------------- timing

(deftest debounce-and-throttle
  (let [clk (clock/virtual-clock) calls (atom [])
        d (timing/debounce 100 {:clock clk} #(swap! calls conj %))]
    (testing "a burst becomes one call with the last arguments"
      (d 1) (clock/advance! clk 50) (d 2) (clock/advance! clk 99)
      (is (= [] @calls))
      (clock/advance! clk 1)
      (is (= [2] @calls)))
    (testing "flush! and cancel!"
      (d 3) (is (timing/pending? d))
      (is (true? (timing/flush! d))) (is (= [2 3] @calls))
      (d 4) (timing/cancel! d) (clock/advance! clk 500)
      (is (= [2 3] @calls))))
  (let [clk (clock/virtual-clock) calls (atom [])
        d (timing/debounce 100 {:clock clk :max-wait 150} #(swap! calls conj %))]
    (dotimes [i 10] (d i) (clock/advance! clk 40))
    (is (= [3 7] @calls) ":max-wait bounds the delay under a steady stream"))
  (let [clk (clock/virtual-clock) calls (atom [])
        d (timing/debounce 100 {:clock clk :leading true} #(swap! calls conj %))]
    (d 1) (d 2) (clock/advance! clk 100)
    (is (= [1 2] @calls) ":leading calls at once, then the trailing last call"))
  (let [clk (clock/virtual-clock) calls (atom [])
        t (timing/throttle 100 {:clock clk} #(swap! calls conj %))]
    (t 1) (t 2) (t 3)
    (is (= [1] @calls) "the first call runs at once")
    (clock/advance! clk 100)
    (is (= [1 3] @calls) "the latest runs when the interval ends")
    (t 4) (clock/advance! clk 100)
    (is (= [1 3 4] @calls))))

;; ---------------------------------------------------------------- debounce send hint

(deftest debounce-hint-sends-when-quiet
  (let [clk (clock/virtual-clock)
        !x (atom 0)
        c (rt/ctor {:name 'quiet :ret 1
                    :nodes [{:op :const :v !x}
                            {:op :watch :site :server :in [0] :readers #{:client} :debounce 100}]})
        p (-> (ct/pair :server-opts {:clock clk}) (ct/mount! c) ct/flush!)
        sends #(count (filter (fn [e] (and (= :s->c (:dir e)) (seq (:vals (:msg e))))) (ct/wire-log p)))]
    (clock/advance! clk 1000) (ct/flush! p)
    (is (= 0 (rt/value (:client-root p) 1)))
    (ct/clear-wire! p)
    (dotimes [i 5] (reset! !x (inc i)) (clock/advance! clk 60) (ct/flush! p))
    (is (zero? (sends)) "nothing while it keeps changing")
    (clock/advance! clk 100) (ct/flush! p)
    (is (= 1 (sends)) "one send once quiet")
    (is (= 5 (rt/value (:client-root p) 1)))))

(r/defn Counter []
  (let [!n (atom 0)
        n (r/watch !n)
        !calls (r/server (atom []))
        calls (r/server (r/watch !calls))
        !view (r/projection (r/server (count calls)))
        view (r/watch !view)
        save! (r/mutation (fn [x] (swap! !calls conj x))
                          {:debounce 40 :optimistic [!view inc]})]
    [:div
     [:span.view (str view)]
     [:span.calls (pr-str calls)]
     [:button {:on-click (fn [_] (save! (swap! !n inc)))} "+"]]))

(deftest debounced-mutation
  (let [{:keys [dom] :as p} (render Counter)
        click! #(do (h/fire! (h/query dom "button") "click" {}) (ct/run-client! p))]
    (click!) (click!) (click!)
    (is (= "3" (h/text-content (h/query dom ".view"))) "every call shows at once")
    (ct/flush! p)
    (is (= "[]" (h/text-content (h/query dom ".calls"))) "server not called yet")
    (Thread/sleep 120)
    (ct/flush! p)
    (is (= "[3]" (h/text-content (h/query dom ".calls"))) "one call, last argument")
    (is (= "1" (h/text-content (h/query dom ".view"))) "projections dropped; the server's value shows")))

;; ---------------------------------------------------------------- shared atoms

(deftest shared-atom-metadata
  (let [changes (atom [])
        a ((shared/atom-family {:init (fn [_] {}) :idle-ms nil
                                :validate (fn [_ _ new _] (not (:bad new)))})
           ::m)]
    (shared/listen! a ::t #(swap! changes conj (select-keys % [:version :delta :meta])))
    (shared/swap-meta! a {:op-id "a" :user 1} assoc :x 1)
    (shared/swap-meta! a {:op-id "a" :user 1} assoc :x 2)
    (is (= {:x 1} @a) "a repeated op-id is ignored")
    (swap! a assoc :x 1)
    (is (= 1 (shared/version a)) "no change, no version")
    (is (thrown? clojure.lang.ExceptionInfo (swap! a assoc :bad true)))
    (is (= {:x 1} @a) "a rejected change leaves the value")
    (swap! a assoc-in [:y] 2)
    (is (= [{:version 1 :delta [:m {:set {:x 1}}] :meta {:op-id "a" :user 1}}
            {:version 2 :delta [:m {:set {:y 2}}] :meta nil}]
           @changes))))

(deftest family-lifecycle
  (let [clk (clock/virtual-clock)
        store (atom {"c1" {:a 1}})
        batches (atom [])
        fam (shared/atom-family {:load #(get @store %) :init (fn [_] {}) :clock clk
                                 :flush-ms 50 :idle-ms 1000
                                 :flush (fn [k changes v] (swap! batches conj (count changes)) (swap! store assoc k v))})
        a (fam "c1")
        settle! #(do (clock/advance! clk %) (Thread/sleep 30))]
    (is (= {:a 1} @a) "loaded from storage")
    (is (identical? a (fam "c1")))
    (swap! a assoc :b 1) (swap! a assoc :c 1)
    (settle! 50)
    (is (= [2] @batches) "writes are batched")
    (is (= {:a 1 :b 1 :c 1} (get @store "c1")))
    (let [h (rt/-share! a (fn []))]
      (settle! 5000)
      (is (= #{"c1"} (set (keys (shared/members fam)))) "kept while watched")
      ((:release h)))
    (swap! a assoc :d 1)
    (settle! 999)
    (is (contains? (shared/members fam) "c1"))
    (settle! 1)
    (is (empty? (shared/members fam)) "unloaded after :idle-ms without watchers")
    (is (= {:a 1 :b 1 :c 1 :d 1} (get @store "c1")) "flushed before unloading")
    (swap! a assoc :e 1)
    (is (= {:a 1 :b 1 :c 1 :d 1 :e 1} @(fam "c1")) "a stale reference writes to the reloaded atom")))

(def doc-family (shared/atom-family {:init (fn [_] {}) :idle-ms nil}))

(r/defn Doc [id]
  (let [doc (r/server (r/watch (doc-family id)))]
    [:ul (r/for [[k v] (sort doc)] [:li (str (name k) "=" v)])]))

(deftest watched-atoms-encode-once
  (let [a (doc-family ::d)
        _ (reset! a (into {} (for [i (range 50)] [(keyword (str "k" i)) i])))
        ps (vec (repeatedly 5 #(render Doc ::d)))
        flush-all! #(doseq [p ps] (ct/flush! p))
        _ (flush-all!)
        _ (run! ct/clear-wire! ps)
        before (:encodes @shared/stats)]
    (swap! a assoc :k7 700)
    (flush-all!)
    (is (= 1 (- (:encodes @shared/stats) before)) "one encode for five sessions")
    (is (every? #(some #{"k7=700"} (map h/text-content (h/query-all (:dom %) "li"))) ps))
    (is (< (ct/bytes-sent (first ps) :s->c) 40) "only the changed field crosses")
    (is (= 5 (shared/watchers a)))
    (doseq [p ps] (rt/unmount-frame! (:server-root p)))
    (is (= 0 (shared/watchers a)) "unmounting releases")))

;; ---------------------------------------------------------------- presence

(deftest presence-publisher
  (let [clk (clock/virtual-clock)
        room [::room 1]
        leave (presence/join! room "me" {:name "ada"})
        pub (presence/publisher room "me" {:rate 10 :clock clk})
        info #(get @(presence/members room) "me")]
    (pub {:cursor [1 1]})
    (is (= [1 1] (:cursor (info))) "the first update goes at once")
    (pub {:cursor [2 2]}) (pub {:drag 5}) (pub {:cursor [3 3]})
    (is (= [1 1] (:cursor (info))))
    (clock/advance! clk 100)
    (is (= {:name "ada" :cursor [3 3] :drag 5} (info)) "merged, latest per key")
    (pub {:drag nil}) (clock/advance! clk 100)
    (is (not (contains? (info) :drag)) "nil removes a key")
    (leave)
    (pub {:cursor [9 9]}) (clock/advance! clk 100)
    (is (nil? (info)) "updates after leaving do not resurrect the member")))

;; ---------------------------------------------------------------- 2D

(deftest two-d-windows
  (is (= (virtual/quantize {:x 10 :y 10 :w 100 :h 100} 50 100)
         (virtual/quantize {:x 30 :y 40 :w 100 :h 100} 50 100))
      "small pans give the same area")
  (let [items (into {} (for [i (range 2500)]
                         [i {:id i :x (* 50 (mod i 50)) :y (* 50 (quot i 50)) :w 40 :h 40}]))
        area {:x 0 :y 0 :w 500 :h 250}
        expect (set (map :id (virtual/in-rect area identity items)))
        idx (virtual/index-sync (virtual/grid-index 200) identity {} items)]
    (is (= 66 (count expect)))
    (is (= expect (virtual/index-query idx area)))
    (let [moved (assoc items 5 {:id 5 :x 5000 :y 5000 :w 40 :h 40})
          idx2 (virtual/index-sync idx identity items moved)
          idx3 (virtual/index-patch idx identity moved [:m {:set {5 (get moved 5)}}])]
      (is (= (disj expect 5) (virtual/index-query idx2 area)))
      (is (= #{5} (virtual/index-query idx2 {:x 5010 :y 5010 :w 5 :h 5})))
      (is (= idx2 idx3) "a known delta gives the same index")
      (is (= (virtual/index-sync (virtual/grid-index 200) identity {} moved) idx2)
          "incremental equals rebuilt"))))

;; ---------------------------------------------------------------- M31 keyed loops

(def board-family (shared/atom-family {:init (fn [_] {}) :idle-ms nil}))

(r/defn Board [id]
  [:div
   (r/for [it (r/server (r/watch (board-family id))) :keyed true]
     [:p {:id (str "i" (:id it))} (:label it)])])

(deftest keyed-loop-visits-only-touched-entries
  (let [a (board-family ::b)
        _ (reset! a (into {} (for [i (range 1000)] [i {:id i :label (str "n" i)}])))
        {:keys [dom client] :as p} (render Board ::b)
        visits #(:for-visits (rt/counters client) 0)
        label #(some-> (h/query dom (str "#i" %)) h/text-content)]
    (is (= 1000 (count (h/query-all dom "p"))))
    (testing "a field change"
      (let [v0 (visits)]
        (ct/clear-wire! p)
        (swap! a assoc-in [7 :label] "seven")
        (ct/flush! p)
        (is (= "seven" (label 7)))
        (is (= 1 (- (visits) v0)) "one entry visited, not 1000")
        (is (< (ct/bytes-sent p :s->c) 30) "only the changed field crossed")))
    (testing "add and remove"
      (let [v0 (visits)]
        (swap! a #(-> % (dissoc 3) (assoc 2000 {:id 2000 :label "new"})))
        (ct/flush! p)
        (is (nil? (label 3)))
        (is (= "new" (label 2000)))
        (is (= 1000 (count (h/query-all dom "p"))))
        (is (= 2 (- (visits) v0)))))
    (testing "a full value still reconciles correctly"
      (reset! a {1 {:id 1 :label "only"}})
      (ct/flush! p)
      (is (= ["only"] (mapv h/text-content (h/query-all dom "p")))))))

;; ---------------------------------------------------------------- M32 history

(require '[hypercurve.history :as history] '[hypercurve.undo :as undo])

(defn- history-fixture []
  (let [clk (clock/virtual-clock)
        sealed (atom [])
        rec (history/recorder {:clock clk :window-ms 1000 :on-seal #(swap! sealed conj %)})
        a ((shared/atom-family {:init (fn [_] {}) :idle-ms nil :on-change #(history/record! rec %)}) ::h)]
    {:clk clk :sealed sealed :rec rec :a a}))

(deftest history-merges-adjacent-changes
  (let [{:keys [clk sealed rec a]} (history-fixture)
        ada {:user :ada} bob {:user :bob}]
    (shared/swap-meta! a ada assoc 1 {:x 0 :y 0})
    (dotimes [i 50] (shared/swap-meta! a ada assoc-in [1 :x] i))
    (shared/swap-meta! a ada assoc 2 {:x 5})
    (shared/swap-meta! a ada dissoc 2)
    (is (empty? @sealed) "still open")
    (clock/advance! clk 1000)
    (let [[v] @sealed]
      (is (= 1 (count @sealed)) "idle seals")
      (is (= {[1] [history/absent {:x 49 :y 0}]} (:changes v))
          "create + 50 moves = one entry; create + delete = nothing")
      (is (= 52 (:count v)) "writes that changed nothing are not counted")
      (is (= 1 (:seq v))))
    (testing "another user's write to the same field seals the open version"
      (shared/swap-meta! a ada assoc-in [1 :x] 100)
      (shared/swap-meta! a bob assoc-in [1 :y] 7)
      (is (= 1 (count @sealed)) "different fields: both stay open")
      (shared/swap-meta! a bob assoc-in [1 :x] 200)
      (is (= 2 (count @sealed)))
      (is (= {[1 :x] [49 100]} (:changes (last @sealed))))
      (history/seal-all! rec)
      (is (= {[1 :y] [0 7] [1 :x] [100 200]} (:changes (last @sealed)))))
    (testing "net-zero versions are not stored"
      (shared/swap-meta! a ada assoc-in [1 :y] 99)
      (shared/swap-meta! a ada assoc-in [1 :y] 7)
      (history/seal-all! rec)
      (is (= 3 (count @sealed))))
    (testing "replay rebuilds every version; restore is a version too"
      (is (= @a (history/replay {} @sealed)))
      (let [v1 (history/replay {} (take 1 @sealed))]
        (is (= {1 {:x 49 :y 0}} v1))
        (shared/swap-meta! a ada assoc 3 {:x 1})
        (history/restore! a v1 {:user :ada :target 1})
        (is (= v1 @a))
        (let [[edit restore] (take-last 2 @sealed)]
          (is (= :edit (:kind edit)) "the open version was sealed first")
          (is (= [:restore 1] [(:kind restore) (:target restore)]))
          (is (= @a (history/replay {} @sealed)) "replay includes the restore")
          (is (= {:x 200 :y 7} (get (history/apply-version @a restore :backward) 1))
              "a restore can be undone like any version"))))))

;; ---------------------------------------------------------------- M33 undo

(deftest undo-reverts-only-own-changes
  (let [clk (clock/virtual-clock)
        um (undo/manager {:clock clk})
        a ((shared/atom-family {:init (fn [_] {}) :idle-ms nil :clock clk :on-change #(undo/record! um %)}) ::u)
        ada {:user :ada} bob {:user :bob}]
    (shared/swap-meta! a ada assoc 1 {:x 0 :color "red"})
    (clock/advance! clk 1000)
    (dotimes [i 5] (shared/swap-meta! a ada assoc-in [1 :x] (inc i)) (clock/advance! clk 100))
    (clock/advance! clk 1000)
    (shared/swap-meta! a bob assoc-in [1 :color] "blue")
    (testing "a drag undoes in one step and keeps bob's color"
      (is (= {[1 :x] [5 0]} (undo/undo! um a :ada)))
      (is (= {1 {:x 0 :color "blue"}} @a)))
    (testing "redo"
      (undo/redo! um a :ada)
      (is (= 5 (get-in @a [1 :x]))))
    (testing "a field someone else changed since is skipped"
      (shared/swap-meta! a bob assoc-in [1 :x] 42)
      (is (nil? (undo/undo! um a :ada))
          "the move (bob moved it since) and the creation (bob edited it) are both skipped")
      (is (= {1 {:x 42 :color "blue"}} @a) "nothing of bob's is lost"))
    (testing "a new change clears redo"
      (undo/undo! um a :ada)
      (shared/swap-meta! a ada assoc 9 {:x 1})
      (is (not (undo/can-redo? um a :ada))))
    (testing "undoing a creation nobody touched removes it"
      (is (= {[9] [{:x 1} history/absent]} (undo/undo! um a :ada)))
      (is (not (contains? @a 9))))))

;; ---------------------------------------------------------------- M34 client cache

(r/defn Nav []
  (let [!at (atom ::ca) at (r/watch !at)]
    [:div
     [:button.a {:on-click (fn [_] (reset! !at ::ca))} "a"]
     [:button.b {:on-click (fn [_] (reset! !at ::cb))} "b"]
     (Board at)]))

(deftest client-cache-resumes-with-deltas
  (reset! (board-family ::ca) (into {} (for [i (range 300)] [i {:id i :label (str "a" i)}])))
  (reset! (board-family ::cb) (into {} (for [i (range 300)] [i {:id i :label (str "b" i)}])))
  (let [{:keys [dom client] :as p} (render Nav)
        go! (fn [b] (h/fire! (h/query dom (str "button." b)) "click" {}) (ct/flush! p))
        label #(some-> (h/query dom (str "#i" %)) h/text-content)
        full (do (ct/clear-wire! p) (go! "b") (ct/bytes-sent p :s->c))]
    (is (= "b7" (label 7)))
    (is (> full 2000) "first visit: the whole board")
    (testing "back to a board unchanged since: only the slot number"
      (ct/clear-wire! p) (go! "a")
      (is (= "a7" (label 7)))
      (is (< (ct/bytes-sent p :s->c) 20)))
    (testing "changed while away: only the delta"
      (swap! (board-family ::cb) assoc-in [7 :label] "B7")
      (ct/flush! p)
      (ct/clear-wire! p) (go! "b")
      (is (= "B7" (label 7)))
      (is (= 300 (count (h/query-all dom "p"))))
      (is (< (ct/bytes-sent p :s->c) 40)))
    (testing "a client that lost its cache asks for the full value"
      (vreset! (:slot-vals client) {})
      (go! "a")
      (is (= "a7" (label 7)))
      (is (= 300 (count (h/query-all dom "p")))))))

(r/defn ServerSum [id]
  (let [n (r/server (reduce + (r/for [it (r/watch (board-family id)) :keyed true] (count (:label it)))))]
    [:span.sum (str n)]))

(deftest keyed-loop-on-the-server-reads-shared-deltas
  (let [a (board-family ::s)
        _ (reset! a (into {} (for [i (range 500)] [i {:id i :label "xx"}])))
        {:keys [dom server] :as p} (render ServerSum ::s)
        visits #(:for-visits (rt/counters server) 0)]
    (is (= "1000" (h/text-content (h/query dom ".sum"))))
    (let [v0 (visits)]
      (swap! a assoc-in [3 :label] "xxxx")
      (ct/flush! p)
      (is (= "1002" (h/text-content (h/query dom ".sum"))))
      (is (= 1 (- (visits) v0)) "the server loop used the atom's delta"))))
