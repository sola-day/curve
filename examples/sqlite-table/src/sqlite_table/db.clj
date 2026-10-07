(ns sqlite-table.db
  "SQLite access for the example: users with salted password hashes and an
  editable products table. Writes go through hypercurve.source/write!, which
  bumps the source version so every live query re-runs once."
  (:require [hypercurve.source :as src])
  (:import [java.security MessageDigest SecureRandom]
           [java.util HexFormat]))

(defonce db (atom nil))

(defn- hex [^bytes bs] (.formatHex (HexFormat/of) bs))

(defn hash-password
  "Example-grade salted SHA-256. Use bcrypt/argon2 in a real application."
  [salt password]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str salt ":" password) "UTF-8"))))

(defn- new-salt [] (let [b (byte-array 16)] (.nextBytes (SecureRandom.) b) (hex b)))

(defn init!
  "Open (and seed when empty) the database at path."
  [path]
  (let [s (src/jdbc-source (str "jdbc:sqlite:" path))]
    (src/write! s ["create table if not exists users (id integer primary key, name text unique, salt text, password_hash text)"])
    (src/write! s ["create table if not exists products (id integer primary key, name text not null, price real not null)"])
    (when (empty? (src/query (:get-conn s) ["select id from users limit 1"]))
      (let [salt (new-salt)]
        (src/write! s ["insert into users (name, salt, password_hash) values (?, ?, ?)" "admin" salt (hash-password salt "admin")])))
    (when (empty? (src/query (:get-conn s) ["select id from products limit 1"]))
      (doseq [[n p] [["Apple" 3.5] ["Banana" 1.2] ["Cherry" 12.0]]]
        (src/write! s ["insert into products (name, price) values (?, ?)" n p])))
    (reset! db s)))

(defn products-ref [] (src/rows @db ["select id, name, price from products order by id"]))

(defn check-login
  "The user's name when the password matches, else nil."
  [name password]
  (when-let [{:keys [salt password_hash]} (first (src/query (:get-conn @db) ["select salt, password_hash from users where name = ?" name]))]
    (when (= password_hash (hash-password salt password)) name)))

(defn update-field! [id field value]
  (case field
    :name (src/write! @db ["update products set name = ? where id = ?" (str value) id])
    :price (when-let [p (parse-double (str value))]
             (src/write! @db ["update products set price = ? where id = ?" p id]))))

(defn add-product! [name price]
  (src/write! @db ["insert into products (name, price) values (?, ?)" name price]))

(defn delete-product! [id]
  (src/write! @db ["delete from products where id = ?" id]))
