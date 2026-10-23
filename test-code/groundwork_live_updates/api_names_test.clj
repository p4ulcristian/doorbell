(ns groundwork-live-updates.api-names-test
  "The public names of groundwork-live-updates's api, the server's side. Adding or
   removing one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-live-updates.api]))

(deftest the-api-names
  (is (= '#{store-settings-shape
           listening-settings-shape
           function-files
           check-live-updates-of-tables
           put-live-updates-on-tables
           put-table-notes-into-handlers
           add-sse-line!
           remove-sse-line!
           start-listening!
           stop-listening!
           count-open-lines}
         (set (keys (ns-publics 'groundwork-live-updates.api))))))
