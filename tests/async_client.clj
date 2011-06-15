(ns tests
  (:refer-clojure)
  (:require [com.twinql.clojure.async-client :as async])
  (:require [com.twinql.clojure.http :as http])
  (:require [clojure.contrib.io :as io])
  (:use clojure.test)
  (:import
     (java.net URI)))


(deftest test-create-request
  (let [get-google (async/create-request :get "http://www.google.com")
        post-yahoo (async/create-request :post "http://www.yahoo.com")
        put-bing   (async/create-request :put "http://www.bing.com")]
    (are [x y] (= x y)

         (. get-google getMethod)
         "GET"

         (.. get-google getURI toString)
         "http://www.google.com"

         (. post-yahoo getMethod)
         "POST"

         (.. post-yahoo getURI toString)
         "http://www.yahoo.com"

         (. put-bing getMethod)
         "PUT"

         (.. put-bing getURI toString)
         "http://www.bing.com" )))

(deftest test-encode-query-params
  (are [x y] (= x y)

       (async/encode-query-params {"val1" "Mock?" "val2" 399 "val3" "Hello!!"})
       "val1=Mock%3F&val2=399&val3=Hello%21%21"

       (async/encode-query-params {})
       nil

       (async/encode-query-params nil)
       nil ))

(deftest test-get-basic-auth-header
  (are [x y] (= x y)

       (async/get-basic-auth-value "CookieMonster" "Abcd@^&HiJk+=-)(")
       "Basic Q29va2llTW9uc3RlcjpBYmNkQF4mSGlKays9LSko" ))


(deftest test-get-full-url
  (are [x y] (= x y)

       (async/get-full-url :get "http://bit.ly" "this=that" nil)
       "http://bit.ly?this=that"

       (async/get-full-url :get "http://bit.ly:8080" "this=that" nil)
       "http://bit.ly:8080?this=that"

       ;; For post, query params go in body if body is nil
       (async/get-full-url :post "http://bit.ly" "this=that" nil)
       "http://bit.ly"

       ;; For post, query params go on URL if body is specified
       (async/get-full-url :post "http://bit.ly" "this=that" "<xml>Hey</xml>")
       "http://bit.ly?this=that" ))

(def sample-params {"name" "Edgar" "age" 89 "weight" 166 "city" "Boston"})
(def sample-headers {"X-This" "Spiderman" "X-That" "Spongebob"})
(def sample-body "This goes into the body of a post.")
(def request-hashes
     [{:method :get     :url "http://www.hotelicopter.com"
       :query-params    sample-params
       :basic-auth-name "guest"
       :basic-auth-pwd  "seekrit"
       :headers         sample-headers}
      {:method :post    :url "http://www.hotelicopter.com"
       :query-params    sample-params
       :headers         sample-headers }
      {:method :post    :url "http://www.hotelicopter.com"
       :query-params    sample-params
       :body            sample-body}])



(deftest test-build-request
  (let [req1 (async/build-request (first request-hashes))
        req2 (async/build-request (nth request-hashes 1))
        req3 (async/build-request (nth request-hashes 2))]
  (are [x y] (= x y)

       (. req1 getMethod)
       "GET"

       (. req2 getMethod)
       "POST"

       (. req3 getMethod)
       "POST"

       (.. req1 getURI toString)
       "http://www.hotelicopter.com?name=Edgar&age=89&weight=166&city=Boston"

       (.. req2 getURI toString)
       "http://www.hotelicopter.com"

       (.. req3 getURI toString)
       "http://www.hotelicopter.com?name=Edgar&age=89&weight=166&city=Boston"

       ;; This post has no body, but it does have query params,
       ;; so query params go in the body.
       (io/slurp* (.. req2 getEntity getContent))
       "name=Edgar&age=89&weight=166&city=Boston"

       ;; When query params go in the body, Content-Type header
       ;; should be "application/x-www-form-urlencoded"
       (. (. req2 getFirstHeader "Content-Type") getValue)
       "application/x-www-form-urlencoded"

       (. (. req2 getFirstHeader "X-This") getValue)
       "Spiderman"

       (. (. req2 getFirstHeader "X-That") getValue)
       "Spongebob"

       ;; Be sure body was set from :body option.
       (io/slurp* (.. req3 getEntity getContent))
       sample-body )))

