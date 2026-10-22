(ns doorbell.tables-test
  (:require
   [clojure.test :refer [deftest is]]
   [doorbell.api :as doorbell]
   [doorbell.telling :as telling]))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :doorbell :tell})

(deftest every-table-says-whether-it-tells
  (let [problems (try (doorbell/check-doorbell-of-tables [(dissoc shops :doorbell)
                                                          {:table :logs :doorbell [:silent ""]}
                                                          {:table :odd :doorbell :everyone}])
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= 3 (count problems)) "every problem at once")
    (is (re-find #":shops says nothing" (first problems)))
    (is (re-find #"without saying why" (second problems)))
    (is (re-find #"neither :tell nor" (nth problems 2)))))

(deftest a-table-that-tells-gets-its-triggers
  (let [[shops-ready logs-ready] (doorbell/put-doorbell-on-tables [shops {:table :logs :doorbell [:silent "nobody reads logs live"]}])]
    (is (= {:function "doorbell.tell" :arguments []} (:on-change shops-ready))
        "no arguments: the trigger tells only the table")
    (is (= [:on-change nil] (find logs-ready :on-change)) "a silent table writes out :on-change nil")))

(deftest the-listening-settings-are-every-one-required
  (let [{:keys [type errors]} (try (doorbell/start-listening! nil {:batch-window-ms 50 :stop-wiat-ms 2000} {:log (fn [_ _ _])})
                                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorbell/bad-settings type) "refused before it listens")
    (is (= #{:check-stopped-every-ms :reconnect-after-ms :stop-wait-ms :stop-wiat-ms} (set (keys errors))))))

(deftest the-listening-options-take-the-apps-log-meter-and-tables
  (let [{:keys [type errors]} (try (doorbell/start-listening! nil {:batch-window-ms 50 :check-stopped-every-ms 500
                                                                   :reconnect-after-ms 1000 :stop-wait-ms 2000}
                                                              {})
                                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorbell/bad-options type))
    (is (= {:log ["missing required key"] :meter ["missing required key"] :tables ["missing required key"]} errors))))

(deftest a-batch-tells-each-changed-table-once
  (let [tables (telling/list-changed-tables
                ["{\"folder\":\"public\",\"table\":\"orders\"}"
                 "{\"folder\":\"billing\",\"table\":\"invoices\"}"
                 "{\"folder\":\"public\",\"table\":\"orders\"}"])]
    (is (= [:orders :billing/invoices] tables))
    (is (= {:doorbell/changed {:table "orders"}} (telling/make-changed-message (first tables))))
    (is (= {:doorbell/changed {:table "billing/invoices"}} (telling/make-changed-message (second tables))))))

(deftest a-handler-writes-out-its-run-steps-in
  (let [errors (try (doorbell/put-table-notes-into-handlers {:orders/all {:steps []}})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorbell/bad-handlers (:type errors)))
    (is (contains? (get-in errors [:errors :orders/all]) :run-steps-in))))
