(ns groundwork-live-updates.metrics
  "groundwork-live-updates's metrics, declared on the app's meter by start-listening!:

     groundwork_live_updates_changes_total{table}      counter: changes heard, one per committed transaction and table
     groundwork_live_updates_tells_total{message}      counter: messages told to open lines, changed or missed
     groundwork_live_updates_listening                 gauge, read: 1 while the listener's connection listens, else 0

   The tables' set is the ones that tell (:live-updates :tell) of
   start-listening!'s :tables. A break in listening is counted by groundwork-postgres's
   listener, on the same meter (groundwork_postgres_listening_reconnects_total); the
   open lines by bridge (groundwork_bridge_open_lines)."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-metrics.api :as metrics]))

(defn declare-metrics!
  "Declares groundwork-live-updates's metrics on the meter. listener-holder will hold
   groundwork-postgres's listener, read for the listening gauge."
  [meter tables listener-holder]
  (metrics/declare-metrics!
   meter
   [{:name    :groundwork-live-updates/changes-total
     :type    :counter
     :help    "Changes heard from Postgres, one per committed transaction and table, by table."
     :labels  {:table (set (keep #(when (= :tell (:live-updates %)) (:table %)) tables))}
     :buckets nil
     :read    nil}
    {:name    :groundwork-live-updates/tells-total
     :type    :counter
     :help    "Messages told to open sse lines: a table changed, or changes may have been missed."
     :labels  {:message #{:changed :missed}}
     :buckets nil
     :read    nil}
    {:name    :groundwork-live-updates/listening
     :type    :gauge
     :help    "1 while groundwork-live-updates's listener's connection listens, 0 while it is broken or stopped."
     :labels  {}
     :buckets nil
     :read    (fn []
                (let [listener @listener-holder]
                  {{} (if (and listener (postgres/is-listening? listener)) 1 0)}))}]))

(defn count-change! [meter table]
  (metrics/add-to-counter! meter :groundwork-live-updates/changes-total {:table table} 1))

(defn count-tells! [meter message how-many]
  (metrics/add-to-counter! meter :groundwork-live-updates/tells-total {:message message} how-many))
