(ns doorbell.telling
  "What a batch of Postgres's notifications tells. Each one names a table,

     {\"folder\": \"public\", \"table\": \"orders\"}

   and every open line hears each changed table once:
   {:doorbell/changed {:table \"orders\"}}.

   The payload is JSON doorbell's own trigger function writes for
   Postgres, so doorbell reads it with jsonista itself, not through the
   app's wire, which is for data crossing the app's edges."
  (:require
   [cellar.api :as cellar]
   [jsonista.core :as json]))

(defn turn-payload-into-table
  "A notification's text as the table key it names."
  [payload]
  (let [{:strs [folder table]} (json/read-value payload)]
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
