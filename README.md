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
io.github.p4ulcristian/doorbell {:git/tag "v0.1.0" :git/sha "…"}
```

Needs [cellar](https://github.com/p4ulcristian/cellar) (Postgres: the
trigger's function, listening, the tables a query reads) and
[mailroom](https://github.com/p4ulcristian/mailroom) (the sse lines, a
handler's notes, the browser's messages), with
[wire](https://github.com/p4ulcristian/wire), which comes with mailroom.
One public namespace, `doorbell.api`: a `.clj` for the server, a `.cljs`
for the browser.

## The api

| side | name | does |
|---|---|---|
| server | `install-tell-function!` | `doorbell.tell()`, which the triggers call; as the owner, before migrate |
| server | `check-doorbell-of-tables` `put-doorbell-on-tables` | every table map says `:doorbell :tell` or `[:silent "why"]`; one that tells gets its triggers (cellar's `:on-change`) |
| server | `put-table-notes-into-handlers` | every answer notes the tables its steps read (`:mailroom/notes`) |
| server | `start-listening!` `stop-listening!` | listens on the channel `doorbell`, tells every open line |
| server | `add-sse-line!` `remove-sse-line!` `count-open-lines` | for mailroom's `:on-sse-open` and `:on-sse-close` |
| both | `store-settings-shape` | the question store's settings, every key required |
| browser | `make-question-store` | answers kept by question, `[name params]`, asked through mailroom's `send-message!` |
| browser | `watch-question!` `ask-question-again!` `find-kept-answer` | a screen watches what it shows; asked now, and again when a table it read changes |
| browser | `message-shapes-by-name` `make-sse-handlers` `hear-missed-changes!` | the two messages, their shapes and handlers; after a break, every watched question asked again |

Nothing has a default: every setting is written out; a missing one
refuses, named, with every other problem at once. What stays fixed, and
why: [the decision](decisions/2026-10-22-doorbell-is-its-own-library.md).

```clojure
(require '[doorbell.api :as doorbell]
         '[cellar.api :as cellar])

(def orders {:table :orders :columns […] :doorbell :tell})
(def logs   {:table :logs :columns […] :doorbell [:silent "no screen shows logs"]})

;; the deploy, as the role that owns the tables
(doorbell/install-tell-function! owner-pool)
(cellar/migrate! owner-pool (doorbell/put-doorbell-on-tables [orders logs]) changes)

;; the server, as the app's own role (it needs no grant for doorbell)
(def bell (doorbell/start-listening! pool))
(mailroom/make-routes-for-messages
 (doorbell/put-table-notes-into-handlers handlers)   ; each writes out :run-steps-in, nil for none
 settings
 {:on-sse-open  #(doorbell/add-sse-line! bell %)
  :on-sse-close #(doorbell/remove-sse-line! bell %)
  …})

;; the browser
(def store (doorbell/make-question-store #(mailroom/send-message! (:http addresses) shapes-by-name %)
                                         {:gather-changes-ms         50       ; changes heard together, one round of asking
                                          :keep-unwatched-answers-ms 300000})) ; an unwatched answer is kept this long
(mailroom/open-sse-line! (:sse addresses) (merge shapes-by-name doorbell/message-shapes-by-name)
                         (doorbell/make-sse-handlers store)
                         {:on-open            #(when % (doorbell/hear-missed-changes! store))
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
