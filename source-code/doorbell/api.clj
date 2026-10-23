(ns doorbell.api
  "A change to a table rings every open screen, and the screens that read
   that table ask again: the server's side. The browser's side is this
   namespace's .cljs half.

   1. Every table map says whether its changes reach screens (:doorbell
      :tell or [:silent \"why\"]); put-doorbell-on-tables checks that and
      gives the tables that tell their triggers, which call one Postgres
      function, doorbell.tell(), after every statement; its .sql file
      (function-files) is declared to cellar with the app's functions.
   2. Postgres tells every listening server, when a transaction commits,
      which table changed: no ids, no values. start-listening! listens,
      and tells every open sse line, once per table in what it gathered:

        {:doorbell/changed {:table \"orders\"}}

      After a break in listening, every line is told {:doorbell/missed {}}:
      something may have changed unheard.
   3. Every answer says the tables it was read from
      (put-table-notes-into-handlers), so the browser asks again only the
      questions a change touches; the gates decide what each asker gets.
      doorbell decides no one's access.

     (cellar/migrate! owner-pool {:tables     (doorbell/put-doorbell-on-tables tables)
                                  :extensions []
                                  :functions  doorbell/function-files   ; with the app's own
                                  :views      []}
                      changes
                      {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                       :log          log})
     (def doorbell (doorbell/start-listening! pool {:batch-window-ms        50
                                                    :check-stopped-every-ms 500
                                                    :reconnect-after-ms     1000
                                                    :stop-wait-ms           2000}
                                              {:log    log      ; the app's (logbook's)
                                               :meter  meter    ; the app's (meter's)
                                               :tables tables}))   ; the app's table maps: the changes' set
     ;; bridge's :on-sse-open #(doorbell/add-sse-line! doorbell %),
     ;;            :on-sse-close #(doorbell/remove-sse-line! doorbell %)

   A line is told by the wire it carries, the app's, which bridge's
   routes were made with: doorbell never makes a wire. Nor a log: the
   listener writes to the one start-listening! is handed, and the notes
   are taken with the message's own, :bridge/log, so a query whose
   tables Postgres can't say is written with the request's id.

   start-listening! declares doorbell's metrics on the app's meter, its
   :meter (doorbell.metrics): doorbell_changes_total{table}, the telling
   tables of its :tables as the set; doorbell_tells_total{message};
   doorbell_listening. It hands the meter to cellar's listener, which
   counts its reconnects."
  (:require
   [cellar.api :as cellar]
   [bridge.api :as bridge]
   [doorbell.metrics :as metrics]
   [doorbell.settings :as settings]
   [doorbell.tables :as tables]
   [doorbell.telling :as telling]))

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
   doorbell.settings/listening-settings-shape for each key."
  settings/listening-settings-shape)

(def function-files
  "The .sql file of doorbell.tell(), which the tables' triggers call, for
   cellar's :functions beside the app's own: cellar makes the folder
   doorbell and the function before the triggers, compares it with
   Postgres's, and would drop it from the folder were it not declared.
   The app's own role, owning nothing, needs no grant for it: a trigger
   runs its function whoever writes, and anyone may notify and listen."
  ["doorbell/tell.sql"])

(defn check-doorbell-of-tables
  "Throws with every problem at once (:doorbell/bad-tables): a table that
   says nothing about its doorbell, [:silent] without a reason, a value
   that is neither."
  [tables]
  (tables/check-doorbell-of-tables tables))

(defn put-doorbell-on-tables
  "The table maps, checked (check-doorbell-of-tables), each that tells
   given :on-change: the triggers cellar makes; each silent one :on-change
   nil.

     (cellar/migrate! owner-pool {:tables (doorbell/put-doorbell-on-tables tables) :extensions []
                                  :functions doorbell/function-files :views []}
                      changes migrate-options)"
  [tables]
  (tables/put-on-change-on-tables tables))

(defn- turn-key-into-text [k] (subs (str k) 1))

(defn- turn-tables-into-notes [tables]
  {:tables (if (= :unknown tables) "any" (vec (sort (map turn-key-into-text tables))))})

(defn- run-and-note-tables [run context]
  (let [[context tables] (cellar/run-and-list-tables-read {:log (:bridge/log context)} #(run context))]
    (assoc context :bridge/notes (turn-tables-into-notes tables))))

(defn- run-plainly [run context]
  (run context))

(defn put-table-notes-into-handlers
  "Every handler's answer gets a note of the tables its steps read,
   {:tables [\"orders\" \"shops\"]}, or {:tables \"any\"} when Postgres's
   planner couldn't say for one of its queries; bridge sends it beside
   the answer. Only the steps count, not what runs around them in the
   handler's :run-steps-in (a session, who holds what): the notes are
   taken inside it, with the message's log (bridge's :bridge/log), which
   a query the planner can't say is written to. Every handler writes out
   :run-steps-in, nil to run the steps plainly; one that leaves it out
   refuses (:doorbell/bad-handlers)."
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
  "Keeps an sse line that has just opened, to tell of changes. For
   bridge's :on-sse-open."
  [{:keys [lines]} line]
  (swap! lines assoc (:bridge/channel line) line))

(defn remove-sse-line!
  "Forgets a line that has closed. For bridge's :on-sse-close."
  [{:keys [lines]} line]
  (swap! lines dissoc (:bridge/channel line)))

(defn- tell-every-line!
  "Tells every open line the message; counts how many were told, as
   message-kind (:changed or :missed)."
  [{:keys [lines meter]} message-kind message]
  (let [open-lines (vals @lines)]
    (doseq [line open-lines]
      (bridge/send-message-on-sse-line! line message nil))
    (metrics/count-tells! meter message-kind (count open-lines))))

(defn- tell-of-notifications! [{:keys [meter] :as doorbell} notifications]
  (let [tables (map (comp telling/turn-payload-into-table :payload) notifications)]
    (doseq [table tables]
      (metrics/count-change! meter table))
    (doseq [table (distinct tables)]
      (tell-every-line! doorbell :changed (telling/make-changed-message table)))))

(defn start-listening!
  "Listens for changes, on the channel doorbell, and tells every open
   line. Holds one connection of the pool; the app's own role, owning
   nothing, may listen. The settings (listening-settings-shape), every key
   required, are checked first (:doorbell/bad-settings), and the options
   (:doorbell/bad-options), every key required: :log, the app's log, which
   a break in listening (:cellar/listening-broke) and a failure telling the
   lines (:cellar/listener-function-failed) are written to; :meter, the
   app's meter, which doorbell's metrics are declared on and counted into
   (doorbell.metrics), and cellar's listener counts its reconnects on;
   :tables, the app's table maps, as put-doorbell-on-tables takes them,
   whose telling ones are the changes' set:

     {:batch-window-ms        50     ; after the first change, how long more join its batch
      :check-stopped-every-ms 500    ; one wait for a change, before looking whether it was stopped
      :reconnect-after-ms     1000   ; after a break, the wait before listening again
      :stop-wait-ms           2000}  ; how long stop-listening! waits for the listener

   Returns the doorbell, for add-sse-line!, remove-sse-line!,
   count-open-lines and stop-listening!."
  [pool settings options]
  (settings/check-listening-settings settings)
  (settings/check-listening-options options)
  (let [{:keys [log meter tables]} options
        listener-holder (atom nil)
        doorbell        {:lines (atom {}) :meter meter}]
    (tables/check-doorbell-of-tables tables)
    ;; and a meter meter didn't make is refused here (:meter/not-a-meter)
    (metrics/declare-metrics! meter tables listener-holder)
    (assoc doorbell :listener
           (reset! listener-holder
                   (cellar/start-listening! pool "doorbell"
                                            (assoc settings
                                                   :log              log
                                                   :meter            meter
                                                   :on-notifications #(tell-of-notifications! doorbell %)
                                                   :on-reconnect     #(tell-every-line! doorbell :missed telling/missed-message)))))))

(defn stop-listening!
  "Stops listening; the lines stay open until the server stops. Waits at
   most the settings' :stop-wait-ms; returns whether the listener had ended."
  [{:keys [listener]}]
  (cellar/stop-listening! listener))

(defn count-open-lines
  "How many sse lines are kept, for tests and a status page."
  [{:keys [lines]}]
  (count @lines))
