(ns doorbell.store-test
  "The browser's question store, on node, asking through a stand-in
   send-message! that answers at once with the tables each question read.
   Requiring doorbell.api proves the browser's side compiles."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [doorbell.api :as doorbell]))

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
                           (assoc :bridge/notes {message-name {:tables (get tables-by-name message-name)}})))))}))

(defn- after-ms [ms f] (js/setTimeout f ms))

(deftest a-change-asks-again-what-read-its-table
  (async done
         (let [{:keys [asked send-message!]} (make-stand-in-server {:orders/all ["orders"] :shops/all ["shops"]
                                                                    :totals/all "any"})
               store    (doorbell/make-question-store send-message! settings)
               heard    (atom [])
               handlers (doorbell/make-sse-handlers store)]
           (doorbell/watch-question! store [:orders/all {}] #(swap! heard conj %))
           (doorbell/watch-question! store [:shops/all {}] identity)
           (doorbell/watch-question! store [:totals/all {}] identity)
           (doorbell/watch-question! store [:untold/all {}] identity)
           (after-ms 10
                     (fn []
                       (is (= {:orders/all 1 :shops/all 1 :totals/all 1 :untold/all 1} @asked) "watching asks")
                       (is (= [1] @heard))
                       ((:doorbell/changed handlers) {:table "orders"})
                       ((:doorbell/changed handlers) {:table "orders"})
                       (after-ms 5
                                 (fn []
                                   (is (= 1 (:orders/all @asked)) "gathered: not asked yet")
                                   (after-ms 40
                                             (fn []
                                               (is (= {:orders/all 2 :shops/all 1 :totals/all 2 :untold/all 2} @asked)
                                                   "orders once for two changes; \"any\" and no notes on every change; shops not")
                                               (is (= [1 2] @heard))
                                               ((:doorbell/missed handlers) {})
                                               (after-ms 10
                                                         (fn []
                                                           (is (= {:orders/all 3 :shops/all 2 :totals/all 3 :untold/all 3} @asked)
                                                               "missed: every watched question")
                                                           (done))))))))))))

(deftest an-unwatched-answer-is-kept-then-dropped
  (async done
         (let [{:keys [send-message!]} (make-stand-in-server {:orders/all ["orders"]})
               store (doorbell/make-question-store send-message! settings)
               stop  (doorbell/watch-question! store [:orders/all {}] identity)]
           (after-ms 10
                     (fn []
                       (stop)
                       (is (= 1 (doorbell/find-kept-answer store [:orders/all {}])) "kept while unwatched")
                       (after-ms 15
                                 (fn []
                                   (is (= 1 (doorbell/find-kept-answer store [:orders/all {}])) "within :keep-unwatched-answers-ms")
                                   (after-ms 50
                                             (fn []
                                               (is (nil? (doorbell/find-kept-answer store [:orders/all {}])) "then dropped")
                                               (done))))))))))

(deftest no-answer-from-the-server
  (async done
         (let [store (doorbell/make-question-store (fn [_] (js/Promise.reject (js/Error. "offline"))) settings)
               heard (atom [])]
           (doorbell/watch-question! store [:orders/all {}] #(swap! heard conj %))
           (after-ms 10
                     (fn []
                       (is (= [{:bridge/refused :bridge/no-answer :bridge/data {:http-status nil}}] @heard)
                           "bridge's own refusal for no response, no request id")
                       (done))))))

(deftest the-store-says-its-settings
  (let [problems (try (doorbell/make-question-store identity {:gather-changes-ms 50})
                      nil
                      (catch :default e (ex-data e)))]
    (is (= :doorbell/bad-settings (:type problems)) "refused, nothing filled in")
    (is (= #{:keep-unwatched-answers-ms} (set (keys (:errors problems)))))))

(deftest the-messages-have-their-shapes
  (testing "the three shapes for each of the server's two messages, as bridge takes them"
    (is (= #{:doorbell/changed :doorbell/missed} (set (keys doorbell/message-shapes-by-name))))
    (is (every? #(= #{:params-shape :answer-shape :refusals} (set (keys %))) (vals doorbell/message-shapes-by-name)))
    (is (every? #(= {} (:refusals %)) (vals doorbell/message-shapes-by-name)) "never refused")))

(deftest a-change-the-browser-learns-of-itself
  (async done
         (let [{:keys [asked send-message!]} (make-stand-in-server {:orders/all ["orders"] :shops/all ["shops"]})
               store (doorbell/make-question-store send-message! settings)]
           (doorbell/watch-question! store [:orders/all {}] identity)
           (doorbell/watch-question! store [:shops/all {}] identity)
           (after-ms 10
                     (fn []
                       (doorbell/hear-changed-table! store "orders")
                       (doorbell/hear-changed-table! store "orders")
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
                                   (resolve {message-name 1 :bridge/notes {message-name {:tables ["orders"]}}}))))
               store    (doorbell/make-question-store
                         (fn [message] (js/Promise. (fn [resolve _] (swap! held conj [message resolve]))))
                         settings)
               orders   [:orders/all {}]]
           (is (false? (doorbell/is-question-being-asked? store orders)) "never asked")
           (doorbell/watch-question! store orders identity)
           (is (true? (doorbell/is-question-being-asked? store orders)) "out")
           (answer!)
           (after-ms 5
                     (fn []
                       (is (false? (doorbell/is-question-being-asked? store orders)) "answered")
                       (doorbell/hear-changed-table! store "shops")
                       (is (false? (doorbell/is-question-being-asked? store orders)) "a change to a table it didn't read")
                       (doorbell/hear-changed-table! store "orders")
                       (is (true? (doorbell/is-question-being-asked? store orders)) "a change gathered that will ask it")
                       (after-ms 40
                                 (fn []
                                   (is (= 1 (count @held)) "the gathering asked it")
                                   (is (true? (doorbell/is-question-being-asked? store orders)) "out again")
                                   (answer!)
                                   (after-ms 5
                                             (fn []
                                               (is (false? (doorbell/is-question-being-asked? store orders)))
                                               (done))))))))))
