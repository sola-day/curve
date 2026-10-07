(ns hypercurve.timing
  "Rate control for plain functions: debounce and throttle.

    (def save! (timing/debounce 300 (fn [text] ...)))       ; quiet 300 ms, then once
    (def move! (timing/throttle 33 (fn [pos] ...)))          ; at most every 33 ms
    (save! \"a\") (save! \"ab\")                              ; => one call with \"ab\"
    (timing/flush! save!)                                    ; run a pending call now
    (timing/cancel! save!)                                   ; drop it

  Both always call with the latest arguments. Time comes from a
  hypercurve.clock Clock (opts :clock, default the host clock), so tests drive
  them with a virtual clock. Options:
    debounce  :max-wait ms  call at least this often while calls keep coming
              :leading true also call on the first of a burst
    throttle  :trailing false  drop the last call of a burst instead of
              running it when the interval ends"
  (:refer-clojure :exclude [flush])
  (:require [hypercurve.clock :as clock]))

(defprotocol Pending
  (flush! [d] "Run the pending call now, if any. Returns true if one ran.")
  (cancel! [d] "Drop the pending call, if any.")
  (pending? [d] "Is a call waiting?"))

(declare fire! call! arm!)

(deftype Limited [f clk opts state]
  Pending
  (flush! [d] (boolean (fire! d)))
  (cancel! [d] (locking d
                 (when-let [c (:timer @state)] (c))
                 (vswap! state assoc :args nil :timer nil :first nil)
                 nil))
  (pending? [d] (some? (:args @state)))
  #?@(:clj
      [clojure.lang.IFn
       (invoke [d] (call! d []))
       (invoke [d a] (call! d [a]))
       (invoke [d a b] (call! d [a b]))
       (invoke [d a b c] (call! d [a b c]))
       (invoke [d a b c e] (call! d [a b c e]))
       (applyTo [d args] (call! d (vec args)))]
      :cljs
      [IFn
       (-invoke [d] (call! d []))
       (-invoke [d a] (call! d [a]))
       (-invoke [d a b] (call! d [a b]))
       (-invoke [d a b c] (call! d [a b c]))
       (-invoke [d a b c e] (call! d [a b c e]))]))

;; state: {:args [..] | nil, :timer cancel-fn, :first ms (first call of the
;; burst), :last-run ms}
(defn- fire! [^Limited d]
  ;; take the pending args under the lock, call outside it
  (let [args (locking d
               (let [st @(.-state d)]
                 (when-let [c (:timer st)] (c))
                 (when-let [a (:args st)]
                   (vswap! (.-state d) assoc :args nil :timer nil :first nil
                           :last-run (clock/now (.-clk d)))
                   a)))]
    (when args (apply (.-f d) args) true)))

(defn- call! [^Limited d args]
  (let [clk (.-clk d)
        now (clock/now clk)
        run-now
        (locking d
          (let [{:keys [first last-run] :as st} @(.-state d)
                {:keys [wait max-wait leading throttle trailing]} (.-opts d)]
            (if throttle
              (let [due (if last-run (- (+ last-run wait) now) 0)]
                (cond
                  (<= due 0) (do (vswap! (.-state d) assoc :last-run now :args nil) args)
                  (false? trailing) nil
                  :else (do (vswap! (.-state d) assoc :args args)
                            (when-not (:timer st) (arm! d due))
                            nil)))
              (let [burst-start (or first now)]
                (if (and leading (nil? first) (or (nil? last-run) (>= (- now last-run) wait)))
                  (do (vswap! (.-state d) assoc :first now :last-run now :args nil)
                      (arm! d wait)
                      args)
                  (do (vswap! (.-state d) assoc :args args :first burst-start)
                      (if (and max-wait (>= (- now burst-start) max-wait))
                        ::now
                        (arm! d (if max-wait (min wait (- (+ burst-start max-wait) now)) wait)))
                      nil))))))]
    (cond
      (= run-now ::now) (fire! d)
      run-now (apply (.-f d) run-now))
    nil))

(defn- arm!
  "(Re)start the timer: delay ms from now. Called under the lock."
  [^Limited d delay-ms]
  (when-let [c (:timer @(.-state d))] (c))
  (let [c (clock/schedule! (.-clk d) delay-ms (fn [] (fire! d)))]
    (vswap! (.-state d) assoc :timer c)))

(defn debounce
  "f wrapped so that a burst of calls becomes one call with the last
  arguments, wait ms after the burst goes quiet."
  ([wait f] (debounce wait {} f))
  ([wait {:keys [clock] :as opts} f]
   (Limited. f (or clock clock/host) (assoc opts :wait wait) (volatile! {}))))

(defn throttle
  "f wrapped so that it runs at most once every interval ms: the first call
  of a burst at once, the last one (latest arguments) when the interval ends."
  ([interval f] (throttle interval {} f))
  ([interval {:keys [clock] :as opts} f]
   (Limited. f (or clock clock/host) (assoc opts :wait interval :throttle true) (volatile! {}))))
