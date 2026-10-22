# Listening's numbers are doorbell's settings; a silent table writes out its :on-change

> Changes in part [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md): listening's timings, which it left to cellar, fixed there. Since v0.1.1, with cellar v0.1.3.

**Decision:** `start-listening!` takes the listening settings, every key required, checked before it listens (`:doorbell/bad-settings`, every problem at once, the map closed), and hands them to cellar's `start-listening!`, which requires them since cellar v0.1.2:

```clojure
(doorbell/start-listening! pool {:batch-window-ms        50     ; after the first change, how long more join its batch
                                 :check-stopped-every-ms 500    ; one wait for a change, before looking whether it was stopped
                                 :reconnect-after-ms     1000   ; after a break, the wait before listening again
                                 :stop-wait-ms           2000}) ; how long stop-listening! waits for the listener
```

The four numbers were cellar's, fixed (50, 500, 1000 and 2000 ms). doorbell fills in none of them: they are settings the app writes. `stop-listening!` returns whether the listener had ended within `:stop-wait-ms`. The shape is `listening-settings-shape`, in the api.

`put-doorbell-on-tables` gives a silent table `:on-change nil`, as a table that tells gets its triggers: every map it hands cellar says its `:on-change`, which cellar requires.

**Why:** a batch window is how long a screen waits to hear of a change; a reconnect delay is how long screens hear nothing after a break. Each is behaviour a user feels; fixed in cellar, they were invisible in the app's settings, which doorbell's [no defaults](2026-10-22-doorbell-is-its-own-library.md) was meant to rule out.

**The price:** an app writes four numbers more, and passes them to `start-listening!`.

**Instead of:**
- doorbell's own defaults for the four: the value in effect would be invisible in the app's code
- leaving `:on-change` out on a silent table, for the app to write: cellar would refuse every silent table the app forgot, though doorbell already knows it is silent
