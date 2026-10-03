(ns curve.batch
  "Cross-session query batching (design §8.6 item 5), DataLoader style.
  Every load within a short window, from any session, becomes one call of
  batch-fn with all the keys; each caller gets its own result.

    (def user-loader (batch/loader (fn [ids] (db/users-by-ids ids))))
    ;; in a reactive fn, on the server:
    (r/offload @(batch/load user-loader id))"
  (:refer-clojure :exclude [load])
  (:import [java.util.concurrent CompletableFuture Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(defonce ^:private ^ScheduledExecutorService timer
  (Executors/newSingleThreadScheduledExecutor
    (reify ThreadFactory (newThread [_ r] (doto (Thread. ^Runnable r "curve-batch") (.setDaemon true))))))

(defn loader
  "batch-fn: (fn [keys]) -> {key value}. opts: :window-ms (default 2)."
  [batch-fn & {:keys [window-ms] :or {window-ms 2}}]
  {:batch-fn batch-fn :window-ms window-ms :pending (atom nil) :batches (atom 0)})

(defn- run-batch! [{:keys [batch-fn pending batches]}]
  (let [[old _] (swap-vals! pending (constantly nil))]
    (when (seq old)
      (swap! batches inc)
      (try
        (let [result (batch-fn (set (keys old)))]
          (doseq [[k ^CompletableFuture f] old] (.complete f (get result k))))
        (catch Throwable e
          (doseq [[_ ^CompletableFuture f] old] (.completeExceptionally f e)))))))

(defn load
  "A future (deref-able) of key's value, batched with concurrent loads."
  [{:keys [pending window-ms] :as l} key]
  (let [fresh (CompletableFuture.)
        [old new] (swap-vals! pending (fn [m] (if (contains? m key) m (assoc m key fresh))))]
    (when (empty? old)
      (.schedule timer ^Runnable #(run-batch! l) (long window-ms) TimeUnit/MILLISECONDS))
    (let [^CompletableFuture f (get new key)]
      (reify clojure.lang.IDeref (deref [_] (.get f))))))

(defn batch-count [l] @(:batches l))
