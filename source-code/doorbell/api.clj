(ns doorbell.api
  "A change to a table rings every open screen, and the screens that read
   that table ask again: the server's side. The browser's side is this
   namespace's .cljs half.

   1. Every table map says whether its changes reach screens (:doorbell
      :tell or [:silent \"why\"]); put-doorbell-on-tables checks that and
      gives the tables that tell their triggers, which call one Postgres
      function (install-tell-function!) after every statement.
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

     (doorbell/install-tell-function! owner-pool)
     (cellar/migrate! owner-pool (doorbell/put-doorbell-on-tables tables) changes
                      {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}})
     (def doorbell (doorbell/start-listening! pool {:batch-window-ms        50
                                                    :check-stopped-every-ms 500
                                                    :reconnect-after-ms     1000
                                                    :stop-wait-ms           2000}))
     ;; mailroom's :on-sse-open #(doorbell/add-sse-line! doorbell %),
     ;;            :on-sse-close #(doorbell/remove-sse-line! doorbell %)"
  (:require
   [cellar.api :as cellar]
   [mailroom.api :as mailroom]
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

(def ^:private create-tell-function
  (cellar/make-query-from-sql-file "doorbell/create_tell_function.sql"))

(defn install-tell-function!
  "Installs, or replaces, the folder doorbell and doorbell.tell(), which
   the tables' triggers call. Run it as the owner, before migrate: Postgres
   refuses a trigger whose function doesn't exist yet. The app's own role,
   owning nothing, needs no grant for it: a trigger runs its function
   whoever writes, and anyone may notify and listen."
  [pool]
  (cellar/run-statement! pool create-tell-function {}))

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

     (cellar/migrate! owner-pool (doorbell/put-doorbell-on-tables tables) changes migrate-options)"
  [tables]
  (tables/put-on-change-on-tables tables))

(defn- turn-key-into-text [k] (subs (str k) 1))

(defn- turn-tables-into-notes [tables]
  {:tables (if (= :unknown tables) "any" (vec (sort (map turn-key-into-text tables))))})

(defn- run-and-note-tables [run context]
  (let [[context tables] (cellar/run-and-list-tables-read #(run context))]
    (assoc context :mailroom/notes (turn-tables-into-notes tables))))

(defn- run-plainly [run context]
  (run context))

(defn put-table-notes-into-handlers
  "Every handler's answer gets a note of the tables its steps read,
   {:tables [\"orders\" \"shops\"]}, or {:tables \"any\"} when Postgres's
   planner couldn't say for one of its queries; mailroom sends it beside
   the answer. Only the steps count, not what runs around them in the
   handler's :run-steps-in (a session, who holds what): the notes are
   taken inside it. Every handler writes out :run-steps-in, nil to run the
   steps plainly; one that leaves it out refuses (:doorbell/bad-handlers)."
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
   mailroom's :on-sse-open."
  [{:keys [lines]} line]
  (swap! lines assoc (:mailroom/channel line) line))

(defn remove-sse-line!
  "Forgets a line that has closed. For mailroom's :on-sse-close."
  [{:keys [lines]} line]
  (swap! lines dissoc (:mailroom/channel line)))

(defn- tell-every-line! [{:keys [lines]} message]
  (doseq [line (vals @lines)]
    (mailroom/send-message-on-sse-line! line message nil)))

(defn- tell-of-notifications! [doorbell notifications]
  (doseq [table (telling/list-changed-tables (map :payload notifications))]
    (tell-every-line! doorbell (telling/make-changed-message table))))

(defn start-listening!
  "Listens for changes, on the channel doorbell, and tells every open
   line. Holds one connection of the pool; the app's own role, owning
   nothing, may listen. The settings (listening-settings-shape), every key
   required, are checked first (:doorbell/bad-settings):

     {:batch-window-ms        50     ; after the first change, how long more join its batch
      :check-stopped-every-ms 500    ; one wait for a change, before looking whether it was stopped
      :reconnect-after-ms     1000   ; after a break, the wait before listening again
      :stop-wait-ms           2000}  ; how long stop-listening! waits for the listener

   Returns the doorbell, for add-sse-line!, remove-sse-line!,
   count-open-lines and stop-listening!."
  [pool settings]
  (settings/check-listening-settings settings)
  (let [doorbell {:lines (atom {})}]
    (assoc doorbell :listener
           (cellar/start-listening! pool "doorbell"
                                    (assoc settings
                                           :on-notifications #(tell-of-notifications! doorbell %)
                                           :on-reconnect     #(tell-every-line! doorbell telling/missed-message))))))

(defn stop-listening!
  "Stops listening; the lines stay open until the server stops. Waits at
   most the settings' :stop-wait-ms; returns whether the listener had ended."
  [{:keys [listener]}]
  (cellar/stop-listening! listener))

(defn count-open-lines
  "How many sse lines are kept, for tests and a status page."
  [{:keys [lines]}]
  (count @lines))
