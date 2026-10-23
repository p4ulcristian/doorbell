(ns doorbell.api
  "A change to a table rings every open screen, and the screens that read
   that table ask again: the browser's side. The server's side is this
   namespace's .clj half.

   A store keeps answers by question, [name params]. A screen watches the
   questions it shows; a change the server tells of asks again the watched
   questions whose answers were read from the changed table:

     ;; wire is the app's (doorbell never makes one); bridge takes it
     (def store (doorbell/make-question-store #(bridge/send-message! (:http addresses) shapes-by-name % {:wire wire :headers {}})
                                              {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}))
     (bridge/open-sse-line! (:sse addresses) (merge shapes-by-name doorbell/message-shapes-by-name)
                              (doorbell/make-sse-handlers store)
                              {:wire               wire
                               :on-open            #(when % (doorbell/hear-missed-changes! store))
                               :reconnect-after-ms sse-reconnect-after-ms})
     (def stop (doorbell/watch-question! store [:orders/of-shop {:shop/id shop-id}] show-orders!))
     (doorbell/is-question-being-asked? store [:orders/of-shop {:shop/id shop-id}])  ; a fresher answer coming?
     (doorbell/hear-changed-table! store \"orders\")         ; a write's answer noted it
     (stop)                                                 ; when the screen goes"
  (:require
   [doorbell.settings :as settings]
   [doorbell.store :as store]))

(def message-shapes-by-name
  "The shapes of the server's two messages, for bridge's shapes-by-name:
   {:doorbell/changed {:table \"orders\"}} and {:doorbell/missed {}}."
  {:doorbell/changed {:params-shape nil :answer-shape [:map {:closed true} [:table :string]]}
   :doorbell/missed  {:params-shape nil :answer-shape [:map {:closed true}]}})

(defn make-question-store
  "A store that asks through send-message!: (fn [message]) -> a promise of
   the answer keyed by name, as bridge's send-message! gives it. Settings,
   every key required, checked first (:doorbell/bad-settings):
   :gather-changes-ms, how long changes are gathered before the questions
   they touch are asked again; :keep-unwatched-answers-ms, how long an
   answer no screen watches is kept."
  [send-message! settings]
  (store/make-store send-message! (settings/check-store-settings settings)))

(defn watch-question!
  "(on-answer answer) with the kept answer now, if any, and with each new
   one: asked now, and again when a table it was read from changes. Returns
   a function that stops watching; the answer is kept
   :keep-unwatched-answers-ms after the last watcher stops."
  [store question on-answer]
  (store/watch! store question on-answer))

(defn ask-question-again!
  "Asks a question again now (a refresh button, a try-again, a timer)."
  [store question]
  (store/ask! store question))

(defn find-kept-answer
  "The answer kept for a question, or nil."
  [store question]
  (store/find-kept-answer store question))

(defn is-question-being-asked?
  "Whether an answer newer than the kept one is on its way: the question
   is out now, or a change heard and still gathered (:gather-changes-ms)
   will ask it again. For waiting on the fresh answer rather than taking
   the kept one."
  [store question]
  (store/is-being-asked? store question))

(defn hear-changed-table!
  "A table changed (its name, \"orders\"), as the sse line's
   :doorbell/changed tells it: gathered with the other changes for
   :gather-changes-ms, then every watched question that read it is asked
   again. For a change the browser learns of itself: a write's answer,
   whose notes name the tables it wrote."
  [store table]
  (store/hear-change! store table))

(defn hear-missed-changes!
  "Asks again every watched question: for when the sse line opens again
   after a break, and changes may have gone unheard."
  [store]
  (store/hear-missed! store))

(defn make-sse-handlers
  "The handlers for the server's two messages, to open the sse line with:
   :doorbell/changed asks again what a changed table touches
   (hear-changed-table!), :doorbell/missed asks again everything watched
   (hear-missed-changes!)."
  [store]
  {:doorbell/changed #(hear-changed-table! store (:table %))
   :doorbell/missed  (fn [_] (hear-missed-changes! store))})
