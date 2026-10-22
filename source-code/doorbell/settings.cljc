(ns doorbell.settings
  "doorbell's settings, and the data its functions take, as malli shapes,
   checked before anything starts. Nothing has a default: every key is
   written out, and a missing or unknown one refuses, named, with every
   other problem at once. One file for both sides: an app that says the
   browser's settings on the server checks them there too."
  (:require
   [wire.api :as wire]))

(def store-settings-shape
  "The question store's settings, in the browser, every key required.
   :gather-changes-ms: how long the store gathers the changes it hears
   before it asks again the watched questions they touch, so a burst of
   changes is one round of asking.
   :keep-unwatched-answers-ms: how long an answer no screen watches is
   kept, shown at once when a screen watches its question again, before
   it is dropped."
  [:map {:closed true}
   [:gather-changes-ms [:int {:min 0}]]
   [:keep-unwatched-answers-ms [:int {:min 0}]]])

(def listening-settings-shape
  "The server's listening settings, every key required: what
   start-listening! hands cellar's start-listening!.
   :batch-window-ms: after the first change is heard, how long to wait for
   more, told together as one batch (each table once); 0: what has arrived
   by then.
   :check-stopped-every-ms: how long one wait for a change lasts before the
   listener looks whether it was stopped.
   :reconnect-after-ms: after the connection broke, how long to wait before
   listening again (every line is told {:doorbell/missed {}} then).
   :stop-wait-ms: how long stop-listening! waits for the listener to end."
  [:map {:closed true}
   [:batch-window-ms [:int {:min 0}]]
   [:check-stopped-every-ms [:int {:min 1}]]
   [:reconnect-after-ms [:int {:min 0}]]
   [:stop-wait-ms [:int {:min 0}]]])

(def handler-shape
  "What put-table-notes-into-handlers reads of a handler: its
   :run-steps-in, mailroom's, written out, nil to run the steps plainly.
   The rest of the handler is mailroom's to check."
  [:map
   [:run-steps-in [:maybe fn?]]])

(def handlers-shape
  [:map-of :keyword handler-shape])

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [errors (wire/find-errors-in-data-for-shape shape data)]
    (throw (ex-info (str what " have problems: " (pr-str errors))
                    {:type kind :errors errors})))
  data)

(defn check-store-settings [settings]
  (check-data-for-shape "The settings" :doorbell/bad-settings store-settings-shape settings))

(defn check-listening-settings [settings]
  (check-data-for-shape "The settings" :doorbell/bad-settings listening-settings-shape settings))

(defn check-handlers [handlers]
  (check-data-for-shape "The handlers" :doorbell/bad-handlers handlers-shape handlers))
