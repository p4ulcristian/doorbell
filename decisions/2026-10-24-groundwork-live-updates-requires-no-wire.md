# groundwork-live-updates requires no wire: it is handed how a line is told, and shares groundwork-steps's shapes

> The user's decision, 2026-10-24 (groundwork v4.0.0): groundwork-bridge, groundwork-access, groundwork-live-updates and groundwork-screens don't require each other; they share shapes through groundwork-steps, and the composer (groundwork) plugs them together. Changes in part [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md), [doorbell takes no wire](2026-10-22-doorbell-takes-no-wire.md) and [An ask with no answer is bridge's :bridge/no-answer refusal](2026-10-23-an-ask-with-no-answer-is-bridges-no-answer-refusal.md).

**Decision:** groundwork-live-updates requires [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps) in place of groundwork-bridge.

- `start-listening!` takes two more options, both required: `:push!`, `(fn [line message])`, how an open line is told a message; and `:find-line-key`, `(fn [line])`, what tells one line from another, the key `add-sse-line!` keeps a line under and `remove-sse-line!` forgets it by. In groundwork they are `#(bridge/send-message-on-sse-line! %1 %2 nil)` and `:groundwork-bridge/channel`, as before.
- The notes go at groundwork-steps's `:groundwork-steps/notes` in the context, and are taken with the message's `:groundwork-steps/log`.
- The browser's store answers a failed send with groundwork-steps's `(steps/make-refusal :groundwork-steps/no-answer {:http-status nil})`, and reads the tables from `:groundwork-steps/notes` beside the answer.
- groundwork-live-updates opens no line: the browser's sse line is the app's, opened with `make-sse-handlers` and `message-shapes-by-name`, as before.

Its tests tell lines of their own (a queue each, by `:push!`) and run the noted handlers' steps by groundwork-steps; they no longer start a server.

**Why:** groundwork-live-updates used groundwork-bridge for one call, `send-message-on-sse-line!`, one key of a line, `:groundwork-bridge/channel`, and the notes' and log's keys of the step context. Handed the first two and sharing the last through groundwork-steps, it no longer carries the wire or its releases, and the composer is the one place that says which line it tells.

**Instead of:**
- the whole line kept as its own key in a set: a line carries its Ring request, whose parts the wire may change, so its hash isn't sure to stay the one it was added with
- `:find-line-key` left out, the composer handing `add-sse-line!` the key beside the line: two arguments that must agree at every call, where one function written once does
- groundwork-bridge kept as a test-only dependency: the tests would plug two siblings together, which only the composer does
