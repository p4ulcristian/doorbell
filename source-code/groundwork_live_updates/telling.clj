(ns groundwork-live-updates.telling
  "What a batch of Postgres's notifications tells. Each one names a table,

     {\"folder\": \"public\", \"table\": \"orders\"}

   and every open line hears each changed table once:
   {:groundwork-live-updates/changed {:table \"orders\"}}.

   The payload is JSON groundwork-live-updates's own trigger function writes for
   Postgres, so groundwork-live-updates reads it with jsonista itself, not through the
   app's shaper, which is for data crossing the app's edges."
  (:require
   [groundwork-postgres.api :as postgres]
   [jsonista.core :as json]))

(defn turn-payload-into-table
  "A notification's text as the table key it names."
  [payload]
  (let [{:strs [folder table]} (json/read-value payload)]
    (postgres/turn-table-name-into-table-key folder table)))

(defn list-changed-tables
  "The distinct tables a batch of payloads names, in the order first named."
  [payloads]
  (distinct (map turn-payload-into-table payloads)))

(defn make-changed-message [table]
  {:groundwork-live-updates/changed {:table (subs (str table) 1)}})

(def missed-message
  "After a break in listening: something may have changed unheard."
  {:groundwork-live-updates/missed {}})
