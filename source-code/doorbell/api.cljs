(ns doorbell.api
  "A change to a table rings every open screen, and the screens that read
   that table ask again: the browser's side. The server's side is this
   namespace's .clj half.

   A store keeps answers by question, [name params]. A screen watches the
   questions it shows; a change the server tells of asks again the watched
   questions whose answers were read from the changed table:

     ;; wire is the app's (doorbell never makes one); mailroom takes it
     (def store (doorbell/make-question-store #(mailroom/send-message! (:http addresses) shapes-by-name % {:wire wire})
                                              {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}))
     (mailroom/open-sse-line! (:sse addresses) (merge shapes-by-name doorbell/message-shapes-by-name)
                              (doorbell/make-sse-handlers store)
                              {:wire               wire
                               :on-open            #(when % (doorbell/hear-missed-changes! store))
                               :reconnect-after-ms sse-reconnect-after-ms})
     (def stop (doorbell/watch-question! store [:orders/of-shop {:shop/id shop-id}] show-orders!))
     (stop)                                                 ; when the screen goes"
  (:require
   [doorbell.settings :as settings]
   [doorbell.store :as store]))

(def message-shapes-by-name
  "The shapes of the server's two messages, for mailroom's shapes-by-name:
   {:doorbell/changed {:table \"orders\"}} and {:doorbell/missed {}}."
  {:doorbell/changed {:params-shape nil :answer-shape [:map {:closed true} [:table :string]]}
   :doorbell/missed  {:params-shape nil :answer-shape [:map {:closed true}]}})

(defn make-question-store
  "A store that asks through send-message!: (fn [message]) -> a promise of
   the answer keyed by name, as mailroom's send-message! gives it. Settings,
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

(defn make-sse-handlers
  "The handlers for the server's two messages, to open the sse line with:
   :doorbell/changed asks again what a changed table touches,
   :doorbell/missed asks again everything watched."
  [store]
  {:doorbell/changed #(store/hear-change! store (:table %))
   :doorbell/missed  (fn [_] (store/hear-missed! store))})

(defn hear-missed-changes!
  "Asks again every watched question: for when the sse line opens again
   after a break, and changes may have gone unheard."
  [store]
  (store/hear-missed! store))
