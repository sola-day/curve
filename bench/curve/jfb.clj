(ns curve.jfb
  "js-framework-benchmark style operations on the headless DOM, as ratios
  against direct DOM manipulation (design §17.6: compare ratios, not
  milliseconds, so the check holds on any machine).
  Run: clojure -M:bench -m curve.jfb [--check bench/baseline.edn]"
  (:require [clojure.edn :as edn]
            [curve.core :as r]
            [curve.dom-api :as d]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.runtime :as rt]
            [curve.select :as select]))

(defn- now [] (System/nanoTime))
(defn- median
  "Median time of the op returned by setup (setup itself is not timed)."
  [n setup]
  (dotimes [_ 5] ((setup)))
  (nth (sort (for [_ (range n)] (let [op (setup) t (now)] (op) (- (now) t)))) (quot n 2)))

(defn rows [n start] (vec (for [i (range n)] {:id (+ start i) :label (str "row " (+ start i))})))

(r/defn Row [sel {:keys [id label]}]
  [:tr {:class (when (r/watch (select/is sel id)) "danger")} [:td id] [:td [:a label]]])

(r/defn Table [!rows !sel]
  [:table [:tbody (r/for [row (r/watch !rows) :by :id] (Row !sel row))]])

(defn curve-app []
  (let [root (h/root)
        peer (apply rt/peer :client (mapcat identity (dissoc (mount/renderer (h/dom) root) :mounter)))
        !rows (atom []) !sel (select/selector)]
    (rt/mount-root! peer Table !rows !sel)
    (rt/run! peer)
    {:run! #(rt/run! peer) :rows !rows :sel !sel}))

(defn vanilla-app []
  (let [dom (h/dom) root (h/root) tbody (h/element "tbody")]
    (h/insert-before root tbody nil)
    {:dom dom :tbody tbody :trs (atom [])}))

(defn- vanilla-row [dom {:keys [id label]}]
  (let [tr (h/element "tr") td1 (h/element "td") td2 (h/element "td") a (h/element "a")]
    (h/insert-before td1 (h/text (str id)) nil)
    (h/insert-before a (h/text label) nil)
    (h/insert-before td2 a nil)
    (h/insert-before tr td1 nil) (h/insert-before tr td2 nil)
    tr))

(def ops
  {:create-1k
   {:curve (fn [] (let [{run! :run! !rows :rows} (curve-app)] (fn [] (reset! !rows (rows 1000 0)) (run!))))
    :vanilla (fn [] (let [{:keys [dom tbody trs]} (vanilla-app)]
                      (fn [] (doseq [r (rows 1000 0)] (let [tr (vanilla-row dom r)] (h/insert-before tbody tr nil) (swap! trs conj tr))))))}
   :update-10th
   {:curve (fn [] (let [{run! :run! !rows :rows} (curve-app)] (reset! !rows (rows 1000 0)) (run!)
                    (fn [] (swap! !rows (fn [rs] (reduce #(update-in %1 [%2 :label] str " !") rs (range 0 1000 10)))) (run!))))
    :vanilla (fn [] (let [{:keys [dom tbody trs]} (vanilla-app)]
                      (doseq [r (rows 1000 0)] (let [tr (vanilla-row dom r)] (h/insert-before tbody tr nil) (swap! trs conj tr)))
                      (fn [] (doseq [i (range 0 1000 10)]
                               (let [t (first (h/children (first (h/children (second (h/children (nth @trs i)))))))]
                                 (d/set-text! dom t (str @(:text t) " !")))))))}
   :select
   {:curve (fn [] (let [{run! :run! !rows :rows sel :sel} (curve-app)] (reset! !rows (rows 1000 0)) (run!)
                    (fn [] (select/select! sel (if (= 5 (select/current sel)) 6 5)) (run!))))
    :vanilla (fn [] (let [{:keys [dom tbody trs]} (vanilla-app) cur (atom nil)]
                      (doseq [r (rows 1000 0)] (let [tr (vanilla-row dom r)] (h/insert-before tbody tr nil) (swap! trs conj tr)))
                      (fn [] (when-let [c @cur] (d/set-attr! dom c "class" nil))
                        (let [n (nth @trs (if (= @cur (nth @trs 5)) 6 5))] (d/set-attr! dom n "class" "danger") (reset! cur n)))))}
   :swap-rows
   {:curve (fn [] (let [{run! :run! !rows :rows} (curve-app)] (reset! !rows (rows 1000 0)) (run!)
                    (fn [] (swap! !rows (fn [rs] (assoc rs 1 (rs 998) 998 (rs 1)))) (run!))))
    :vanilla (fn [] (let [{:keys [dom tbody trs]} (vanilla-app)]
                      (doseq [r (rows 1000 0)] (let [tr (vanilla-row dom r)] (h/insert-before tbody tr nil) (swap! trs conj tr)))
                      (fn [] (let [a (nth @trs 1) b (nth @trs 998) after-b (nth @trs 999)]
                               (h/insert-before tbody b a) (h/insert-before tbody a after-b)
                               (swap! trs assoc 1 b 998 a)))))}
   :clear
   {:curve (fn [] (let [{run! :run! !rows :rows} (curve-app)]
                    (fn [] (reset! !rows (rows 1000 0)) (run!) (reset! !rows []) (run!))))
    :vanilla (fn [] (let [{:keys [dom tbody trs]} (vanilla-app)]
                      (fn [] (doseq [r (rows 1000 0)] (let [tr (vanilla-row dom r)] (h/insert-before tbody tr nil) (swap! trs conj tr)))
                        (doseq [tr @trs] (d/remove-node! dom tr)) (reset! trs []))))}})

(defn ratios []
  (into (sorted-map)
        (for [[k {:keys [curve vanilla]}] ops]
          (let [c (median 15 curve)
                v (median 15 vanilla)]
            [k (Double/parseDouble (format "%.1f" (/ (double c) v)))]))))

(defn -main [& args]
  (let [rs (ratios)]
    (println "curve / vanilla (headless DOM):" rs)
    (when (= "--check" (first args))
      (let [baseline (edn/read-string (slurp (second args)))
            over (filter (fn [[k v]] (> v (* 1.5 (get baseline k Double/MAX_VALUE)))) rs)]
        (if (seq over)
          (do (println "REGRESSION (more than 1.5x the baseline ratio):" (into {} over)) (System/exit 1))
          (println "ratios within 1.5x of" (second args)))))
    (System/exit 0)))
