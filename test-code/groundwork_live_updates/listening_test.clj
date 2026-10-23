(ns groundwork-live-updates.listening-test
  "The server's side against a real Postgres, as an app runs it: the owner
   installs the function and migrates; the app's own role, which owns
   nothing and has no grant on the folder groundwork-live-updates, writes rows, listens,
   and every open sse line hears which table changed. Runs when
   LIVE_UPDATES_TEST_PORT is set (and LIVE_UPDATES_TEST_USER, a role that may make
   roles)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-live-updates.api :as live-updates]
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]
   [groundwork-bridge.api :as bridge]
   [groundwork-metrics.api :as metrics]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (java.io BufferedReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "LIVE_UPDATES_TEST_PORT") parse-long))

(def ^:private test-shaper
  "The tests' groundwork-shaped-json, made as an app makes its own and hands to bridge
   (the values groundwork-shaped-json v0.1 had in effect); groundwork-live-updates never makes one."
  (shaped-json/make-shaper-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth         1000
                       :max-number-length         1000
                       :max-string-length         20000000
                       :max-name-length           50000
                       :max-document-length       nil
                       :max-token-count           nil
                       :max-writing-nesting-depth 1000}}))
(def ^:private lines
  "The lines the tests' log wrote, as JSON data."
  (atom []))

(def ^:private log
  "The tests' log, made as an app makes its own; groundwork-live-updates never makes one."
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 10
                                               :write-to     #(swap! lines conj (json/read-value %))})))

(def ^:private owner (System/getenv "LIVE_UPDATES_TEST_USER"))

(def ^:private shops
  {:table    :shops
   :columns  [[:shop/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes  []
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :gates    nil
   :live-updates :tell})

(def ^:private logs
  {:table    :logs
   :columns  [[:log/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes  []
   :uniques  []
   :checks   []
   :references []
   :triggers  []
   :gates    nil
   :live-updates [:silent "no screen shows logs"]})

(defn- query [text] (postgres/make-query-from-sql-text "test" text))

(defn- run-sql! [pool text] (postgres/run-statement! pool (query text) {}))

(def ^:private find-shops (query "SELECT shop__id FROM shops ORDER BY shop__id"))

(def ^:private handlers
  {:shops/all {:params-shape nil :answer-shape nil :refusals {} :rate-limit nil :run-steps-in nil
               :steps [{:name :answer-shops :enter identity}]}})

(def ^:private server-settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 4194304
   :idle-timeout-ms 60000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 1000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.01 0.1 1]}})

(defn- make-meter
  "A meter, as an app makes its own."
  []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 10000} {:log log}))

(defn- find-metric-lines [a-meter prefix]
  (filterv #(str/starts-with? % prefix) (str/split-lines (metrics/write-metrics-text a-meter))))

(defn- start-pool! [user]
  (postgres/start-pool! {:host "localhost" :port port :database "postgres" :user user :password nil
                       :tls :off :tls-root-cert nil
                       :pool-size 4
                       :connection-wait-ms 30000 :connection-check-wait-ms 5000
                       :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                       :leak-warning-after-ms :none
                       :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                       :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
                       :time-zone "UTC"
                       :metric-buckets {:query-seconds [0.01 0.1 1] :connection-acquire-seconds [0.01 0.1 1]
                                        :connection-usage-seconds [0.01 0.1 1]}}
                      ;; a meter per pool, as groundwork-postgres takes it: the app's pool's is the app's meter
                      {:log log :meter (make-meter)}))

(def ^:private migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}})

(def ^:private listening-settings
  {:batch-window-ms 50 :check-stopped-every-ms 500 :reconnect-after-ms 1000 :stop-wait-ms 2000})

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_live_updates CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'groundwork_live_updates_app') THEN CREATE ROLE groundwork_live_updates_app LOGIN; END IF; END $$")
  (let [tables (live-updates/put-live-updates-on-tables [shops logs])]
    @(:slow-part (postgres/migrate! owner-pool {:tables tables :extensions [] :functions live-updates/function-files :views []}
                                  [] (assoc migrate-options :log log)))
    (postgres/grant-tables-to-role! owner-pool tables "groundwork_live_updates_app")))

