(ns doorbell.telling
  "What a batch of Postgres's notifications tells. Each one names a table,

     {\"folder\": \"public\", \"table\": \"orders\"}

   and every open line hears each changed table once:
   {:doorbell/changed {:table \"orders\"}}."
  (:require
   [cellar.api :as cellar]
   [wire.api :as wire]))

(defn turn-payload-into-table
  "A notification's text as the table key it names."
  [payload]
  (let [{:keys [folder table]} (wire/turn-json-text-into-data payload)]
    (cellar/turn-table-name-into-table-key folder table)))

(defn list-changed-tables
  "The distinct tables a batch of payloads names, in the order first named."
  [payloads]
  (distinct (map turn-payload-into-table payloads)))

(defn make-changed-message [table]
  {:doorbell/changed {:table (subs (str table) 1)}})

(def missed-message
  "After a break in listening: something may have changed unheard."
  {:doorbell/missed {}})
