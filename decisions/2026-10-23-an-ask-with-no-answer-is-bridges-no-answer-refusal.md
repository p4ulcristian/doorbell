# An ask with no answer is bridge's :bridge/no-answer refusal

> Follows bridge's [A refusal is a named reason, declared on its handler](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md) (the user's decision, 2026-10-23), which left doorbell's store for later. Changes in part [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md). Built on tidy-up, not released.

**Decision:** when the app's `send-message!` fails, the store answers the question with bridge's own refusal for no response, not with an English text:

```clojure
{:bridge/refused :bridge/no-answer :bridge/data {:http-status nil}}   ; was {:error "No answer from the server."}
```

**Why:** parlor now reads `{:error …}` as data, and a refusal is a named reason whose text is the app's. bridge's `send-message!` answers a request that got no response with exactly this, so the screens see one shape whether the fetch failed inside bridge or the send function failed itself.

**Instead of:** keeping `{:error "No answer from the server."}`: English on the screen, in no language the app chose, and an answer parlor takes for the question's data.

## Settled while building

Choices made while building, not asked:

1. **`:http-status nil`, and no `:bridge/request-id`**, as bridge's own refusal for no response at all: no server answered.
2. **doorbell's two messages declare `:refusals {}`**: bridge's browser shapes are three per name now, and neither message is ever refused.
3. **doorbell takes bridge at its tidy-up commit** (`63451ad…`, full `:git/sha`); its tests give `answer-message` the app's meter and the settings' `:metric-buckets`, and the ring handler `:not-found nil`.
