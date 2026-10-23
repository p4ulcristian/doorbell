(ns doorbell.metrics
  "doorbell's metrics, declared on the app's meter by start-listening!:

     doorbell_changes_total{table}      counter: changes heard, one per committed transaction and table
     doorbell_tells_total{message}      counter: messages told to open lines, changed or missed
     doorbell_listening                 gauge, read: 1 while the listener's connection listens, else 0

   The tables' set is the ones that tell (:doorbell :tell) of
   start-listening!'s :tables. A break in listening is counted by cellar's
   listener, on the same meter (cellar_listening_reconnects_total); the
   open lines by bridge (bridge_open_lines)."
  (:require
   [cellar.api :as cellar]
   [meter.api :as meter]))

(defn declare-metrics!
  "Declares doorbell's metrics on the meter. listener-holder will hold
   cellar's listener, read for the listening gauge."
  [meter tables listener-holder]
  (meter/declare-metrics!
   meter
   [{:name    :doorbell/changes-total
     :type    :counter
     :help    "Changes heard from Postgres, one per committed transaction and table, by table."
     :labels  {:table (set (keep #(when (= :tell (:doorbell %)) (:table %)) tables))}
     :buckets nil
     :read    nil}
    {:name    :doorbell/tells-total
     :type    :counter
     :help    "Messages told to open sse lines: a table changed, or changes may have been missed."
     :labels  {:message #{:changed :missed}}
     :buckets nil
     :read    nil}
    {:name    :doorbell/listening
     :type    :gauge
     :help    "1 while doorbell's listener's connection listens, 0 while it is broken or stopped."
     :labels  {}
     :buckets nil
     :read    (fn []
                (let [listener @listener-holder]
                  {{} (if (and listener (cellar/is-listening? listener)) 1 0)}))}]))

(defn count-change! [meter table]
  (meter/add-to-counter! meter :doorbell/changes-total {:table table} 1))

(defn count-tells! [meter message how-many]
  (meter/add-to-counter! meter :doorbell/tells-total {:message message} how-many))
