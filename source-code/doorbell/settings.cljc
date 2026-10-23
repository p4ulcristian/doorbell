(ns doorbell.settings
  "doorbell's settings, and the data its functions take, as malli shapes,
   checked before anything starts. Nothing has a default: every key is
   written out, and a missing or unknown one refuses, named, with every
   other problem at once. One file for both sides: an app that says the
   browser's settings on the server checks them there too. They are
   checked by malli itself: they are values the app hands in, not data
   crossing an edge."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]))

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

(def listening-options-shape
  "start-listening!'s options, every key required. :log: the app's log
   function (logbook's), handed to cellar's listener: a break in listening
   and a failure telling the lines are written to it. :meter: the app's
   meter (meter's make-meter): doorbell's metrics are declared on it, and
   cellar's listener counts its reconnects on it; one make-meter didn't
   make is refused (:meter/not-a-meter). :tables: the app's table maps, as
   put-doorbell-on-tables takes them: the ones that tell are the set the
   changes are counted by."
  [:map {:closed true}
   [:log [fn? {:error/message "should be the app's log function, made by logbook"}]]
   [:meter [some? {:error/message "should be the app's meter, made by meter.api/make-meter"}]]
   [:tables [:sequential map?]]])

(def handler-shape
  "What put-table-notes-into-handlers reads of a handler: its
   :run-steps-in, bridge's, written out, nil to run the steps plainly.
   The rest of the handler is bridge's to check."
  [:map
   [:run-steps-in [:maybe fn?]]])

(def handlers-shape
  [:map-of :keyword handler-shape])

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

(defn check-store-settings [settings]
  (check-data-for-shape "The settings" :doorbell/bad-settings store-settings-shape settings))

(defn check-listening-settings [settings]
  (check-data-for-shape "The settings" :doorbell/bad-settings listening-settings-shape settings))

(defn check-listening-options [options]
  (check-data-for-shape "The options" :doorbell/bad-options listening-options-shape options))

(defn check-handlers [handlers]
  (check-data-for-shape "The handlers" :doorbell/bad-handlers handlers-shape handlers))
