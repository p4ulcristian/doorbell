# doorbell decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

All but the last four were made while doorbell was a library inside Groundwork, a Clojure framework (groundwork.server.sync and groundwork.browser.sync, called bells before); they name its parts (its schema, its sessions and access, transport, `:sync`, `groundwork.tell_sync`) where they came from. The ones they name that stayed with Groundwork are named by title, in plain text. Groundwork is now house, the part of manor (the group of libraries this one belongs to) that puts them together; the decisions that stayed with Groundwork are in house's `decisions/`.

## In force

- [Postgres rings the bell](2026-10-12-postgres-rings-the-bell.md), changed in part by [sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md)
- [The browser keeps answers by question](2026-10-12-the-browser-keeps-answers-by-question.md), changed in part by [Asking again, and bells that miss nothing](2026-10-14-asking-again-and-bells-that-miss-nothing.md)
- [Asking again, and bells that miss nothing](2026-10-14-asking-again-and-bells-that-miss-nothing.md), changed in part by [sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md)
- [sync v1: triggers tell, the listener tells the readers, answers say what they read](2026-10-22-sync-v1.md), changed in part by [sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md) and [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md)
- [The question store v1](2026-10-22-the-question-store-v1.md), changed in part by [sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md) and [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md), added to by [A change can be told by the browser, and an answer on its way is known](2026-10-23-a-change-can-be-told-and-an-answer-on-its-way-known.md)
- [sync tells by table and trusts the gates](2026-10-22-sync-tells-by-table-and-trusts-the-gates.md), changed in part by [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md)
- [doorbell is its own library](2026-10-22-doorbell-is-its-own-library.md), changed in part by [Listening's numbers are doorbell's settings](2026-10-22-listening-s-numbers-are-settings.md) and [doorbell takes no wire](2026-10-22-doorbell-takes-no-wire.md)
- [Listening's numbers are doorbell's settings; a silent table writes out its :on-change](2026-10-22-listening-s-numbers-are-settings.md), changed in part by [The app passes its log](2026-10-22-the-app-passes-its-log.md)
- [doorbell takes no wire: lines carry the app's, Postgres's messages are read with jsonista](2026-10-22-doorbell-takes-no-wire.md)
- [The app passes its log; the notes are taken with the message's](2026-10-22-the-app-passes-its-log.md), changed in part by [Changes heard and lines told are counted on the app's meter](2026-10-23-changes-and-tells-are-counted-on-the-apps-meter.md)
- [A change can be told by the browser, and an answer on its way is known](2026-10-23-a-change-can-be-told-and-an-answer-on-its-way-known.md)
- [Changes heard and lines told are counted on the app's meter](2026-10-23-changes-and-tells-are-counted-on-the-apps-meter.md)
