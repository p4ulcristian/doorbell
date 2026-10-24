# doorbell takes no wire: lines carry the app's, Postgres's messages are read with jsonista

> **Changed in part** by [groundwork-live-updates requires no wire](2026-10-24-groundwork-live-updates-requires-no-wire.md) (2026-10-24): it requires no wire library either; how a line is told a message is the app's `:push!`.

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> Changes in part [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md) (what it needs). Since v0.2.0, on mailroom v0.2.0.

**Decision:** wire v0.2.0 makes a wire from settings, and every one of its functions takes the wire first; the app makes its wire once and passes the same value to every library that sends or reads data across the app's edges. doorbell never makes one, and none of its functions takes one, because none needs it:

- **The sse lines** doorbell tells of changes are mailroom's, and since mailroom v0.2.0 a line carries the wire its routes were made with, the app's (`:mailroom/wire`). `send-message-on-sse-line!` writes by it; `start-listening!`, `add-sse-line!` and the rest are unchanged.
- **Postgres's change messages**, `{"folder": "public", "table": "orders"}`, are JSON doorbell's own trigger function writes and its listener reads: they don't cross the app's edges. doorbell reads them with jsonista itself, as wire's [wire is for the app's edges](https://github.com/p4ulcristian/wire/blob/main/decisions/2026-10-22-wire-is-for-the-apps-edges.md) says of sync's change messages (doorbell was sync; on leaving Groundwork it had read them through wire).
- **The settings and the handlers** are checked by malli itself, as cellar and showroom check theirs: they are values the app hands in, not data crossing an edge.
- **In the browser**, the question store asks through the app's `send-message!` function, which the app writes with its wire: `#(mailroom/send-message! (:http addresses) shapes-by-name % {:wire wire})`; the app's `open-sse-line!` call takes its wire too.

So doorbell's `deps.edn` names cellar, mailroom, malli and jsonista; wire comes with mailroom, and the tests name it to make the wire they hand mailroom.

**Why:** the rule for every library of manor is that the app passes its wire and the library never makes one. A library that needs no wire takes none: a wire passed to `start-listening!` only to read a trigger's JSON would hand an app's edge settings (its string limits, its kept shapes) to doorbell's own Postgres format, and every app would write it out for nothing.

**The price:** doorbell reads JSON with jsonista beside the wire the app's messages use: two JSON readers in one app (jsonista is the one under wire on the JVM).

**Instead of:**
- `start-listening!` taking the app's wire, `:wire`, for the payloads: an option that carries nothing doorbell's format needs
- a wire made inside doorbell for the payloads: a second wire, which the rule forbids
- checking the settings through wire: `make-question-store` and `start-listening!` would take a wire only to check their settings
