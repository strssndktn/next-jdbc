;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc.sql.builder
  "Some utility functions for building SQL strings.

  These were originally private functions in `next.jdbc.sql` but
  they may proof useful to developers who want to write their own
  'SQL sugar' functions, such as a database-specific `upsert!` etc."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn as-?
  "Given a hash map of column names and values, or a vector of column names,
  return a string of `?` placeholders for them."
  [key-map opts]
  (str/join ", " (repeat (count key-map) "?")))

(defn as-keys
  "Given a hash map of column names and values, return a string of all the
  column names.

  Applies any `:column-fn` supplied in the options."
  [key-map opts]
  (str/join ", " (map (comp (:column-fn opts identity) name) (keys key-map))))

(defn by-keys
  "Given a hash map of column names and values and a clause type
  (`:set`, `:where`), return a vector of a SQL clause and its parameters.

  Applies any `:column-fn` supplied in the options."
  [key-map clause opts]
  (let [entity-fn      (:column-fn opts identity)
        [where params] (reduce-kv (fn [[conds params] k v]
                                    (let [e (entity-fn (name k))]
                                      (if (and (= :where clause) (nil? v))
                                        [(conj conds (str e " IS NULL")) params]
                                        [(conj conds (str e " = ?")) (conj params v)])))
                                  [[] []]
                                  key-map)]
    (assert (seq where) "key-map may not be empty")
    (into [(str (str/upper-case (name clause)) " "
                (str/join (if (= :where clause) " AND " ", ") where))]
          params)))

(defn for-delete
  "Given a table name and either a hash map of column names and values or a
  vector of SQL (where clause) and its parameters, return a vector of the
  full `DELETE` SQL string and its parameters.

  Applies any `:table-fn` / `:column-fn` supplied in the options.

  If `:suffix` is provided in `opts`, that string is appended to the
  `DELETE ...` statement."
  [table where-params opts]
  (let [entity-fn    (:table-fn opts identity)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (into [(str "DELETE FROM " (entity-fn (name table))
                " " (first where-params)
                (when-let [suffix (:suffix opts)]
                  (str " " suffix)))]
          (rest where-params))))

(defn for-insert
  "Given a table name and a hash map of column names and their values,
  return a vector of the full `INSERT` SQL string and its parameters.

  Applies any `:table-fn` / `:column-fn` supplied in the options.

  If `:suffix` is provided in `opts`, that string is appended to the
  `INSERT ...` statement."
  [table key-map opts]
  (let [entity-fn (:table-fn opts identity)
        params    (as-keys key-map opts)
        places    (as-? key-map opts)]
    (assert (seq key-map) "key-map may not be empty")
    (into [(str "INSERT INTO " (entity-fn (name table))
                " (" params ")"
                " VALUES (" places ")"
                (when-let [suffix (:suffix opts)]
                  (str " " suffix)))]
          (vals key-map))))

(defn for-insert-multi
  "Given a table name, a vector of column names, and a vector of row values
  (each row is a vector of its values), return a vector of the full `INSERT`
  SQL string and its parameters.

  Applies any `:table-fn` / `:column-fn` supplied in the options.

  If `:suffix` is provided in `opts`, that string is appended to the
  `INSERT ...` statement."
  [table cols rows opts]
  (assert (apply = (count cols) (map count rows))
          "column counts are not consistent across cols and rows")
  ;; to avoid generating bad SQL
  (assert (seq cols) "cols may not be empty")
  (assert (seq rows) "rows may not be empty")
  (let [table-fn  (:table-fn opts identity)
        column-fn (:column-fn opts identity)
        params    (str/join ", " (map (comp column-fn name) cols))
        places    (as-? (first rows) opts)]
    (into [(str "INSERT INTO " (table-fn (name table))
                " (" params ")"
                " VALUES "
                (str/join ", " (repeat (count rows) (str "(" places ")")))
                (when-let [suffix (:suffix opts)]
                  (str " " suffix)))]
          cat
          rows)))

(defn for-order-col
  "Given a column name, or a pair of column name and direction,
  return the sub-clause for addition to `ORDER BY`."
  [col opts]
  (let [entity-fn (:column-fn opts identity)]
    (cond (keyword? col)
          (entity-fn (name col))

          (and (vector? col) (= 2 (count col)) (keyword? (first col)))
          (str (entity-fn (name (first col)))
               " "
               (or (get {:asc "ASC" :desc "DESC"} (second col))
                   (throw (IllegalArgumentException.
                           (str ":order-by " col
                                " expected :asc or :desc")))))
          :else
          (throw (IllegalArgumentException.
                  (str ":order-by expected keyword or keyword pair,"
                       " found: " col))))))

(defn for-order
  "Given an `:order-by` vector, return an `ORDER BY` clause."
  [order-by opts]
  (when-not (vector? order-by)
    (throw (IllegalArgumentException. ":order-by must be a vector")))
  (assert (seq order-by) ":order-by may not be empty")
  (str "ORDER BY "
       (str/join ", " (map #(for-order-col % opts) order-by))))

(defn for-query
  "Given a table name and either a hash map of column names and values or a
  vector of SQL (where clause) and its parameters, return a vector of the
  full `SELECT` SQL string and its parameters.

  Applies any `:table-fn` / `:column-fn` supplied in the options.

  If `:suffix` is provided in `opts`, that string is appended to the
  `SELECT ...` statement."
  [table where-params opts]
  (let [entity-fn    (:table-fn opts identity)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(when-let [clause (first where-params)]
                                (str "WHERE " clause))]
                             (rest where-params)))
        where-params (cond-> (if (:top opts)
                               (into [(first where-params)]
                                     (cons (:top opts) (rest where-params)))
                               where-params)
                       (:limit opts)  (conj (:limit opts))
                       (:offset opts) (conj (:offset opts))
                       (:fetch opts)  (conj (:fetch opts)))]
    (into [(str "SELECT "
                (when (:top opts)
                  "TOP ? ")
                "* FROM " (entity-fn (name table))
                (when-let [clause (first where-params)]
                  (str " " clause))
                (when-let [order-by (:order-by opts)]
                  (str " " (for-order order-by opts)))
                (when (:limit opts)
                  " LIMIT ?")
                (when (:offset opts)
                  (if (:limit opts)
                    " OFFSET ?"
                    " OFFSET ? ROWS"))
                (when (:fetch opts)
                  " FETCH NEXT ? ROWS ONLY")
                (when-let [suffix (:suffix opts)]
                  (str " " suffix)))]
          (rest where-params))))

(defn for-update
  "Given a table name, a vector of column names to set and their values, and
  either a hash map of column names and values or a vector of SQL (where clause)
  and its parameters, return a vector of the full `UPDATE` SQL string and its
  parameters.

  Applies any `:table-fn` / `:column-fn` supplied in the options.

  If `:suffix` is provided in `opts`, that string is appended to the
  `UPDATE ...` statement."
  [table key-map where-params opts]
  (let [entity-fn    (:table-fn opts identity)
        set-params   (by-keys key-map :set opts)
        where-params (if (map? where-params)
                       (by-keys where-params :where opts)
                       (into [(str "WHERE " (first where-params))]
                             (rest where-params)))]
    (-> [(str "UPDATE " (entity-fn (name table))
              " " (first set-params)
              " " (first where-params)
              (when-let [suffix (:suffix opts)]
                (str " " suffix)))]
        (into (rest set-params))
        (into (rest where-params)))))
