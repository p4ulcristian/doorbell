# sync tells by table and trusts the gates

> **Changed in part** by [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md) (2026-10-22, v0.1.0): sync is doorbell; the table map's key is `:doorbell`, the messages `{:doorbell/changed {:table …}}` and `{:doorbell/missed {}}`, the function `doorbell.tell` on the channel `doorbell`.

> Changes in part Groundwork's "sync keeps screens in step with the database; rate limits are transport's", [sync v1](2026-10-22-sync-v1.md), [Postgres rings the bell](2026-10-12-postgres-rings-the-bell.md), Groundwork's "Who is rung comes from who may see", [Asking again, and bells that miss nothing](2026-10-14-asking-again-and-bells-that-miss-nothing.md) and [The question store v1](2026-10-22-the-question-store-v1.md).

**Decision:**

- **When rows change, the trigger tells only which table changed:** `{"folder": "public", "table": "orders"}`, once per statement that changed rows; no ids, no column values. A statement that changed no row tells nothing.
- **Each server tells every open sse line** `{:sync/changed {:table "orders"}}`, once per table in what it gathered (50 ms). After a break in listening, every line hears `{:sync/missed {}}`, as before.
- **The browser asks again** every watched question whose answer was read from that table (the `:mailroom/notes` tables, as before). Postgres's gates decide what each asker gets. Permissions live in one place, the gates; sync never decides who may see a row.
- **A table map's `:sync` is `:tell` or `[:silent "why"]`.** `:by-access` and `:everyone` go: told by table, they did the same thing. A table that says nothing, or `:silent` without a reason, still stops the start.
- **Sync no longer uses access.** Its trigger function stays `groundwork.tell_sync`, in the `groundwork` schema; `start-sync!` takes only the pool.

**Why:** before, permissions were decided twice: by the gates when asking, and by sync's own copy of them when telling. The two had to match exactly, or a change reached the wrong people. Now sync can't leak a row: it sends only a table name.

**The price:** every screen showing something read from a table asks again on any change to that table: at the size of the app it was made for, dozens of screens, one cheap query each. Anyone with an open sse line (a visitor too) can learn that some row of a table changed, not which.

**Instead of:** telling only the people who may see the row (the trigger sending ids and the scope columns' values, sync matching them against the `:access` sentences).
