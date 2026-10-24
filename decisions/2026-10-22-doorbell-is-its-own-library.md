# doorbell is its own library

> **Changed in part** by [groundwork-live-updates requires no wire](2026-10-24-groundwork-live-updates-requires-no-wire.md) (2026-10-24): it needs groundwork-postgres, groundwork-steps, groundwork-metrics, malli and jsonista, not groundwork-bridge; `start-listening!` is handed `:push!` and `:find-line-key`.

> **Changed in part** by [An ask with no answer is bridge's :bridge/no-answer refusal](2026-10-23-an-ask-with-no-answer-is-bridges-no-answer-refusal.md) (2026-10-23, built on tidy-up, not released): a failed send answers `{:bridge/refused :bridge/no-answer :bridge/data {:http-status nil}}`, not `{:error "No answer from the server."}`.

> **Changed in part** by [doorbell takes no wire](2026-10-22-doorbell-takes-no-wire.md) (2026-10-22, v0.2.0): doorbell needs cellar, mailroom, malli and jsonista; it takes no wire, and reads Postgres's change messages with jsonista.

> **Changed in part** by [Listening's numbers are doorbell's settings](2026-10-22-listening-s-numbers-are-settings.md) (2026-10-22, v0.1.1): listening's four timings are required settings `start-listening!` takes, and a silent table gets `:on-change nil`.

> Changes in part [sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md), [sync v1](2026-10-22-sync-v1.md) and [The question store v1](2026-10-22-the-question-store-v1.md): their names, their keys, their messages, and the two numbers they kept fixed.

**Decision:** doorbell is a library of its own, in its own public repository, under the MIT license; an app or a framework takes it from git by tag. It was Groundwork's sync, a library on each side (`groundwork.server.sync`, `groundwork.browser.sync`). Now it is the namespace `doorbell`:

- **One public namespace, `doorbell.api`, a `.clj` for the server and a `.cljs` for the browser**; its parts are `doorbell.*` (`tables`, `telling` on the server; `store` in the browser; `settings`, a `.cljc`, the shapes of both).
- **It needs only cellar and mailroom** (and wire, which comes with mailroom): cellar for the trigger's function, the table maps' `:on-change`, listening and the tables a query reads; mailroom for the sse lines, a handler's notes and the browser's messages. It uses no access library: the gates decide what each asker gets ([sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md)).
- **It knows nothing of who uses it:**

  | was | is |
  |---|---|
  | a table map's `:sync :tell` / `[:silent "why"]` | `:doorbell :tell` / `[:silent "why"]` |
  | `groundwork.tell_sync()`, in the folder `groundwork` | `doorbell.tell()`, in the folder `doorbell` |
  | the channel `groundwork_sync` | the channel `doorbell` |
  | `{:sync/changed {:table "orders"}}` | `{:doorbell/changed {:table "orders"}}` |
  | `{:sync/missed {}}` | `{:doorbell/missed {}}` |
  | `:groundwork.server.sync/bad-sync` | `:doorbell/bad-tables` |
  | `install-sync-function!`, `check-sync-of-tables`, `put-sync-on-tables` | `install-tell-function!`, `check-doorbell-of-tables`, `put-doorbell-on-tables` |
  | `put-sync-into-handlers` | `put-table-notes-into-handlers` (what it puts in: a note of the tables read) |
  | `start-sync!`, `stop-sync!` | `start-listening!`, `stop-listening!` |

  `add-sse-line!`, `remove-sse-line!`, `count-open-lines` and the browser's `make-question-store`, `watch-question!`, `ask-question-again!`, `find-kept-answer`, `make-sse-handlers`, `hear-missed-changes!` keep their names. The messages say what happened, a table changed or changes were missed, under the library's name, as mailroom's `:mailroom/notes` do.
- **The browser's shapes**: `message-shapes-by-name` holds both shapes of the two messages, for mailroom's `shapes-by-name`, so they arrive checked.
- **The app's own role needs no grant for it.** A trigger runs its function whoever writes the rows, and `pg_notify`, like `LISTEN`, needs no privilege; the function runs as the writer. The test proves it with a role that owns nothing and has no `USAGE` on the folder `doorbell`: its writes ring, and it listens.
- **No defaults.** The question store's two fixed numbers are its required settings, checked when it is made (`:doorbell/bad-settings`, every problem at once):

  ```clojure
  (doorbell/make-question-store send-message! {:gather-changes-ms         50       ; changes heard together are one round of asking
                                               :keep-unwatched-answers-ms 300000}) ; an unwatched answer is kept this long
  ```

  `store-settings-shape`, in both halves, lets an app say them on the server and put them in the page. `put-table-notes-into-handlers` refuses a handler that doesn't write out `:run-steps-in` (nil to run the steps plainly), `:doorbell/bad-handlers`.

**What stays fixed, and why:**

- **The folder `doorbell`, the function `doorbell.tell()` and the channel `doorbell`**: the trigger and the listener must say the same, and the function is SQL installed once; a database has one doorbell. Two apps in one database would hear each other's tables: extra asking, never data.
- **The messages' names and shapes**: the protocol both halves read.
- **An answer without notes counts as read from any table**: an answer refused before the handler's steps ran (a rate limit, a session check) has no notes; asked again on every change is the one reading that can't leave it stale.
- **An ask that gets no answer is answered `{:error "No answer from the server."}`**: the store's words to its watchers, as mailroom keeps its error texts; the app shows its own.
- **Listening's own timings** (how often it polls, how long it gathers a batch, how long it waits before it listens again) are cellar's `start-listening!`, not doorbell's: fixed in cellar v0.1.1, where they would be settings.

**Why its own library:** the server's and the browser's halves are one idea, a change rings and the screens that read it ask again, and nothing in it needed Groundwork after it stopped working out who may see a row. Its own repository, with a `deps.edn` naming only cellar, mailroom and wire, proves it, and lets an app take it alone. **Why the name:** a doorbell's ring says someone is at the door, not who or why: a change tells which table, never which row, and the screen opens the door by asking again, through the gates. It stands beside the doorman; Groundwork's earliest decisions called these bells.

**The price:** a change to doorbell is a commit and a tag in another repository, then a bump in the app. An app writes the store's two numbers, and a table map's key changes from `:sync` to `:doorbell`.

**Instead of:**
- staying folders in Groundwork's tree: no app can take it alone, and nothing proves its deps
- two libraries, one per side: one idea and one protocol, every change two tags
- the names bellhop (one who carries things: doorbell carries nothing), echo (says what was said, not that something happened) and sync (says what the user gets, not what the library does, and promises more than a table's name)
- keeping `:sync/…` messages: the names would say a library that no longer exists
- the numbers as defaults: a value in effect would be invisible in the app's code
