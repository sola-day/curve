(ns sqlite-table.app
  "Login, then a live, editable products table shared by every session."
  (:require [curve.core :as r]
            #?(:clj [sqlite-table.db :as db])))

(r/defn ProductRow [{:keys [id name price]} update! delete!]
  [:tr
   [:td.id id]
   [:td [:input.name {:value name :on-change (fn [e] (update! id :name (r/event-value e)))}]]
   [:td [:input.price {:value price :on-change (fn [e] (update! id :price (r/event-value e)))}]]
   [:td [:button.delete {:on-click (fn [_] (delete! id))} "delete"]]])

(r/defn Products [user]
  (let [rows (r/shared ::products (r/watch (db/products-ref)))
        ;; server closures: they run with this session's user, checked server-side
        update! (r/server (fn [id field v] (when user (db/update-field! id field v)) nil))
        delete! (r/server (fn [id] (when user (db/delete-product! id)) nil))
        add! (r/server (fn [] (when user (db/add-product! "New product" 0.0)) nil))]
    [:div
     [:table
      [:thead [:tr [:th "id"] [:th "name"] [:th "price"] [:th]]]
      [:tbody (r/for [p rows :by :id] (ProductRow p update! delete!))]]
     [:button.add {:on-click (fn [_] (add!))} "add product"]
     [:p.count (count rows) " products"]]))

(r/defn LoginForm [login!]
  (let [!form (atom {:name "" :password ""})
        {:keys [name password error]} (r/watch !form)]
    [:form.login {:on-submit (fn [e] #?(:cljs (when-not (map? e) (.preventDefault e)))
                               (let [r (login! name password)]
                                 #?(:cljs (add-watch r ::login (fn [_ _ _ ok] (when-not ok (swap! !form assoc :error "wrong name or password"))))
                                    :clj nil)))}
     [:input.user {:value name :placeholder "name" :on-input (fn [e] (swap! !form assoc :name (r/event-value e)))}]
     [:input.password {:type "password" :value password :placeholder "password"
                       :on-input (fn [e] (swap! !form assoc :password (r/event-value e)))}]
     [:button.login {:type "submit"} "log in"]
     [:p.error error]]))

(r/defn App []
  (let [!user (r/server (atom nil))
        user (r/server (r/watch !user))
        login! (r/server (fn [n p] (boolean (when-let [u (db/check-login n p)] (reset! !user u)))))
        logout! (r/server (fn [] (reset! !user nil) nil))]
    [:main
     [:h1 "Products"]
     (if user
       [:div
        [:p.who "Logged in as " [:b user] " " [:button.logout {:on-click (fn [_] (logout!))} "log out"]]
        (Products user)]
       (LoginForm login!))]))
