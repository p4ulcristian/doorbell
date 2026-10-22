# The app passes its log; the notes are taken with the message's

> **Changed in part** by [Changes heard and lines told are counted on the app's meter](2026-10-23-changes-and-tells-are-counted-on-the-apps-meter.md) (2026-10-23, v0.5.0): `start-listening!`'s options also take `:meter`, the app's meter, and `:tables`, the app's table maps.

> Changes in part [Listening's numbers are doorbell's settings](2026-10-22-listening-s-numbers-are-settings.md) (`start-listening!` takes an options map too). Since v0.3.0, on cellar v0.2.0 and mailroom v0.3.0.

**Decision:** doorbell writes no line itself, and makes no log. Its two callers of cellar that write lines are handed one:

- **`start-listening!`** takes a new options map, last, `{:log log}`, the app's log as logbook makes it, required, checked by malli as a function (`:doorbell/bad-options`), and hands it to cellar's listener: a break in listening (`:cellar/listening-broke`) and a failure telling the lines (`:cellar/listener-function-failed`) are written there. The settings stay the four numbers the app writes in its config; the log is a function, not a setting.
- **The notes** (`put-table-notes-into-handlers`) are taken with the message's own log, mailroom's `:mailroom/log` in the context: a query whose tables Postgres's planner can't say (`:cellar/tables-read-unknown`) is written with the request's id, and the person once doorman's step has added them.

**Why:** "The app passes its log": cellar takes the log on the calls that write, and doorbell is between the app and those calls. The notes run inside a message, so the message's log is the one with the context; a log from the start would lose the request.

**The price:** `start-listening!` takes a third argument; every caller of v0.2 changes. doorbell takes nothing new: the log reaches it as a value.

**Instead of:**
- `:log` in the listening settings: the settings are numbers the app writes in its config, which a function can't be
- the notes taken with the app's log from the start: the warning would not say which request asked
