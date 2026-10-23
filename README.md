# doorbell

A change to a table rings every open screen, and the screens that read
that table ask again. When a transaction commits, Postgres tells every
listening server which table changed: only its name, no ids, no values.
Each server tells every open sse line `{:doorbell/changed {:table
"orders"}}`. Every answer says the tables it was read from, so the
browser's question store asks again only the watched questions that read
the changed table; the gates (Postgres's row rules) decide what each asker
gets. doorbell decides no one's access.

```clojure
io.github.p4ulcristian/doorbell {:git/tag "v0.5.4" :git/sha "f9958b3"}
```

Needs [cellar](https://github.com/p4ulcristian/cellar) (Postgres: the
trigger's function, listening, the tables a query reads) and
[mailroom](https://github.com/p4ulcristian/mailroom) (the sse lines, a
handler's notes, the browser's messages),
[meter](https://github.com/p4ulcristian/meter) (the app's metrics, handed
in), malli (the settings checked) and jsonista (Postgres's change messages
read). One public namespace,
`doorbell.api`: a `.clj` for the server, a `.cljs` for the browser.

doorbell never makes a [wire](https://github.com/p4ulcristian/wire) and
takes none: an sse line is told by the wire it carries, the app's, which
mailroom's routes were made with; in the browser the app's `send-message!`
and `open-sse-line!` calls hand mailroom the app's wire. Nor a log:
`start-listening!` takes the app's under `:log` in a new options map, for
cellar's listener, and the notes are taken with the message's own,
mailroom's `:mailroom/log` ([why](decisions/2026-10-22-the-app-passes-its-log.md)).

`start-listening!` takes the app's meter under `:meter`, and the app's
table maps under `:tables`, and declares doorbell's metrics
([why](decisions/2026-10-23-changes-and-tells-are-counted-on-the-apps-meter.md)):
`doorbell_changes_total{table}`, each change heard, the tables that tell
as the set (another is `"other"`); `doorbell_tells_total{message}`, each
`changed` or `missed` message written to a line; `doorbell_listening`, 1
while the listener listens. It hands the meter to cellar's listener, which
counts a break (`cellar_listening_reconnects_total`); mailroom counts the
open lines.

## The api

| side | name | does |
|---|---|---|
| server | `install-tell-function!` | `doorbell.tell()`, which the triggers call; as the owner, before migrate |
| server | `check-doorbell-of-tables` `put-doorbell-on-tables` | every table map says `:doorbell :tell` or `[:silent "why"]`; one that tells gets its triggers (cellar's `:on-change`), a silent one `:on-change nil` |
| server | `put-table-notes-into-handlers` | every answer notes the tables its steps read (`:mailroom/notes`) |
| server | `listening-settings-shape` `start-listening!` `stop-listening!` | listens on the channel `doorbell`, tells every open line; its batch window, waits and reconnect delay are required settings, the app's log, meter and tables required options |
| server | `add-sse-line!` `remove-sse-line!` `count-open-lines` | for mailroom's `:on-sse-open` and `:on-sse-close` |
| both | `store-settings-shape` | the question store's settings, every key required |
| browser | `make-question-store` | answers kept by question, `[name params]`, asked through mailroom's `send-message!` |
| browser | `watch-question!` `ask-question-again!` `find-kept-answer` | a screen watches what it shows; asked now, and again when a table it read changes |
| browser | `is-question-being-asked?` | whether a fresher answer is on its way: out now, or a gathered change will ask it |
| browser | `hear-changed-table!` | a table changed, told by the browser itself (a write's answer): gathered, then what read it is asked again |
| browser | `message-shapes-by-name` `make-sse-handlers` `hear-missed-changes!` | the two messages, their shapes and handlers; after a break, every watched question asked again |

Nothing has a default: every setting is written out; a missing one
refuses, named, with every other problem at once. What stays fixed, and
why: [the decision](decisions/2026-10-22-doorbell-is-its-own-library.md)
and [listening's numbers](decisions/2026-10-22-listening-s-numbers-are-settings.md).

```clojure
(require '[doorbell.api :as doorbell]
         '[cellar.api :as cellar])

(def orders {:table :orders :columns […] :indexes […] :gates nil :doorbell :tell})
(def logs   {:table :logs :columns […] :indexes […] :gates nil :doorbell [:silent "no screen shows logs"]})
;; put-doorbell-on-tables writes :on-change, the triggers or nil

;; the deploy, as the role that owns the tables
(doorbell/install-tell-function! owner-pool)
(cellar/migrate! owner-pool (doorbell/put-doorbell-on-tables [orders logs]) changes migrate-options)

;; the server, as the app's own role (it needs no grant for doorbell)
(def bell (doorbell/start-listening! pool {:batch-window-ms        50     ; after the first change, how long more join its batch
                                          :check-stopped-every-ms 500    ; one wait for a change, before looking whether it was stopped
                                          :reconnect-after-ms     1000   ; after a break, the wait before listening again
                                          :stop-wait-ms           2000}  ; how long stop-listening! waits
                                     {:log    log                        ; the app's: a break in listening is written to it
                                      :meter  meter                      ; the app's: changes heard, lines told, listening
                                      :tables [orders logs]}))           ; the tables that tell are the changes' set
(mailroom/make-routes-for-messages
 (doorbell/put-table-notes-into-handlers handlers)   ; each writes out :run-steps-in, nil for none
 settings
 {:wire         wire                                  ; the app's; each line carries it
  :log          log                                   ; the app's; each message's steps get one with its request id
  :meter        meter                                 ; the app's; mailroom's metrics
  :on-sse-open  #(doorbell/add-sse-line! bell %)
  :on-sse-close #(doorbell/remove-sse-line! bell %)
  …})

;; the browser
(def store (doorbell/make-question-store #(mailroom/send-message! (:http addresses) shapes-by-name % {:wire wire :headers {}})
                                         {:gather-changes-ms         50       ; changes heard together, one round of asking
                                          :keep-unwatched-answers-ms 300000})) ; an unwatched answer is kept this long
(mailroom/open-sse-line! (:sse addresses) (merge shapes-by-name doorbell/message-shapes-by-name)
                         (doorbell/make-sse-handlers store)
                         {:wire               wire
                          :on-open            #(when % (doorbell/hear-missed-changes! store))
                          :reconnect-after-ms sse-reconnect-after-ms})
(def stop (doorbell/watch-question! store [:orders/of-shop {:shop/id shop-id}] show-orders!))
```

## Tests

```sh
clojure -X:test                              # the server; the Postgres one is skipped without a database
npm install && npx shadow-cljs compile test  # the browser's store, on node

# with a throwaway Postgres:
initdb -D /tmp/doorbell-pg -A trust -U "$USER"
pg_ctl -D /tmp/doorbell-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/doorbell-pg.log start
DOORBELL_TEST_PORT=5499 DOORBELL_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/doorbell-pg stop
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
