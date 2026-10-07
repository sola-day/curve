(ns hypercurve.undo
  "Per-user undo/redo for shared atoms (milestone M33).

  Each user has a stack per atom key. Undo reverts only that user's own
  changes, path by path, and only where the value is still what the user
  left: a field someone else has changed since is skipped, never clobbered.
  Changes by the same user within :merge-ms that touch the same entities
  are one step (a drag of several updates undoes at once).

    (def um (undo/manager {}))
    (shared/atom-family {... :on-change #(undo/record! um %)})
    (undo/undo! um (room id) uid)      ; => the paths reverted, or nil
    (undo/redo! um (room id) uid)

  The writes undo! and redo! make carry {::undo true} in their metadata and
  are not recorded as new steps; any other change by the user clears the
  user's redo stack."
  (:require [hypercurve.clock :as clock]
            [hypercurve.history :as history]
            [hypercurve.shared :as shared]))

(defn manager
  "opts: :limit steps kept per user and key (default 30), :merge-ms (500),
  :depth path depth (2), :clock."
  [opts]
  {:opts (merge {:limit 30 :merge-ms 500 :depth 2} opts)
   :state (atom {})})

(defn- roots [changes] (into #{} (map first) (keys changes)))

(defn record!
  "Fold one SharedAtom change into its user's undo stack."
  [{:keys [opts state]} {:keys [key old new meta at] :as change}]
  (let [user (:user meta)]
    (when (and user (not (::undo meta)))
      (let [at (or at (clock/now (or (:clock opts) clock/host)))
            paths (history/changed-paths change (:depth opts))
            step (reduce #(history/fold %1 %2 old new) {} paths)]
        (when (seq step)
          (swap! state update [key user]
                 (fn [{:keys [undo]}]
                   (let [top (peek undo)
                         merge? (and top (< (- at (:at top)) (:merge-ms opts))
                                     (= (roots (:changes top)) (roots step)))
                         undo (if merge?
                                (conj (pop undo)
                                      {:at at :changes (reduce #(history/fold %1 %2 old new) (:changes top) paths)})
                                (conj (or undo []) {:at at :changes step}))
                         undo (if (> (count undo) (:limit opts)) (subvec undo 1) undo)]
                     ;; a merged step that cancelled out is no step
                     {:undo (if (empty? (:changes (peek undo))) (pop undo) undo)
                      :redo []}))))))))

(defn- revert!
  "Pop steps from stack until one reverts something; push the reverted part
  onto the other stack. Returns {path [from to]} for the paths it set."
  [{:keys [state]} sa user from to]
  (let [k [(.-key ^hypercurve.shared.SharedAtom sa) user]]
    (loop []
      (when-let [step (peek (get-in @state [k from]))]
        (swap! state update-in [k from] pop)
        (let [applied (volatile! {})]
          (shared/swap-meta!
            sa {:user user ::undo true}
            (fn [v]
              (vreset! applied {})
              (reduce-kv (fn [v p [before after]]
                           (if (= (history/get-path v p) after)
                             (do (vswap! applied assoc p [after before])
                                 (history/set-path v p before))
                             v))
                         v (:changes step))))
          (if (seq @applied)
            (do (swap! state update-in [k to] (fnil conj []) {:at (:at step) :changes @applied})
                @applied)
            (recur)))))))

(defn undo! [um sa user] (revert! um sa user :undo :redo))
(defn redo! [um sa user] (revert! um sa user :redo :undo))

(defn can-undo? [{:keys [state]} sa user]
  (boolean (seq (get-in @state [[(.-key ^hypercurve.shared.SharedAtom sa) user] :undo]))))

(defn can-redo? [{:keys [state]} sa user]
  (boolean (seq (get-in @state [[(.-key ^hypercurve.shared.SharedAtom sa) user] :redo]))))

(defn forget!
  "Drop key's stacks (all users), e.g. when its atom unloads."
  [{:keys [state]} key]
  (swap! state #(into {} (remove (fn [[[k _] _]] (= k key))) %)))
