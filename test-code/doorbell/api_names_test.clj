(ns doorbell.api-names-test
  "The public names of doorbell's api, the server's side. Adding or
   removing one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [doorbell.api]))

(deftest the-api-names
  (is (= '#{store-settings-shape
           listening-settings-shape
           function-files
           check-doorbell-of-tables
           put-doorbell-on-tables
           put-table-notes-into-handlers
           add-sse-line!
           remove-sse-line!
           start-listening!
           stop-listening!
           count-open-lines}
         (set (keys (ns-publics 'doorbell.api))))))
