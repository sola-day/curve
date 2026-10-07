(ns todomvc.app
  "TodoMVC: all state is client-local; nothing crosses the wire."
  (:require [clojure.string :as str]
            [hypercurve.core :as r]))

(defn add-todo [state title]
  (let [t (str/trim title)]
    (if (str/blank? t)
      state
      (-> state
          (update :todos conj {:id (:next-id state) :title t :done false})
          (update :next-id inc)))))

(defn toggle [state id] (update state :todos (fn [ts] (mapv #(if (= id (:id %)) (update % :done not) %) ts))))
(defn destroy [state id] (update state :todos (fn [ts] (filterv #(not= id (:id %)) ts))))
(defn toggle-all [state done] (update state :todos (fn [ts] (mapv #(assoc % :done done) ts))))
(defn clear-completed [state] (update state :todos (fn [ts] (filterv (complement :done) ts))))

(defn visible [todos filter]
  (case filter
    :active (filterv (complement :done) todos)
    :completed (filterv :done todos)
    todos))

(r/defn TodoItem [!state {:keys [id title done]}]
  [:li {:class (when done "completed")}
   [:div.view
    [:input.toggle {:type "checkbox" :checked done :on-change (fn [_] (swap! !state toggle id))}]
    [:label title]
    [:button.destroy {:on-click (fn [_] (swap! !state destroy id))}]]])

(r/defn FilterLink [!state current value label]
  [:li [:a {:class (when (= current value) "selected") :href (str "#/" (name value))
            :on-click (fn [_] (swap! !state assoc :filter value))}
        label]])

(r/defn App []
  (let [!state (atom {:todos [] :next-id 1 :filter :all :draft ""})
        {:keys [todos filter draft]} (r/watch !state)
        active (count (remove :done todos))]
    [:section.todoapp
     [:header.header
      [:h1 "todos"]
      [:input.new-todo {:placeholder "What needs to be done?" :value draft
                        :on-input (fn [e] (swap! !state assoc :draft (r/event-value e)))
                        :on-keydown (fn [e] (when (= "Enter" (r/event-key e))
                                              (swap! !state #(-> % (add-todo (:draft %)) (assoc :draft "")))))}]]
     (when (seq todos)
       [:section.main
        [:input#toggle-all.toggle-all {:type "checkbox" :checked (zero? active)
                                       :on-change (fn [_] (swap! !state toggle-all (pos? active)))}]
        [:ul.todo-list
         (r/for [t (visible todos filter) :by :id] (TodoItem !state t))]])
     (when (seq todos)
       [:footer.footer
        [:span.todo-count [:strong active] (if (= 1 active) " item left" " items left")]
        [:ul.filters
         (FilterLink !state filter :all "All")
         (FilterLink !state filter :active "Active")
         (FilterLink !state filter :completed "Completed")]
        (when (some :done todos)
          [:button.clear-completed {:on-click (fn [_] (swap! !state clear-completed))} "Clear completed"])])]))
