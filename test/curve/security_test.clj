(ns curve.security-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.compiler :as compiler]
            [curve.core :as r]
            [curve.runtime :as rt]
            [curve.test :as ct]))

(def ^:secret api-key "sk-123")
(def User [:map [:id :int] [:name :string] [:password_hash {:secret true} :string]])
(defn all-users [] [{:id 1 :name "ada" :password_hash "x"}])
(defn get-user [] (first (all-users)))

(defn compile-error [src]
  (try (binding [*ns* (the-ns 'curve.security-test)] (eval (read-string src)) nil)
       (catch Exception e (ex-message (or (ex-cause e) e)))))

(deftest secret-vars
  (is (re-find #"secret" (str (compile-error "(r/defn A [] [:p (r/server (str api-key))])"))))
  (is (re-find #"outside server code" (str (compile-error "(r/defn A2 [] [:p (str api-key)])"))))
  (is (nil? (compile-error "(r/defn A3 [] [:p (r/server (count api-key))])")) "derived non-secret: count"))

(deftest secret-bindings
  (is (re-find #"secret value" (str (compile-error "(r/defn B [] (let [^:secret t (r/server (str \"tok\"))] [:p t]))"))))
  (is (nil? (compile-error "(r/defn B2 [] (let [^:secret t (r/server (str \"tok\")) ok (r/server (some? t))] [:p (str ok)]))"))
      "a size/predicate of a secret is not the secret"))

(deftest schema-keys
  (testing "literal-key access"
    (is (nil? (compile-error "(r/defn S1 [] (let [u ^{:schema User} (r/server (get-user))] [:p (r/server (:name u))]))")))
    (is (re-find #"secret" (str (compile-error "(r/defn S1b [] (let [u ^{:schema User} (r/server (get-user))] [:p (:name u)]))")))
        "reading a key on the client ships the whole record there")
    (is (re-find #"secret" (str (compile-error "(r/defn S2 [] (let [u ^{:schema User} (r/server (get-user))] [:p (:password_hash u)]))"))))
    (is (re-find #"secret" (str (compile-error "(r/defn S3 [] (let [u ^{:schema User} (r/server (get-user)) k :name] [:p (get u k)]))")))
        "non-literal key: whole value tainted"))
  (testing "collections"
    (is (re-find #"secret" (str (compile-error "(r/defn S4 [] (r/for [u ^{:schema [:vector User]} (r/server (all-users))] [:li (:name u)]))")))
        "iterating ships whole rows to the client")
    (is (nil? (compile-error "(r/defn S5 [] (let [us ^{:schema [:vector User]} (r/server (all-users))
                                                     safe (r/server (mapv #(dissoc % :password_hash) us))]
                                                 (r/for [u safe] [:li (:name u)])))"))
        "cleaned on the server")))

(r/defn ^:client Card [u] [:span (:name u)])

(deftest reactive-fn-arguments
  (is (re-find #"passed to" (str (compile-error "(r/defn C1 [] (let [u ^{:schema User} (r/server (get-user))] (Card u)))"))))
  (is (nil? (compile-error "(r/defn C2 [] (let [u ^{:schema User} (r/server (get-user))] (Card (r/server (select-keys u [:name])))))"))))

(deftest declassify
  (is (nil? (compile-error "(r/defn D1 [] (let [u ^{:schema User} (r/server (get-user))] [:p (r/declassify (r/server (:password_hash u)) \"admin audit view\")]))")))
  (is (re-find #"reason" (str (compile-error "(r/defn D2 [] [:p (r/declassify (r/server (str api-key)) \"\")])"))))
  (is (some #(= "admin audit view" (:declassified %)) (compiler/boundary-report))))

(def !last (atom nil))

(r/defn Validated []
  (let [save! (r/server ^{:validate string?} (fn [s] (reset! !last s)))]
    [:p (str (some? save!))]))

(deftest server-validates-calls
  (let [p (-> (ct/pair) (ct/mount! Validated) ct/flush!)
        save! (some #(when (fn? %) %) (map #(rt/value (:client-root p) %) (range (count (:nodes Validated)))))
        ok (save! "fine") bad (save! 42)]
    (ct/flush! p)
    (is (= "fine" @!last))
    (is (= "fine" @ok))
    (is (rt/failure? @bad))))

(deftest boundary-report
  (let [entries (filter #(= 'curve.security-test/Validated (:fn %)) (compiler/boundary-report))]
    (is (seq entries))
    (is (every? #(= :server (:from %)) entries))
    (is (some :validated entries))))
