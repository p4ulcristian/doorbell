(ns groundwork-live-updates.tables
  "Every table map says whether its changes reach screens, with :live-updates:

     :tell             every open line hears that the table changed
     [:silent \"why\"]   nobody, on purpose, with the reason

   A table that says nothing stops the start, every problem at once: no
   table is quiet by accident. A table that tells gets :on-change, the
   triggers groundwork-postgres makes, calling groundwork_live_updates.tell; a silent one :on-change
   nil, written out as groundwork-postgres requires."
  (:require
   [clojure.string :as str]))

(defn- is-silent? [live-updates]
  (and (vector? live-updates) (= :silent (first live-updates))))

(defn- find-problem-of-table [{:keys [table live-updates]}]
  (cond
    (nil? live-updates)
    (str table " says nothing about its live updates: :live-updates :tell or [:silent \"why\"]")
    (and (is-silent? live-updates) (not (and (string? (second live-updates)) (seq (second live-updates)))))
    (str table " is [:silent …] without saying why")
    (not (or (= :tell live-updates) (is-silent? live-updates)))
    (str table "'s :live-updates is neither :tell nor [:silent \"why\"]: " (pr-str live-updates))))

(defn check-live-updates-of-tables
  "Throws with every table's problem at once."
  [tables]
  (let [problems (keep find-problem-of-table tables)]
    (when (seq problems)
      (throw (ex-info (str "The tables' live updates have problems:\n  " (str/join "\n  " problems))
                      {:type :groundwork-live-updates/bad-tables :problems (vec problems)})))))

(defn put-on-change-on-tables
  "The tables, checked, each that tells given :on-change, each silent one
   :on-change nil."
  [tables]
  (check-live-updates-of-tables tables)
  (mapv (fn [table]
          (if (= :tell (:live-updates table))
            (assoc table :on-change {:function "groundwork_live_updates.tell" :arguments []})
            (assoc table :on-change nil)))
        tables))
