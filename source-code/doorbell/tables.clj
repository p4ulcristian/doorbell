(ns doorbell.tables
  "Every table map says whether its changes reach screens, with :doorbell:

     :tell             every open line hears that the table changed
     [:silent \"why\"]   nobody, on purpose, with the reason

   A table that says nothing stops the start, every problem at once: no
   table is quiet by accident. A table that tells gets :on-change, the
   triggers cellar makes, calling doorbell.tell."
  (:require
   [clojure.string :as str]))

(defn- is-silent? [doorbell]
  (and (vector? doorbell) (= :silent (first doorbell))))

(defn- find-problem-of-table [{:keys [table doorbell]}]
  (cond
    (nil? doorbell)
    (str table " says nothing about its doorbell: :doorbell :tell or [:silent \"why\"]")
    (and (is-silent? doorbell) (not (and (string? (second doorbell)) (seq (second doorbell)))))
    (str table " is [:silent …] without saying why")
    (not (or (= :tell doorbell) (is-silent? doorbell)))
    (str table "'s :doorbell is neither :tell nor [:silent \"why\"]: " (pr-str doorbell))))

(defn check-doorbell-of-tables
  "Throws with every table's problem at once."
  [tables]
  (let [problems (keep find-problem-of-table tables)]
    (when (seq problems)
      (throw (ex-info (str "The tables' doorbells have problems:\n  " (str/join "\n  " problems))
                      {:type :doorbell/bad-tables :problems (vec problems)})))))

(defn put-on-change-on-tables
  "The tables, checked, each that tells given :on-change."
  [tables]
  (check-doorbell-of-tables tables)
  (mapv (fn [table]
          (if (= :tell (:doorbell table))
            (assoc table :on-change {:function "doorbell.tell" :arguments []})
            table))
        tables))
