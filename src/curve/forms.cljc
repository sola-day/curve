(ns curve.forms
  "Forms: state, field binding, client validation and server submission.

    (let [!f (forms/state {:email \"\"})]
      [:form {:on-submit (fn [e] (forms/submit! !f validators save!))}
       (forms/Field !f :email \"Email\")
       [:button \"Save\"]])

  validators: {field (fn [value values] error-string-or-nil)}. save! is
  usually a server fn (validate on the server too, ^{:validate ...}); it
  returns nil on success or {field error} to show next to the fields."
  (:require [curve.core :as r]))

(defn state
  "A form's client state."
  [initial]
  (atom {:initial initial :values initial :errors {} :touched #{} :status :idle}))

(defn validate [validators values]
  (into {} (keep (fn [[k f]] (when-let [e (f (get values k) values)] [k e]))) validators))

(defn bind
  "Attributes binding an input to field k (use with {:& ...})."
  [st !form k]
  {:value (str (get-in st [:values k] ""))
   :on-input (fn [e] (swap! !form assoc-in [:values k] (r/event-value e)))
   :on-blur (fn [_] (swap! !form update :touched conj k))})

(defn submit!
  "Validate; when clean, call send! with the values. Its result (a watchable
  returned by a server fn, or a plain value) nil means success, a map means
  field errors from the server."
  [!form validators send!]
  (let [{:keys [values]} @!form
        errors (validate validators values)]
    (if (seq errors)
      (swap! !form assoc :errors errors :status :invalid)
      (let [done (fn [res]
                   (cond
                     (map? res) (swap! !form assoc :errors res :status :invalid)
                     (r/failure? res) (swap! !form assoc :errors {:form (str (:error res))} :status :error)
                     :else (swap! !form assoc :errors {} :status :saved)))
            res (do (swap! !form assoc :errors {} :status :submitting) (send! values))]
        (if (instance? #?(:clj clojure.lang.IRef :cljs cljs.core/IWatchable) res)
          (let [k (gensym "submit")]
            (add-watch res k (fn [_ _ _ v] (when-not (r/pending? v) (remove-watch res k) (done v))))
            (when-not (r/pending? @res) (remove-watch res k) (done @res)))
          (done res))))))

(defn reset-form! [!form] (swap! !form #(assoc % :values (:initial %) :errors {} :touched #{} :status :idle)))

(r/defn Field [!form k label & [type]]
  (let [st (r/watch !form)
        err (get-in st [:errors k])]
    [:label {:class (if err "field invalid" "field")}
     [:span.label label]
     [:input {:& (bind st !form k) :type (or type "text") :name (name k)}]
     (when err [:span.error err])]))