;; These are some default options for the HTTP connections we want to set up.
;; see clj-apache-http(s) lib
;; and http://hc.apache.org/httpcomponents-client-ga/httpclient/apidocs/org/apache/http/conn/params/ConnManagerPNames.html
(def default-client-opts
     (async/create-http-params
      (http/map->params
       {
        :so-timeout 1250                  ;; in ms
        :connection-timeout 2112          ;; in ms
        :cookie-policy
        org.apache.http.client.params.CookiePolicy/IGNORE_COOKIES
        ;;:default-proxy (http/http-host
        ;;                :host "proxy.myco.kom:"
        ;;                :port 8080)
        :user-agent "Clojure-Apache HTTPS"
        :use-expect-continue false        ;; incompatible with squid/proxy
        :tcp-nodelay true                 ;; use more bandwidth to lower latency
        :stale-connection-check false}))) ;; saves up to 30ms / req


;; Make sure the call to create-http-params above produced a
;; BasicHttpParams object with the settings we requested.
(deftest test-http-params
  (is (instance? org.apache.http.params.BasicHttpParams default-client-opts))
  (is (= 1250 (. default-client-opts getParameter
                 org.apache.http.params.CoreConnectionPNames/SO_TIMEOUT)))
  (is (= 2112 (. default-client-opts getParameter
                  org.apache.http.params.CoreConnectionPNames/CONNECTION_TIMEOUT)))
  (is (= org.apache.http.client.params.CookiePolicy/IGNORE_COOKIES
         (. default-client-opts getParameter
            org.apache.http.client.params.ClientPNames/COOKIE_POLICY)))
  (is (= "Clojure-Apache HTTPS"
         (. default-client-opts getParameter
            org.apache.http.params.CoreProtocolPNames/USER_AGENT)))
  (is (= false (. default-client-opts getParameter
                  org.apache.http.params.CoreProtocolPNames/USE_EXPECT_CONTINUE)))
  (is (= true (. default-client-opts getParameter
                 org.apache.http.params.CoreConnectionPNames/TCP_NODELAY)))
  (is (= false (. default-client-opts getParameter
                  org.apache.http.params.CoreConnectionPNames/STALE_CONNECTION_CHECK))))




;; Scheme names and port numbers. We want to register these in a
;; SchemeRegistry so our connection manager knows what's what.
;; Key is protocol name, value is port number.
(def connection-schemes [{"http" 80 "alt-http" 8080
                          "https" 443 "alt-https" 8888 }])

;; Define max number of total connections we want in the pool.
;; This should be set to at least the sum of all max-per-route values.
(def max-total-conns 30)

;; Define max number of connections per route. Default is 2.
;; Key is fully-qualified host name. Value is an integer.
;; Note that "server1.mycompany.com" and "server2.mycompany.com"
;; would each need their own entries. If you are connecting to
;; hosts not specified in this map, you'll get a max of 2 connections
;; to that host in your connection pool.
(def max-per-route { "www.google.com" 5 "www.yahoo.com" 6 "www.bing.com" 7 })


;; Ideally, this would test whether the connection pool was set up with
;; all of the settings we specified. Unfortunately, the Apache libs don't
;; provide APIs for getting much info out of the pool.
(deftest test-connection-manager
  (let [conn-manager (async/connection-manager
                      {:worker-threads 2
                       :time-to-live 6000
                       :client-options default-client-opts
                       :max-total-connections max-total-conns
                       :max-per-route max-per-route})]
    (is (instance?
         org.apache.http.impl.nio.conn.PoolingClientConnectionManager
         conn-manager))))


(clojure.test/run-tests)
