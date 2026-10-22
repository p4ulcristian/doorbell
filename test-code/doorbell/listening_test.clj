(ns doorbell.listening-test
  "The server's side against a real Postgres, as an app runs it: the owner
   installs the function and migrates; the app's own role, which owns
   nothing and has no grant on the folder doorbell, writes rows, listens,
   and every open sse line hears which table changed. Runs when
   DOORBELL_TEST_PORT is set (and DOORBELL_TEST_USER, a role that may make
   roles)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [doorbell.api :as doorbell]
   [mailroom.api :as mailroom]
   [wire.api :as wire])
  (:import
   (java.io BufferedReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "DOORBELL_TEST_PORT") parse-long))

(def ^:private test-wire
  "The tests' wire, made as an app makes its own and hands to mailroom
   (the values wire v0.1 had in effect); doorbell never makes one."
  (wire/make-wire-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth         1000
                       :max-number-length         1000
                       :max-string-length         20000000
                       :max-name-length           50000
                       :max-document-length       nil
                       :max-token-count           nil
                       :max-writing-nesting-depth 1000}}))
(def ^:private owner (System/getenv "DOORBELL_TEST_USER"))

(def ^:private shops
  {:table    :shops
   :columns  [[:shop/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes  []
   :gates    nil
   :doorbell :tell})

(def ^:private logs
  {:table    :logs
   :columns  [[:log/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes  []
   :gates    nil
   :doorbell [:silent "no screen shows logs"]})

(defn- query [text] (cellar/make-query-from-sql-text "test" text))

(defn- run-sql! [pool text] (cellar/run-statement! pool (query text) {}))

(def ^:private find-shops (query "SELECT shop__id FROM shops ORDER BY shop__id"))

(def ^:private handlers
  {:shops/all {:params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil
               :steps [{:name :answer-shops :enter identity}]}})

(def ^:private server-settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 1000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000})

(defn- start-pool! [user]
  (cellar/start-pool! {:host "localhost" :port port :database "postgres" :user user :password nil
                       :tls :off :tls-root-cert nil
                       :pool-size 4
                       :connection-wait-ms 30000 :connection-check-wait-ms 5000
                       :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                       :leak-warning-after-ms :none
                       :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                       :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
                       :time-zone "UTC"}))

(def ^:private migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}})

(def ^:private listening-settings
  {:batch-window-ms 50 :check-stopped-every-ms 500 :reconnect-after-ms 1000 :stop-wait-ms 2000})

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorbell CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorbell_app') THEN CREATE ROLE doorbell_app LOGIN; END IF; END $$")
  (let [tables (doorbell/put-doorbell-on-tables [shops logs])]
    (doorbell/install-tell-function! owner-pool)        ; before the triggers: they call it
    (with-out-str @(:slow-part (cellar/migrate! owner-pool tables [] migrate-options)))
    (cellar/grant-tables-to-role! owner-pool tables "doorbell_app")))

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

(defn- wait-for-lines [doorbell n]
  (loop [tries 50]
    (when (and (pos? tries) (not= n (doorbell/count-open-lines doorbell)))
      (Thread/sleep 50)
      (recur (dec tries)))))