(defn- open-sse [server-port]
  (io/reader (.body (.send (HttpClient/newHttpClient)
                           (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" server-port "/mail/sse"))))
                           (HttpResponse$BodyHandlers/ofInputStream)))))

(defn- read-next-data
  "The next sse data line's JSON text, or :nothing within 2 s."
  [^BufferedReader reader]
  (deref (future (loop []
                   (let [line (.readLine reader)]
                     (cond (nil? line)                      :closed
                           (str/starts-with? line "data: ") (subs line 6)
                           :else                            (recur)))))
         2000 :nothing))

(defn- wait-for [done?]
  (loop [tries 100]
    (when (and (pos? tries) (not (done?)))
      (Thread/sleep 50)
      (recur (dec tries)))))

(defn- wait-for-lines [live-updates n]
  (loop [tries 50]
    (when (and (pos? tries) (not= n (live-updates/count-open-lines live-updates)))
      (Thread/sleep 50)
      (recur (dec tries)))))

(deftest a-change-rings-every-open-line
  (if-not port
    (println "groundwork-live-updates.listening-test: skipped, LIVE_UPDATES_TEST_PORT is not set")
    (let [owner-pool (start-pool! owner)]
      (try
        (make-database! owner-pool)
        (let [app-pool (start-pool! "groundwork_live_updates_app")
              ;; one meter for the app: the pool's, bridge's and groundwork-live-updates's metrics
              app-meter (:groundwork-postgres/meter app-pool)
              live-updates (live-updates/start-listening! app-pool listening-settings {:log log :meter app-meter :tables [shops logs]})
              server   (bridge/start-server!
                        (bridge/make-ring-handler-from-routes
                         (bridge/make-routes-for-messages
                          (live-updates/put-table-notes-into-handlers handlers) server-settings
                          {:shaper                test-shaper
                           :log                 log
                           :meter               app-meter
                           :find-rate-limit-key #(bridge/find-client-address % nil)
                           :on-sse-open #(live-updates/add-sse-line! live-updates %) :on-sse-close #(live-updates/remove-sse-line! live-updates %)
                           :on-ws-open nil :on-ws-close nil})
                         {:files nil :not-found nil})
                        server-settings
                        {:log log})]
          (try
            (testing "the app's own role owns nothing and has no grant on the folder groundwork-live-updates"
              (is (= {:test/owns-nothing true :test/no-usage true}
                     (postgres/find-one-row app-pool (query (str "SELECT NOT EXISTS (SELECT FROM pg_tables WHERE tableowner = current_user) AS test__owns_nothing, "
                                                               "NOT has_schema_privilege('doorbell', 'USAGE') AS test__no_usage"))
                                          {}))))
            (let [line-1 (open-sse (:port server))
                  line-2 (open-sse (:port server))]
              (wait-for-lines live-updates 2)
              (is (= 2 (live-updates/count-open-lines live-updates)))
              (testing "a row the app's role writes: every line hears its table, nothing more"
                (run-sql! app-pool "INSERT INTO shops DEFAULT VALUES")
                (is (= "{\"groundwork-live-updates/changed\":{\"table\":\"shops\"}}" (read-next-data line-1)))
                (is (= "{\"groundwork-live-updates/changed\":{\"table\":\"shops\"}}" (read-next-data line-2))))
              (testing "one transaction's statements on one table ring once"
                (postgres/run-in-transaction! app-pool {:read-only? false :log nil}
                                            (fn [transaction]
                                              (run-sql! transaction "INSERT INTO shops DEFAULT VALUES")
                                              (run-sql! transaction "UPDATE shops SET shop__id = shop__id")))
                (is (= "{\"groundwork-live-updates/changed\":{\"table\":\"shops\"}}" (read-next-data line-1)))
                (is (= "{\"groundwork-live-updates/changed\":{\"table\":\"shops\"}}" (read-next-data line-2)))
                (is (= :nothing (read-next-data line-1))))
              (testing "a silent table, and a statement that changed no row, ring nobody"
                (run-sql! app-pool "INSERT INTO logs DEFAULT VALUES")
                (run-sql! app-pool "DELETE FROM shops WHERE false")
                (is (= :nothing (read-next-data line-1))))
              (testing "a rolled-back transaction rings nobody"
                (try (postgres/run-in-transaction! app-pool {:read-only? false :log nil}
                                                 (fn [transaction]
                                                   (run-sql! transaction "INSERT INTO shops DEFAULT VALUES")
                                                   (throw (ex-info "roll back" {}))))
                     (catch clojure.lang.ExceptionInfo _))
                (is (= :nothing (read-next-data line-2))))
              (testing "the changes heard, by table, and the lines told, on the app's meter"
                (is (= ["groundwork_live_updates_changes_total{table=\"shops\"} 2"] (find-metric-lines app-meter "groundwork_live_updates_changes_total")))
                (is (= ["groundwork_live_updates_tells_total{message=\"changed\"} 4"] (find-metric-lines app-meter "groundwork_live_updates_tells_total")))
                (is (= ["groundwork_live_updates_listening 1"] (find-metric-lines app-meter "groundwork_live_updates_listening"))))
              (testing "a break in listening: every line told it may have missed changes, counted; groundwork-postgres counts the reconnect"
                (run-sql! owner-pool "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query = 'LISTEN groundwork-live-updates'")
                ;; read from the counts: a line's reader from a test above may still be waiting on it
                (wait-for #(= 2 (count (find-metric-lines app-meter "groundwork_live_updates_tells_total"))))
                (is (= ["groundwork_live_updates_tells_total{message=\"changed\"} 4" "groundwork_live_updates_tells_total{message=\"missed\"} 2"]
                       (find-metric-lines app-meter "groundwork_live_updates_tells_total")))
                (is (= ["groundwork_postgres_listening_reconnects_total 1"] (find-metric-lines app-meter "groundwork_postgres_listening_reconnects_total")))
                (is (= ["groundwork_live_updates_listening 1"] (find-metric-lines app-meter "groundwork_live_updates_listening")))))
            (testing "an answer comes with the tables its steps read"
              (let [ready (live-updates/put-table-notes-into-handlers
                           {:shops/all {:params-shape nil :answer-shape nil :refusals {} :rate-limit nil :run-steps-in nil
                                        :steps [{:name  :answer-shops
                                                 :enter #(bridge/add-answer-to-context % (count (postgres/find-rows app-pool find-shops {})))}]}})]
                (is (= {:shops/all 2 :groundwork-bridge/notes {:shops/all {:tables ["shops"]}}}
                       (bridge/answer-message ready :no-rate-limits {} {:shops/all {}} {:shaper test-shaper :log log :meter app-meter :metric-buckets (:metric-buckets server-settings)})))))
            (testing "a query whose tables the planner can't say: written to the message's log, with its request id"
              (reset! lines [])
              (let [ready (live-updates/put-table-notes-into-handlers
                           {:shops/all {:params-shape nil :answer-shape nil :refusals {} :rate-limit nil :run-steps-in nil
                                        :steps [{:name  :ask-untyped
                                                 :enter #(bridge/add-answer-to-context
                                                          % (:b (postgres/find-one-row app-pool (query "SELECT :x IS NULL AS b") {:x "a"})))}]}})]
                (is (= {:shops/all false :groundwork-bridge/notes {:shops/all {:tables "any"}}}
                       (bridge/answer-message ready :no-rate-limits {} {:shops/all {}} {:shaper test-shaper :log log :meter app-meter :metric-buckets (:metric-buckets server-settings)})))
                (let [[line] (filter #(= "groundwork-postgres/tables-read-unknown" (get % "event")) @lines)]
                  (is (= "warn" (get line "level")))
                  (is (some? (get-in line ["context" "request-id"]))))))
            (testing "the notes are taken inside the handler's own :run-steps-in"
              (let [around (atom [])
                    ready  (live-updates/put-table-notes-into-handlers
                            {:shops/all {:params-shape nil :answer-shape nil :refusals {} :rate-limit nil
                                         :run-steps-in (fn [run context]
                                                         (swap! around conj :before)
                                                         (postgres/find-rows app-pool (query "SELECT log__id FROM logs") {})
                                                         (run context))
                                         :steps [{:name :answer :enter #(bridge/add-answer-to-context % 0)}]}})]
                (is (= {:shops/all {:tables []}} (:groundwork-bridge/notes (bridge/answer-message ready :no-rate-limits {} {:shops/all {}} {:shaper test-shaper :log log :meter app-meter :metric-buckets (:metric-buckets server-settings)})))
                    "logs, read around the steps, isn't noted")
                (is (= [:before] @around))))
            (finally
              (bridge/stop-server! server)
              (is (true? (live-updates/stop-listening! live-updates)) "the listener ended within its :stop-wait-ms")
              (is (= ["groundwork_live_updates_listening 0"] (find-metric-lines app-meter "groundwork_live_updates_listening")) "not once stopped")
              (postgres/stop-pool! app-pool))))
        (finally (postgres/stop-pool! owner-pool))))))

(deftest the-options-are-required-and-a-table-outside-the-set-is-other
  (testing "the app's log, meter and tables, before anything listens"
    (is (= {:type :groundwork-live-updates/bad-options :errors {:meter ["missing required key"] :tables ["missing required key"]}}
           (try (live-updates/start-listening! nil listening-settings {:log log})
                (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :errors])))))
    (is (= :groundwork-metrics/not-a-meter
           (try (live-updates/start-listening! nil listening-settings {:log log :meter {} :tables [shops]})
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
  (testing "a change to a table the set doesn't hold is counted as other"
    (let [m        (make-meter)
          live-updates {:lines (atom {}) :meter m}]
      (#'groundwork-live-updates.metrics/declare-metrics! m [logs shops] (atom nil))
      (#'groundwork-live-updates.api/tell-of-notifications! live-updates [{:channel "groundwork_live_updates" :payload "{\"folder\":\"public\",\"table\":\"shops\"}"}
                                                      {:channel "groundwork_live_updates" :payload "{\"folder\":\"public\",\"table\":\"logs\"}"}
                                                      {:channel "groundwork_live_updates" :payload "{\"folder\":\"billing\",\"table\":\"invoices\"}"}])
      (is (= ["groundwork_live_updates_changes_total{table=\"other\"} 2" "groundwork_live_updates_changes_total{table=\"shops\"} 1"]
             (find-metric-lines m "groundwork_live_updates_changes_total"))
          "logs is silent, billing/invoices isn't in the tables: both other")
      (is (= ["groundwork_live_updates_listening 0"] (find-metric-lines m "groundwork_live_updates_listening")) "no listener yet"))))

