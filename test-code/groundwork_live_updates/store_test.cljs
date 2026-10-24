(ns groundwork-live-updates.store-test
  "The browser's question store, on node, asking through a stand-in
   send-message! that answers at once with the tables each question read.
   Requiring groundwork-live-updates.api proves the browser's side compiles."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [groundwork-live-updates.api :as live-updates]))

(def ^:private settings {:gather-changes-ms 20 :keep-unwatched-answers-ms 40})

(defn- make-stand-in-server
  "A send-message! that counts asks by name and answers with the count,
   noting the tables tables-by-name says each name reads; a name it
   doesn't know is answered with no notes."
  [tables-by-name]
  (let [asked (atom {})]
    {:asked         asked
     :send-message! (fn [message]
                      (let [[message-name] (first message)
                            n              (get (swap! asked update message-name (fnil inc 0)) message-name)]
                        (js/Promise.resolve
                         (cond-> {message-name n}
                           (contains? tables-by-name message-name)
                           (assoc :groundwork-steps/notes {message-name {:tables (get tables-by-name message-name)}})))))}))

(defn- after-ms [ms f] (js/setTimeout f ms))

(deftest a-change-asks-again-what-read-its-table
  (async done
         (let [{:keys [asked send-message!]} (make-stand-in-server {:orders/all ["orders"] :shops/all ["shops"]
                                                                    :totals/all "any"})
               store    (live-updates/make-question-store send-message! settings)
               heard    (atom [])
               handlers (live-updates/make-sse-handlers store)]
           (live-updates/watch-question! store [:orders/all {}] #(swap! heard conj %))
           (live-updates/watch-question! store [:shops/all {}] identity)
           (live-updates/watch-question! store [:totals/all {}] identity)
           (live-updates/watch-question! store [:untold/all {}] identity)
           (after-ms 10
                     (fn []
                       (is (= {:orders/all 1 :shops/all 1 :totals/all 1 :untold/all 1} @asked) "watching asks")
                       (is (= [1] @heard))
                       ((:groundwork-live-updates/changed handlers) {:table "orders"})
                       ((:groundwork-live-updates/changed handlers) {:table "orders"})
                       (after-ms 5
                                 (fn []
                                   (is (= 1 (:orders/all @asked)) "gathered: not asked yet")
                                   (after-ms 40
                                             (fn []
                                               (is (= {:orders/all 2 :shops/all 1 :totals/all 2 :untold/all 2} @asked)
                                                   "orders once for two changes; \"any\" and no notes on every change; shops not")
                                               (is (= [1 2] @heard))
                                               ((:groundwork-live-updates/missed handlers) {})
                                               (after-ms 10
                                                         (fn []
                                                           (is (= {:orders/all 3 :shops/all 2 :totals/all 3 :untold/all 3} @asked)
                                                               "missed: every watched question")
                                                           (done))))))))))))

(deftest an-unwatched-answer-is-kept-then-dropped
  (async done
         (let [{:keys [send-message!]} (make-stand-in-server {:orders/all ["orders"]})
               store (live-updates/make-question-store send-message! settings)
               stop  (live-updates/watch-question! store [:orders/all {}] identity)]
           (after-ms 10
                     (fn []
                       (stop)
                       (is (= 1 (live-updates/find-kept-answer store [:orders/all {}])) "kept while unwatched")
                       (after-ms 15
                                 (fn []
                                   (is (= 1 (live-updates/find-kept-answer store [:orders/all {}])) "within :keep-unwatched-answers-ms")
                                   (after-ms 50
                                             (fn []
                                               (is (nil? (live-updates/find-kept-answer store [:orders/all {}])) "then dropped")
                                               (done))))))))))

(deftest no-answer-from-the-server
  (async done
         (let [store (live-updates/make-question-store (fn [_] (js/Promise.reject (js/Error. "offline"))) settings)
               heard (atom [])]
           (live-updates/watch-question! store [:orders/all {}] #(swap! heard conj %))
           (after-ms 10
                     (fn []
                       (is (= [{:groundwork-steps/refused :groundwork-steps/no-answer :groundwork-steps/data {:http-status nil}}] @heard)
                           "groundwork-steps's refusal for no response, no request id")
                       (done))))))

(deftest the-store-says-its-settings
  (let [problems (try (live-updates/make-question-store identity {:gather-changes-ms 50})
                      nil
                      (catch :default e (ex-data e)))]
    (is (= :groundwork-live-updates/bad-settings (:type problems)) "refused, nothing filled in")
    (is (= #{:keep-unwatched-answers-ms} (set (keys (:errors problems)))))))

(deftest the-messages-have-their-shapes
  (testing "the three shapes for each of the server's two messages, as the wire (groundwork-bridge) takes them"
    (is (= #{:groundwork-live-updates/changed :groundwork-live-updates/missed} (set (keys live-updates/message-shapes-by-name))))
    (is (every? #(= #{:params-shape :answer-shape :refusals} (set (keys %))) (vals live-updates/message-shapes-by-name)))
    (is (every? #(= {} (:refusals %)) (vals live-updates/message-shapes-by-name)) "never refused")))

(deftest a-change-the-browser-learns-of-itself
  (async done
         (let [{:keys [asked send-message!]} (make-stand-in-server {:orders/all ["orders"] :shops/all ["shops"]})
               store (live-updates/make-question-store send-message! settings)]
           (live-updates/watch-question! store [:orders/all {}] identity)
           (live-updates/watch-question! store [:shops/all {}] identity)
           (after-ms 10
                     (fn []
                       (live-updates/hear-changed-table! store "orders")
                       (live-updates/hear-changed-table! store "orders")
                       (after-ms 40
                                 (fn []
                                   (is (= {:orders/all 2 :shops/all 1} @asked)
                                       "as the sse line's change: gathered, and only what read the table")
                                   (done))))))))

(deftest a-question-being-asked
  (async done
         (let [held     (atom [])
               answer!  (fn [] (let [[[message resolve]] @held]
                                 (swap! held subvec 1)
                                 (let [[message-name] (first message)]
                                   (resolve {message-name 1 :groundwork-steps/notes {message-name {:tables ["orders"]}}}))))
               store    (live-updates/make-question-store
                         (fn [message] (js/Promise. (fn [resolve _] (swap! held conj [message resolve]))))
                         settings)
               orders   [:orders/all {}]]
           (is (false? (live-updates/is-question-being-asked? store orders)) "never asked")
           (live-updates/watch-question! store orders identity)
           (is (true? (live-updates/is-question-being-asked? store orders)) "out")
           (answer!)
           (after-ms 5
                     (fn []
                       (is (false? (live-updates/is-question-being-asked? store orders)) "answered")
                       (live-updates/hear-changed-table! store "shops")
                       (is (false? (live-updates/is-question-being-asked? store orders)) "a change to a table it didn't read")
                       (live-updates/hear-changed-table! store "orders")
                       (is (true? (live-updates/is-question-being-asked? store orders)) "a change gathered that will ask it")
                       (after-ms 40
                                 (fn []
                                   (is (= 1 (count @held)) "the gathering asked it")
                                   (is (true? (live-updates/is-question-being-asked? store orders)) "out again")
                                   (answer!)
                                   (after-ms 5
                                             (fn []
                                               (is (false? (live-updates/is-question-being-asked? store orders)))
                                               (done))))))))))