(deftest a-change-rings-every-open-line
  (if-not port
    (println "doorbell.listening-test: skipped, DOORBELL_TEST_PORT is not set")
    (let [owner-pool (start-pool! owner)]
      (try
        (make-database! owner-pool)
        (let [app-pool (start-pool! "doorbell_app")
              doorbell (doorbell/start-listening! app-pool listening-settings)
              server   (mailroom/start-server!
                        (mailroom/make-ring-handler-from-routes
                         (mailroom/make-routes-for-messages
                          (doorbell/put-table-notes-into-handlers handlers) server-settings
                          {:wire                test-wire
                           :find-rate-limit-key #(mailroom/find-client-address % nil)
                           :on-sse-open #(doorbell/add-sse-line! doorbell %) :on-sse-close #(doorbell/remove-sse-line! doorbell %)
                           :on-ws-open nil :on-ws-close nil})
                         {:files nil})
                        server-settings)]
          (try
            (testing "the app's own role owns nothing and has no grant on the folder doorbell"
              (is (= {:test/owns-nothing true :test/no-usage true}
                     (cellar/find-one-row app-pool (query (str "SELECT NOT EXISTS (SELECT FROM pg_tables WHERE tableowner = current_user) AS test__owns_nothing, "
                                                               "NOT has_schema_privilege('doorbell', 'USAGE') AS test__no_usage"))
                                          {}))))
            (let [line-1 (open-sse (:port server))
                  line-2 (open-sse (:port server))]
              (wait-for-lines doorbell 2)
              (is (= 2 (doorbell/count-open-lines doorbell)))
              (testing "a row the app's role writes: every line hears its table, nothing more"
                (run-sql! app-pool "INSERT INTO shops DEFAULT VALUES")
                (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data line-1)))
                (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data line-2))))
              (testing "one transaction's statements on one table ring once"
                (cellar/run-in-transaction! app-pool {:read-only? false}
                                            (fn [transaction]
                                              (run-sql! transaction "INSERT INTO shops DEFAULT VALUES")
                                              (run-sql! transaction "UPDATE shops SET shop__id = shop__id")))
                (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data line-1)))
                (is (= "{\"doorbell/changed\":{\"table\":\"shops\"}}" (read-next-data line-2)))
                (is (= :nothing (read-next-data line-1))))
              (testing "a silent table, and a statement that changed no row, ring nobody"
                (run-sql! app-pool "INSERT INTO logs DEFAULT VALUES")
                (run-sql! app-pool "DELETE FROM shops WHERE false")
                (is (= :nothing (read-next-data line-1))))
              (testing "a rolled-back transaction rings nobody"
                (try (cellar/run-in-transaction! app-pool {:read-only? false}
                                                 (fn [transaction]
                                                   (run-sql! transaction "INSERT INTO shops DEFAULT VALUES")
                                                   (throw (ex-info "roll back" {}))))
                     (catch clojure.lang.ExceptionInfo _))
                (is (= :nothing (read-next-data line-2)))))
            (testing "an answer comes with the tables its steps read"
              (let [ready (doorbell/put-table-notes-into-handlers
                           {:shops/all {:params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil
                                        :steps [{:name  :answer-shops
                                                 :enter #(mailroom/add-answer-to-context % (count (cellar/find-rows app-pool find-shops {})))}]}})]
                (is (= {:shops/all 2 :mailroom/notes {:shops/all {:tables ["shops"]}}}
                       (mailroom/answer-message ready :no-rate-limits {} {:shops/all {}} {:wire test-wire})))))
            (testing "the notes are taken inside the handler's own :run-steps-in"
              (let [around (atom [])
                    ready  (doorbell/put-table-notes-into-handlers
                            {:shops/all {:params-shape nil :answer-shape nil :rate-limit nil
                                         :run-steps-in (fn [run context]
                                                         (swap! around conj :before)
                                                         (cellar/find-rows app-pool (query "SELECT log__id FROM logs") {})
                                                         (run context))
                                         :steps [{:name :answer :enter #(mailroom/add-answer-to-context % 0)}]}})]
                (is (= {:shops/all {:tables []}} (:mailroom/notes (mailroom/answer-message ready :no-rate-limits {} {:shops/all {}} {:wire test-wire})))
                    "logs, read around the steps, isn't noted")
                (is (= [:before] @around))))
            (finally
              (mailroom/stop-server! server)
              (is (true? (doorbell/stop-listening! doorbell)) "the listener ended within its :stop-wait-ms")
              (cellar/stop-pool! app-pool))))
        (finally (cellar/stop-pool! owner-pool))))))
