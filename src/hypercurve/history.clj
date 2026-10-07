(ns hypercurve.history
  "Version history for shared atoms (design §10, milestone M32).

  Every change of a SharedAtom (listen! / a family's :on-change) is folded
  into an open *version* per (atom key, user). A version keeps, per changed
  path, the value before the version started and the latest value after it,
  so a field changed a hundred times costs one entry, and a field changed
  back, or an entity created and deleted again, costs nothing.

  A version is sealed (handed to :on-seal, given the next :seq) when
    - its user has made no change for :window-ms,
    - another user changes a path it holds (so replaying sealed versions in
      seq order never reorders two writes of the same path),
    - a restore happens, or seal-all! is called (e.g. on unload).

  Paths go :depth levels into the value (default 2: entity id, then field).
  Storage is the application's: persist sealed versions and, now and then,
  snapshots; (replay snapshot versions) rebuilds any version.

    (def rec (history/recorder {:on-seal store/save-version!}))
    (shared/atom-family {... :on-change #(history/record! rec %)})
    (history/restore! (room id) (history/replay snap versions) {:user u :target 41})"
  (:require [hypercurve.clock :as clock]
            [hypercurve.delta :as delta]
            [hypercurve.shared :as shared]
            [hypercurve.timing :as timing]))

;; ---------------------------------------------------------------- paths

(def absent
  "The value at a path that does not exist."
  ::absent)

(defn get-path [m path]
  (loop [m m [k & more :as p] (seq path)]
    (cond
      (nil? p) m
      (and (map? m) (contains? m k)) (recur (get m k) more)
      :else absent)))

(defn set-path
  "assoc-in, or remove the key when v is absent."
  [m path v]
  (cond
    (empty? path) (if (= v absent) nil v)
    (= v absent) (if (= 1 (count path))
                   (dissoc m (first path))
                   (let [parent (get-path m (pop (vec path)))]
                     (if (map? parent) (update-in m (pop (vec path)) dissoc (peek (vec path))) m)))
    :else (assoc-in m path v)))

(defn- diff-paths
  "Paths (at most depth deep) where old and new differ."
  [prefix old new depth]
  (cond
    (identical? old new) nil
    (and (pos? depth) (map? old) (map? new))
    (mapcat (fn [k]
              (let [a (get old k absent) b (get new k absent)]
                (when-not (identical? a b)
                  (if (and (map? a) (map? b))
                    (diff-paths (conj prefix k) a b (dec depth))
                    (when (not= a b) [(conj prefix k)])))))
            (distinct (concat (keys old) (keys new))))
    (= old new) nil
    :else [prefix]))

(defn changed-paths
  "Paths a change touched, from its delta when possible (no diff)."
  ([change] (changed-paths change 2))
  ([{:keys [old new delta]} depth]
   (letfn [(walk [prefix old new d depth]
             (let [[t x] d]
               (cond
                 (zero? depth) [prefix]
                 (= t :m)
                 (concat
                   (map #(conj prefix %) (:dissoc x))
                   (mapcat (fn [[k v]] (diff-paths (conj prefix k) (get old k absent) v (dec depth))) (:set x))
                   (mapcat (fn [[k dd]] (walk (conj prefix k) (get old k) (get new k) dd (dec depth))) (:patch x)))
                 (= t :v) (diff-paths prefix old new depth)
                 :else [prefix])))]
     (distinct (walk [] old new (or delta (delta/diff old new)) depth)))))

;; ---------------------------------------------------------------- folding

(defn- prefix? [a b] (and (<= (count a) (count b)) (= a (subvec b 0 (count a)))))

(defn- overlaps? [changes p]
  (some #(or (prefix? % p) (prefix? p %)) (keys changes)))

(defn fold
  "Fold the change of path p (old and new are the whole values before and
  after) into changes {path [before after]}; net-zero entries vanish."
  [changes p old new]
  (let [p (vec p)
        ancestor (some #(when (prefix? % p) %) (keys changes))]
    (if ancestor
      (let [[before _] (get changes ancestor) after (get-path new ancestor)]
        (if (= before after) (dissoc changes ancestor) (assoc changes ancestor [before after])))
      (let [descendants (filter #(prefix? p %) (keys changes))
            ;; the value at p before this version: now, with each
            ;; descendant's own before put back
            before (reduce (fn [v d] (let [rel (subvec d (count p))
                                           b (first (get changes d))]
                                       (if (empty? rel) b (set-path (if (= v absent) {} v) rel b))))
                           (get-path old p) descendants)
            after (get-path new p)
            changes (apply dissoc changes descendants)]
        (if (= before after) changes (assoc changes p [before after]))))))

(defn apply-version
  "value with version's changes applied :forward (afters) or :backward
  (befores)."
  ([value version] (apply-version value version :forward))
  ([value {:keys [changes]} dir]
   (let [pick (if (= dir :forward) second first)]
     (reduce-kv (fn [v p ba] (set-path v p (pick ba)))
                ;; a version never holds a path and one of its prefixes
                value changes))))

(defn replay
  "The value after applying versions (in :seq order) to base."
  [base versions]
  (reduce apply-version base (sort-by :seq versions)))

;; ---------------------------------------------------------------- recorder

(defn recorder
  "opts:
    :on-seal    (fn [version]) a sealed version, ready to store:
                {:key :seq :user :kind (:edit|:restore) :start :end :from :to
                 :count :changes {path [before after]} :target}
    :on-update  (fn [version]) the open version changed (throttled by
                :update-ms, default 5000), for saving work in progress
    :window-ms  idle time that seals a user's version (default 600000)
    :depth      path depth (default 2)
    :next-seq   (fn [key]) the next seq for key, when versions were stored
                before this process started (default from 1)
    :clock      a hypercurve.clock Clock"
  [opts]
  {:opts (merge {:window-ms 600000 :depth 2 :update-ms 5000} opts)
   :state (atom {:open {} :seq {}})})

(declare seal!)

(defn- next-seq! [{:keys [opts state]} key]
  (let [cur (get-in @state [:seq key])
        n (if cur (inc cur) ((or (:next-seq opts) (constantly 1)) key))]
    (swap! state assoc-in [:seq key] n)
    n))

(defn- emit! [rec v]
  (let [v (-> v (dissoc :timer :updater) (assoc :seq (next-seq! rec (:key v))))]
    ((or (:on-seal (:opts rec)) (fn [_])) v)
    v))

(defn seal!
  "Seal the open version of (key, user), if it has changes."
  [rec key user]
  (locking rec
    (when-let [v (get-in @(:state rec) [:open [key user]])]
      (swap! (:state rec) update :open dissoc [key user])
      (some-> (:timer v) timing/cancel!)
      (some-> (:updater v) timing/cancel!)
      (when (seq (:changes v)) (emit! rec v)))))

(defn seal-all!
  "Seal every open version of key (all keys when key is omitted)."
  ([rec] (doseq [[k u] (keys (:open @(:state rec)))] (seal! rec k u)))
  ([rec key] (doseq [[k u] (keys (:open @(:state rec))) :when (= k key)] (seal! rec k u))))

(defn open-versions [rec key]
  (into {} (for [[[k u] v] (:open @(:state rec)) :when (= k key)]
             [u (dissoc v :timer :updater)])))

(defn record!
  "Fold one SharedAtom change into the history."
  [rec {:keys [key old new version meta at] :as change}]
  (let [{:keys [opts state]} rec
        user (:user meta)
        restore (::restore meta)
        clk (or (:clock opts) clock/host)
        at (or at (clock/now clk))
        paths (changed-paths change (:depth opts))]
    (locking rec
      ;; another user's open version holding one of these paths is sealed
      ;; first; a restore seals them all
      (doseq [[[k u] v] (:open @state)
              :when (and (= k key) (not= u user)
                         (or restore (some #(overlaps? (:changes v) %) paths)))]
        (seal! rec k u))
      (if restore
        (do (seal! rec key user)
            (emit! rec {:key key :user user :kind :restore :target restore :start at :end at
                        :from (dec version) :to version :count 1
                        :changes (reduce #(fold %1 %2 old new) {} paths)}))
        (let [ok [key user]
              v (or (get-in @state [:open ok])
                    {:key key :user user :kind :edit :start at :from (dec version) :count 0 :changes {}
                     :timer (timing/debounce (:window-ms opts) {:clock clk} #(seal! rec key user))
                     :updater (when-let [f (:on-update opts)]
                                (timing/throttle (:update-ms opts) {:clock clk}
                                                 #(when-let [v (get-in @state [:open ok])]
                                                    (f (dissoc v :timer :updater)))))})
              v (-> v
                    (assoc :end at :to version)
                    (update :count inc)
                    (update :changes #(reduce (fn [c p] (fold c p old new)) % paths)))]
          (swap! state assoc-in [:open ok] v)
          ((:timer v))
          (some-> (:updater v) (apply [])))))
    nil))

(defn restore!
  "Make sa's value target, recorded as one :restore version (open versions
  of sa's key are sealed first). meta must carry :user; :target names the
  version restored (stored with it)."
  [sa value meta]
  (shared/swap-meta! sa (assoc meta ::restore (or (:target meta) true)) (constantly value)))
