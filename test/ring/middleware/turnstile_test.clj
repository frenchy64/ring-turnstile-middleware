(ns ring.middleware.turnstile-test
  (:require [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [ring.middleware.turnstile :as sut]
            [ring.mock.request :refer [header request]]
            [schema.test :refer [validate-schemas]]
            [taoensso.carmine :as car]))

(def redis-conn
  {:spec {:host "localhost"
          :port 6380}
   :pool {}})

(defn reset-limits!
  []
  (car/wcar redis-conn
            (car/flushdb)))

(defn reset-limits-fixture
  [f]
  (reset-limits!)
  (f)
  (reset-limits!))

(use-fixtures :once validate-schemas)

(use-fixtures :each reset-limits-fixture)

(deftest default-rate-limit-handler-test
  (is (= {:status 429
          :headers {"Content-Type" "application/json"
                    "Retry-After" "3"}
          :body "{\"error\": \"Too Many Requests\"}"}
         (sut/default-rate-limit-handler {} 3 {}))))

(defn wrap-with-exception
  [f]
  (fn [request]
    (when-not (= "1234" (get-in request [:headers "x-id"]))
      (f request))))

(def handler
  (fn [req] {:status 200
             :headers {"Content-Type" "application/json"}
             :body "{}"}))

(deftest wrap-rate-limit-test
  (testing "One limit"
    (let [app (-> handler
                  (sut/wrap-rate-limit {:redis-conn redis-conn
                                        :remaining-header-enabled true
                                        :limit-fns [(sut/ip-limit 5)]}))]
      (testing "Rate limit headers"
        (let [response (-> (request :get "/") app)]
          (is (= "4" (get-in response [:headers "X-RateLimit-IP-Remaining"])))
          (is (= "5" (get-in response [:headers "X-RateLimit-IP-Limit"])))))
      (testing "Rate limit status and response"
        (dotimes [_ 5] (-> (request :get "/") app))
        (let [response (-> (request :get "/") app)]
          (is (= 429 (:status response)))
          (is (= "3600" (get-in response [:headers "Retry-After"])))
          (is (= "{\"error\": \"Too Many Requests\"}"
                 (:body response)))))))
  (testing "Remaining header disabled"
    (reset-limits!)
    (let [app (-> handler
                  (sut/wrap-rate-limit {:redis-conn redis-conn
                                        :limit-fns [(sut/ip-limit 5)]}))]
      (testing "Rate limit headers"
        (let [response (-> (request :get "/") app)]
          (is (nil? (get-in response [:headers "X-RateLimit-IP-Remaining"])))
          (is (= "5" (get-in response [:headers "X-RateLimit-IP-Limit"])))))
      (testing "Rate limit status and response"
        (dotimes [_ 5] (-> (request :get "/") app))
        (let [response (-> (request :get "/") app)]
          (is (= 429 (:status response)))
          (is (= "3600" (get-in response [:headers "Retry-After"])))
          (is (= "{\"error\": \"Too Many Requests\"}"
                 (:body response)))))))
  (testing "Custom rate limit handler"
    (let [app (-> handler
                  (sut/wrap-rate-limit {:redis-conn redis-conn
                                        :remaining-header-enabled true
                                        :limit-fns [(sut/ip-limit 5)]
                                        :rate-limit-handler
                                        (fn [request next-slot-in-sec _]
                                          {:status 429
                                           :headers {"Content-Type" "application/text"}
                                           :body "Too many requests, retry later"})}))]
      (let [response (-> (request :get "/") app)]
        (is (= 429 (:status response)))
        (is (= "Too many requests, retry later"
               (:body response))))))
  (testing "Mulitple limiters"
    (reset-limits!)
    (let [ip-limit (wrap-with-exception (sut/ip-limit 8))
          header-limit (wrap-with-exception (sut/header-limit 10 "x-id" "ID"))
          app (-> handler
                  (sut/wrap-rate-limit {:redis-conn redis-conn
                                        :remaining-header-enabled true
                                        :limit-fns [header-limit
                                                 ip-limit]}))]
      (testing "Limits when the `x-id` header is set"
        (let [response (-> (request :get "/") (header "x-id" "1") app)]
          (is (= "9" (get-in response [:headers "X-RateLimit-ID-Remaining"])))
          (is (= "10" (get-in response [:headers "X-RateLimit-ID-Limit"]))))
        (let [response (-> (request :get "/") (header "x-id" "2")app)]
          (is (= "9" (get-in response [:headers "X-RateLimit-ID-Remaining"])))
          (is (= "10" (get-in response [:headers "X-RateLimit-ID-Limit"])))
          (is (= "6" (get-in response [:headers "X-RateLimit-IP-Remaining"])))
          (is (= "8" (get-in response [:headers "X-RateLimit-IP-Limit"])))))
      (testing "Limits when the `x-id` header is not set"
        (let [response (-> (request :get "/") app)]
          (is (= "5" (get-in response [:headers "X-RateLimit-IP-Remaining"])))
          (is (= "8" (get-in response [:headers "X-RateLimit-IP-Limit"]))))
        (let [response (-> (request :get "/") (header "x-id" "1") app)]
          (is (= "8" (get-in response [:headers "X-RateLimit-ID-Remaining"])))
          (is (= "10" (get-in response [:headers "X-RateLimit-ID-Limit"]))))
        (let [response (-> (request :get "/") (header "x-id" "2") app)]
          (is (= "8" (get-in response [:headers "X-RateLimit-ID-Remaining"])))
          (is (= "10" (get-in response [:headers "X-RateLimit-ID-Limit"])))))
      (testing "Unlimited limit for the exception limit"
        (let [response (-> (request :get "/") (header "x-id" "1234") app)]
          (is (not (contains? (:headers response) "X-RateLimit-ID-Remaining")))
          (is (not (contains? (:headers response) "X-RateLimit-IP-Remaining"))))
        (let [response (-> (request :get "/") (header "x-id" "1") app)]
          (is (= "7" (get-in response [:headers "X-RateLimit-ID-Remaining"])))
          (is (= "10" (get-in response [:headers "X-RateLimit-ID-Limit"]))))))))

(deftest wrap-rate-limit-key-prefix-test
  (let [app1 (-> handler
                 (sut/wrap-rate-limit {:redis-conn redis-conn
                                       :remaining-header-enabled true
                                       :key-prefix "api-1"
                                       :limit-fns [(sut/ip-limit 5)]}))
        app2 (-> handler
                 (sut/wrap-rate-limit {:redis-conn redis-conn
                                       :remaining-header-enabled true
                                       :key-prefix "api-2"
                                       :limit-fns [(sut/ip-limit 5)]}))]
    (let [response (-> (request :get "/") app1)]
      (is (= "4" (get-in response [:headers "X-RateLimit-IP-Remaining"]))))
    (let [response (-> (request :get "/") app2)]
      (is (= "4" (get-in response [:headers "X-RateLimit-IP-Remaining"]))))))

(def exception
  (ex-info "Exception in another middleware"
           {:origin :other-middleware}))

(defn ex-mw
  [handler]
  (fn [request]
    (throw exception)))

(deftest error-handling-test
  (testing "Exception from another mw"
    (let [app (-> handler
                  ex-mw
                  (sut/wrap-rate-limit {:redis-conn redis-conn
                                        :remaining-header-enabled true
                                        :key-prefix "api-1"
                                        :limit-fns [(sut/ip-limit 5)]}))]
      (try
        (-> (request :get "/") app)
        (catch Exception e
          (is (= exception e)
              (str "Exceptions from other middlewares should not be flagged as "
                   "coming from the ring-turnstile-middleware"))))))
  (testing "Exception from the ring-turnstile-middleware"
    (let [app (-> handler
                  (sut/wrap-rate-limit {:redis-conn redis-conn
                                        :remaining-header-enabled true
                                        :limit-fns [(fn [request]
                                                      (throw (Exception. "Error")))]}))]
      (try
        (-> (request :get "/") app)
        (catch Exception e
          (let [origin (-> (ex-data e) :origin)]
            (is (= :ring-turnstile-middleware origin))))))))
