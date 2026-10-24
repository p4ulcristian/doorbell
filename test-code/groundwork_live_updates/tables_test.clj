(ns groundwork-live-updates.tables-test
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-live-updates.api :as live-updates]
   [groundwork-live-updates.telling :as telling]))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :live-updates :tell})

(deftest every-table-says-whether-it-tells
  (let [problems (try (live-updates/check-live-updates-of-tables [(dissoc shops :live-updates)
                                                          {:table :logs :live-updates [:silent ""]}
                                                          {:table :odd :live-updates :everyone}])
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= 3 (count problems)) "every problem at once")
    (is (re-find #":shops says nothing" (first problems)))
    (is (re-find #"without saying why" (second problems)))
    (is (re-find #"neither :tell nor" (nth problems 2)))))

(deftest a-table-that-tells-gets-its-triggers
  (let [[shops-ready logs-ready] (live-updates/put-live-updates-on-tables [shops {:table :logs :live-updates [:silent "nobody reads logs live"]}])]
    (is (= {:function "groundwork_live_updates.tell" :arguments []} (:on-change shops-ready))
        "no arguments: the trigger tells only the table")
    (is (= [:on-change nil] (find logs-ready :on-change)) "a silent table writes out :on-change nil")))

(deftest the-listening-settings-are-every-one-required
  (let [{:keys [type errors]} (try (live-updates/start-listening! nil {:batch-window-ms 50 :stop-wiat-ms 2000} {:log (fn [_ _ _])})
                                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-live-updates/bad-settings type) "refused before it listens")
    (is (= #{:check-stopped-every-ms :reconnect-after-ms :stop-wait-ms :stop-wiat-ms} (set (keys errors))))))

(deftest the-listening-options-take-the-apps-log-meter-tables-and-lines
  (let [{:keys [type errors]} (try (live-updates/start-listening! nil {:batch-window-ms 50 :check-stopped-every-ms 500
                                                                   :reconnect-after-ms 1000 :stop-wait-ms 2000}
                                                              {})
                                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-live-updates/bad-options type))
    (is (= {:log ["missing required key"] :meter ["missing required key"] :tables ["missing required key"]
            :push! ["missing required key"] :find-line-key ["missing required key"]}
           errors)
        "how a line is told and told apart is the app's wire's, handed in")))

(deftest a-batch-tells-each-changed-table-once
  (let [tables (telling/list-changed-tables
                ["{\"folder\":\"public\",\"table\":\"orders\"}"
                 "{\"folder\":\"billing\",\"table\":\"invoices\"}"
                 "{\"folder\":\"public\",\"table\":\"orders\"}"])]
    (is (= [:orders :billing/invoices] tables))
    (is (= {:groundwork-live-updates/changed {:table "orders"}} (telling/make-changed-message (first tables))))
    (is (= {:groundwork-live-updates/changed {:table "billing/invoices"}} (telling/make-changed-message (second tables))))))

(deftest a-handler-writes-out-its-run-steps-in
  (let [errors (try (live-updates/put-table-notes-into-handlers {:orders/all {:steps []}})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-live-updates/bad-handlers (:type errors)))
    (is (contains? (get-in errors [:errors :orders/all]) :run-steps-in))))
