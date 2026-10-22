# Changes heard and lines told are counted on the app's meter

> Changes in part [The app passes its log](2026-10-22-the-app-passes-its-log.md) (`start-listening!`' options gain `:meter` and `:tables`). Since v0.5.0, on meter v0.1.2, cellar v0.4.1 and mailroom v0.7.0.

**Decision:** `start-listening!` takes the app's meter (meter's `make-meter`) as a required option, `:meter`, never nil, beside `:log`, and the app's table maps as `:tables`, as `put-doorbell-on-tables` takes them; it declares doorbell's metrics on the meter before it listens:

| metric | type | labels | counted |
|---|---|---|---|
| `doorbell_changes_total` | counter | `table`: the tables of `:tables` that tell (`:doorbell :tell`) | each notification heard: one per committed transaction and table |
| `doorbell_tells_total` | counter | `message`: `changed`, `missed` | each message written to an open sse line |
| `doorbell_listening` | gauge, read | | 1 while the listener's connection listens (cellar's `is-listening?`), else 0 |

- **A table outside the set is `"other"`**: a silent one, or one the app didn't hand in.
- **A break is counted once, by cellar**: doorbell hands its meter to cellar's listener, which counts `cellar_listening_reconnects_total`; doorbell counts the `missed` messages that follow it, one per line.
- **Open lines are mailroom's** (`mailroom_open_lines{pipe="sse"}`); `count-open-lines` stays for tests and a status page.
- **`:tables` is checked** as `put-doorbell-on-tables` checks it: a table that says nothing about its doorbell refuses the start (`:doorbell/bad-tables`).

**Why:** a screen that doesn't refresh is either a change Postgres never told, a listener that is down, or lines that weren't told; the three metrics tell those apart. doorbell's listener held no table names, and a label's set must be known when it is declared, so the app hands the tables it already hands to `put-doorbell-on-tables`. `doorbell_listening` reads cellar's own state, so it can't disagree with it.

**The price:** `start-listening!` takes two keys more; the app hands its tables twice (to `put-doorbell-on-tables` for the deploy and to `start-listening!` for the server). A listener with no meter is no longer possible.

**Instead of:**
- the table as a free label, the notification's text: a table made by hand in the database, or a folder the app doesn't know, would add series
- counting reconnects in doorbell too: the same break twice
- counting each changed table once per batch: the batch window is a setting, so the count would change with it
- the open lines here: mailroom opens and closes them, and counts them
