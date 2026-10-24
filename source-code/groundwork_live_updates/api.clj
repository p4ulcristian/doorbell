(ns groundwork-live-updates.api
  "A change to a table rings every open screen, and the screens that read
   that table ask again: the server's side. The browser's side is this
   namespace's .cljs half.

   1. Every table map says whether its changes reach screens (:live-updates
      :tell or [:silent \"why\"]); put-live-updates-on-tables checks that and
      gives the tables that tell their triggers, which call one Postgres
      function, groundwork_live_updates.tell(), after every statement; its .sql file
      (function-files) is declared to groundwork-postgres with the app's functions.
   2. Postgres tells every listening server, when a transaction commits,
      which table changed: no ids, no values. start-listening! listens,
      and tells every open sse line, once per table in what it gathered:

        {:groundwork-live-updates/changed {:table \"orders\"}}

      After a break in listening, every line is told {:groundwork-live-updates/missed {}}:
      something may have changed unheard.
   3. Every answer says the tables it was read from
      (put-table-notes-into-handlers), so the browser asks again only the
      questions a change touches; the gates decide what each asker gets.
      groundwork-live-updates decides no one's access.

     (postgres/migrate! owner-pool {:tables     (live-updates/put-live-updates-on-tables tables)
                                  :extensions []
                                  :functions  live-updates/function-files   ; with the app's own
                                  :views      []}
                      changes
                      {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                       :log          log})
     (def groundwork-live-updates (live-updates/start-listening! pool {:batch-window-ms        50
                                                    :check-stopped-every-ms 500
                                                    :reconnect-after-ms     1000
                                                    :stop-wait-ms           2000}
                                              {:log           log      ; the app's (groundwork-logs's)
                                               :meter         meter    ; the app's (groundwork-metrics's)
                                               :tables        tables   ; the app's table maps: the changes' set
                                               ;; the app's lines: how one is told a message, and what tells it apart
                                               :push!         #(bridge/send-message-on-sse-line! %1 %2 nil)
                                               :find-line-key :groundwork-bridge/channel}))
     ;; the line's opening and closing, the app's sse hooks:
     ;;   #(live-updates/add-sse-line! groundwork-live-updates %), #(live-updates/remove-sse-line! groundwork-live-updates %)

   groundwork-live-updates opens no line and writes on none itself: the
   app hands start-listening! how a line is told a message (:push!), and
   how lines are told apart (:find-line-key), so it never needs the wire
   (groundwork's: groundwork-bridge's sse lines, each told by the shaper it
   carries). Nor does it make a shaper or a log: the listener writes to the
   one start-listening! is handed, and the notes are taken with the
   message's own, groundwork-steps's :groundwork-steps/log, so a query whose
   tables Postgres can't say is written with the request's id.

   start-listening! declares groundwork-live-updates's metrics on the app's meter, its
   :meter (groundwork_live_updates.metrics): groundwork_live_updates_changes_total{table}, the telling
   tables of its :tables as the set; groundwork_live_updates_tells_total{message};
   groundwork_live_updates_listening. It hands the meter to groundwork-postgres's listener, which
   counts its reconnects."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-live-updates.metrics :as metrics]
   [groundwork-live-updates.settings :as settings]
   [groundwork-live-updates.tables :as tables]
   [groundwork-live-updates.telling :as telling]))

(def store-settings-shape
  "The browser's question store's settings, every key required, for an
   app that says them on the server and puts them in the page:
   {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}. The browser
   checks them again when it makes the store."
  settings/store-settings-shape)

(def listening-settings-shape
  "The server's listening settings, every key required, what
   start-listening! takes: {:batch-window-ms 50 :check-stopped-every-ms 500
   :reconnect-after-ms 1000 :stop-wait-ms 2000}. See
   groundwork-live-updates.settings/listening-settings-shape for each key."
  settings/listening-settings-shape)

(def function-files
  "The .sql file of groundwork_live_updates.tell(), which the tables' triggers call, for
   groundwork-postgres's :functions beside the app's own: groundwork-postgres makes the folder
   groundwork-live-updates and the function before the triggers, compares it with
   Postgres's, and would drop it from the folder were it not declared.
   The app's own role, owning nothing, needs no grant for it: a trigger
   runs its function whoever writes, and anyone may notify and listen."
  ["groundwork_live_updates/tell.sql"])

(defn check-live-updates-of-tables
  "Throws with every problem at once (:groundwork-live-updates/bad-tables): a table that
   says nothing about its live updates, [:silent] without a reason, a value
   that is neither."
  [tables]
  (tables/check-live-updates-of-tables tables))

(defn put-live-updates-on-tables
  "The table maps, checked (check-live-updates-of-tables), each that tells
   given :on-change: the triggers groundwork-postgres makes; each silent one :on-change
   nil.

     (postgres/migrate! owner-pool {:tables (live-updates/put-live-updates-on-tables tables) :extensions []
                                  :functions live-updates/function-files :views []}
                      changes migrate-options)"
  [tables]
  (tables/put-on-change-on-tables tables))

(defn- turn-key-into-text [k] (subs (str k) 1))

(defn- turn-tables-into-notes [tables]
  {:tables (if (= :unknown tables) "any" (vec (sort (map turn-key-into-text tables))))})

(defn- run-and-note-tables [run context]
  (let [[context tables] (postgres/run-and-list-tables-read {:log (:groundwork-steps/log context)} #(run context))]
    (assoc context :groundwork-steps/notes (turn-tables-into-notes tables))))

(defn- run-plainly [run context]
  (run context))

(defn put-table-notes-into-handlers
  "Every handler's answer gets a note of the tables its steps read,
   {:tables [\"orders\" \"shops\"]}, or {:tables \"any\"} when Postgres's
   planner couldn't say for one of its queries, at :groundwork-steps/notes
   in the context, which the runner (groundwork-bridge) sends beside the
   answer. Only the steps count, not what runs around them in the
   handler's :run-steps-in (a session, who holds what): the notes are
   taken inside it, with the message's log (:groundwork-steps/log), which
   a query the planner can't say is written to. Every handler writes out
   :run-steps-in, nil to run the steps plainly; one that leaves it out
   refuses (:groundwork-live-updates/bad-handlers)."
  [handlers]
  (settings/check-handlers handlers)
  (update-vals handlers
               (fn [{:keys [run-steps-in] :as handler}]
                 (let [run-steps-around (or run-steps-in run-plainly)]
                   (assoc handler :run-steps-in
                          (fn [run context]
                            (run-steps-around #(run-and-note-tables run %) context)))))))

;; -----------------------------------------------------------------------------
;; Lines and listening

(defn add-sse-line!
  "Keeps an sse line that has just opened, to tell of changes, under its
   key (the options' :find-line-key). For the app's sse open hook."
  [{:keys [lines find-line-key]} line]
  (swap! lines assoc (find-line-key line) line))

(defn remove-sse-line!
  "Forgets a line that has closed, by its key. For the app's sse close
   hook."
  [{:keys [lines find-line-key]} line]
  (swap! lines dissoc (find-line-key line)))

(defn- tell-every-line!
  "Tells every open line the message, by the options' :push!; counts how
   many were told, as message-kind (:changed or :missed)."
  [{:keys [lines meter push!]} message-kind message]
  (let [open-lines (vals @lines)]
    (doseq [line open-lines]
      (push! line message))
    (metrics/count-tells! meter message-kind (count open-lines))))

(defn- tell-of-notifications! [{:keys [meter] :as live-updates} notifications]
  (let [tables (map (comp telling/turn-payload-into-table :payload) notifications)]
    (doseq [table tables]
      (metrics/count-change! meter table))
    (doseq [table (distinct tables)]
      (tell-every-line! live-updates :changed (telling/make-changed-message table)))))

(defn start-listening!
  "Listens for changes, on the channel groundwork_live_updates, and tells every open
   line. Holds one connection of the pool; the app's own role, owning
   nothing, may listen. The settings (listening-settings-shape), every key
   required, are checked first (:groundwork-live-updates/bad-settings), and the options
   (:groundwork-live-updates/bad-options), every key required: :log, the app's log, which
   a break in listening (:groundwork-postgres/listening-broke) and a failure telling the
   lines (:groundwork-postgres/listener-function-failed) are written to; :meter, the
   app's meter, which groundwork-live-updates's metrics are declared on and counted into
   (groundwork-live-updates.metrics), and groundwork-postgres's listener counts its reconnects on;
   :tables, the app's table maps, as put-live-updates-on-tables takes them,
   whose telling ones are the changes' set; :push!, (fn [line message]),
   how an open line is told a message, written on it at once (groundwork's:
   groundwork-bridge's send-message-on-sse-line! with no id); :find-line-key,
   (fn [line]), what tells one line from another, the key add-sse-line! keeps
   it under and remove-sse-line! forgets it by (groundwork's: the line's
   :groundwork-bridge/channel):

     {:batch-window-ms        50     ; after the first change, how long more join its batch
      :check-stopped-every-ms 500    ; one wait for a change, before looking whether it was stopped
      :reconnect-after-ms     1000   ; after a break, the wait before listening again
      :stop-wait-ms           2000}  ; how long stop-listening! waits for the listener

   Returns the live updates, for add-sse-line!, remove-sse-line!,
   count-open-lines and stop-listening!."
  [pool settings options]
  (settings/check-listening-settings settings)
  (settings/check-listening-options options)
  (let [{:keys [log meter tables push! find-line-key]} options
        listener-holder (atom nil)
        live-updates        {:lines (atom {}) :meter meter :push! push! :find-line-key find-line-key}]
    (tables/check-live-updates-of-tables tables)
    ;; and a meter meter didn't make is refused here (:groundwork-metrics/not-a-meter)
    (metrics/declare-metrics! meter tables listener-holder)
    (assoc live-updates :listener
           (reset! listener-holder
                   (postgres/start-listening! pool "groundwork_live_updates"
                                            (assoc settings
                                                   :log              log
                                                   :meter            meter
                                                   :on-notifications #(tell-of-notifications! live-updates %)
                                                   :on-reconnect     #(tell-every-line! live-updates :missed telling/missed-message)))))))

(defn stop-listening!
  "Stops listening; the lines stay open until the server stops. Waits at
   most the settings' :stop-wait-ms; returns whether the listener had ended."
  [{:keys [listener]}]
  (postgres/stop-listening! listener))

(defn count-open-lines
  "How many sse lines are kept, for tests and a status page."
  [{:keys [lines]}]
  (count @lines))
