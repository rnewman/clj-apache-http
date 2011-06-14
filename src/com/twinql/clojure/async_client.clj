(ns com.twinql.clojure.async-client
  (:import (java.util.concurrent
            CountDownLatch
            TimeUnit))
  (:import (javax.net.ssl
            SSLContext))
  (:import (java.net
            URLEncoder))
  (:import (org.apache.http.impl.nio.conn
            PoolingClientConnectionManager))
  (:import (org.apache.http
            HttpResponse
            HttpHost))
  (:import (org.apache.http.entity
            StringEntity))
  (:import (org.apache.http.message
            BasicHttpResponse))
  (:import (org.apache.http.client.methods
            HttpDelete
            HttpGet
            HttpHead
            HttpOptions
            HttpPost
            HttpPut))
  (:import (org.apache.http.params
            BasicHttpParams))
  (:import (org.apache.http.conn.params
            ConnManagerPNames
            ConnPerRouteBean))
  (:import (org.apache.http.impl.nio.client
            DefaultHttpAsyncClient))
  (:import (org.apache.http.nio.client
            HttpAsyncClient))
  (:import (org.apache.http.nio.concurrent
            FutureCallback))
  (:import (org.apache.http.nio.conn.scheme
            Scheme
            SchemeRegistry))
  (:import (org.apache.http.nio.conn.ssl
            SSLLayeringStrategy))
  (:import (org.apache.http.conn.ssl
            X509HostnameVerifier
            AllowAllHostnameVerifier
            StrictHostnameVerifier))
  (:import (org.apache.http.nio.conn.ssl
            SSLLayeringStrategy))
  (:import (org.apache.http.impl.nio.reactor
            DefaultConnectingIOReactor))
  (:require [clojure.contrib.io :as io])
  (:require [clojure.contrib.base64 :as base64])
  (:require [clojure.contrib.string :as string]))

(def #^AllowAllHostnameVerifier allow-all-hostname-verifier
     (AllowAllHostnameVerifier.))

(def *default-opts*
     {:worker-threads 1
      :hostname-verifier allow-all-hostname-verifier
      :time-to-live 4000
      :max-total-connections 20
      :http-params (BasicHttpParams.)} )

(def *default-http-opts* (merge *default-opts* {:scheme "http" :port 80}))

(def *default-https-opts* (merge *default-opts* {:scheme "https" :port 443}))

;;   "Defines a callback to execute when an async HTTP request completes.
;;    Param on-complete is a function to run when request completes. That
;;    function should take one param, which is an instance of
;;    org.apache.http.HttpResponse. Param on-cancel is a callback to execute
;;    if the request is cancelled. That function takes no params. Param on-fail
;;    is a function to execute if the request fails. That function takes one
;;    param, which is a java.lang.Exception.

;;    Param latch is a CountDownLatch. Use async-client/countdown-latch to
;;    create this param."

(defrecord HttpCallback
  [on-complete on-cancel on-fail latch]
  org.apache.http.nio.concurrent.FutureCallback
  (completed [this response]
             (println "Running completed callback")
             (try
               ((:on-complete this) response)
               (finally
                (. latch countDown))))
  (cancelled [this]
             (println "Running cancelled callback")
             (try
               (:on-cancel this)
               (finally
                (. latch countDown))))
  (failed    [this ex]
             (println "Running failed callback")
             (try
               ((:on-fail this) ex)
               (finally
                (. latch countDown)))))

(defn #^CountDownLatch countdown-latch
  "Returns a CountdownLatch for managing async IO. Param num-requests must
   specify the number of http requests that the callback will be handling."
  [num-requests]
  (CountDownLatch. num-requests))

(defn #^DefaultConnectingIOReactor io-reactor
  "Returns a new instance of
   org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor"
  [#^Integer worker-count #^org.apache.http.params.HttpParams params]
  (DefaultConnectingIOReactor. worker-count params))

(defn #^SSLLayeringStrategy layering-strategy
  "Returns a new LayeringStrategy for managing SSL connections."
  [#^SSLContext ssl-context #^X509HostnameVerifier hostname-verifier]
  (SSLLayeringStrategy. ssl-context hostname-verifier))

(defn #^Scheme scheme
  "Returns a new org.apache.http.nio.conn.scheme.Scheme. Param name should
   be \"http\" or \"https\". Param port is the port to connect to on the
   remote host."
  [#^String name #^int port #^LayeringStrategy strategy]
  (Scheme. name port strategy))

(defn #^ConnPerRouteBean max-conns-per-route
  "Returns a ConnPerRouteBean describing the maximum number of concurrent
   connections allowed to the specified host. Param conns-per-host-map is
   a hash-map in which keys are strings and values are ints. The key for
   each entry is a fully qualified host name as a string. The value is the
   maximum number of concurrent connections to allow to this host. For example:

   {\"secure.mysite.com\" 12 \"public.theirhost.com\" 8}"
  [conns-per-host]
  (let [conn-bean (ConnPerRouteBean.)]
    (doseq [host (keys conns-per-host)]
      (. conn-bean setMaxForRoute (HttpHost. host) (get conns-per-host host)))
    conn-bean))

(defn set-conn-mgr-params!
  "Sets the MAX_TOTAL_CONNECTIONS and MAX_CONNECTIONS_PER_ROUTE options on
   a BasicHttpParams object"
  [http-params max-conns conns-per-route]
  (. http-params setParameter
     ConnManagerPNames/MAX_TOTAL_CONNECTIONS max-conns)
  (if conns-per-route
    (. http-params setParameter
       ConnManagerPNames/MAX_CONNECTIONS_PER_ROUTE (max-conns-per-route
                                                    conns-per-route)))
  http-params)


(defn #^SchemeRegistry scheme-registry
  "Returns a new instance of a non-blocking Apache SchemeRegistry. Param
   schemes is a seq of schemes to register."
  [schemes]
  (let [registry (SchemeRegistry.)]
    (doseq [scheme schemes]
      (. registry register scheme))
    registry))

(defn #^PoolingClientConnectionManager pooling-conn-manager
  "Returns a PoolingClientConnectionManager"
  [#^org.apache.http.nio.reactor.ConnectingIOReactor ioreactor
   #^org.apache.http.nio.conn.scheme.SchemeRegistry registry
   #^long time-to-live]
  (PoolingClientConnectionManager. ioreactor
                                   registry
                                   time-to-live
                                   TimeUnit/MILLISECONDS))

(defn connection-manager
  "Returns a PoolingClientConnectionManager with the specified options.
   Param options is a hash-map that may include the following:

   :worker-threads         The number of threads the connection manager may use.

   :hostname-verifier      The hostname verifier to use for SSL connections.

   :time-to-live           Connection time-to-live, in milliseconds.

   :max-total-connections  The maximum total number of concurrent connections
                           to all hosts.

   :scheme                 Either \"http\" or \"https\"

   :port                   The port on which to connect. Typically 80 for http
                           and 443 for https.

   To make things easy, you can merge your own hash with *default-http-opts*
   or *default-https-opts*.

   Param conns-per-route is a hash-map specifying the maximum number of
   connections to a specific host. It should be a map like the one below,
   which specifies a maximum of 12 simulataneous connections to secure.mysite.com
   and a maximum of 8 simultaneous connections to public.theirhost.com:

   {\"secure.mysite.com\" 12 \"public.theirhost.com\" 8}

   Param conns-per-route may be nil, in which case, we'll default to 2
   connections per route.

   Typically, you want to create a single connection manager with a reasonably
   large pool of connections, then use that manager for all of the http clients
   you create."
  [options conns-per-route]
  (let [opts (merge *default-http-opts* (or options {}))
        ;; TODO: Fix schemes! We will need an SSL manager!!!
        scheme (scheme (:scheme opts) (:port opts) nil)
        registry (scheme-registry [scheme])
        http-params (set-conn-mgr-params! (:http-params opts)
                                          (:max-total-connections opts)
                                          conns-per-route)
        reactor (io-reactor (:worker-threads opts) http-params)]
    (pooling-conn-manager reactor registry (:time-to-live opts))))


(defn http-client
  "Returns an instance of DefaultHttpAsyncClient that uses the specified
   connection manager. Use the connection-manager function to create one
   instance of a connection manager. Use that one instance for all http
   clients."
  [conn-manager]
  (DefaultHttpAsyncClient. conn-manager))

(defn execute-batch!
  "Executes a batch of HTTP requests, calling the specified callback at the
   end of each request.

   Param client is an HTTP client. Param request-seq is a seq of http request
   objects. These include HttpDelete, HttpGet, HttpHead, HttpOptions, HttpPost
   and HttpPut.

   Param callback is an instance of HttpCallback. See the documentation for
   for async-client/HttpCallback."
  [conn-mgr requests on-success on-cancel on-fail]
  (let [client (http-client conn-mgr)
        latch (countdown-latch (count requests))
        callback (HttpCallback. on-success on-cancel on-fail latch)]
    (. client start)
    (try
      (doseq [request requests]
        (try
          (prn (str "Requesting " request))
          (. client execute request callback)
          (catch Exception ex
            (println (str "Caught exception " (. ex getMessage)))
            (.printStackTrace ex))))
      (. latch await)
      (finally (. client shutdown)))))
      ;;(. client shutdown)



(defn response-status [response]
  "Returns the 3-digit status code from the response."
  (.. response getStatusLine getStatusCode))

(defn response-headers [response]
  "Returns the response headers as a hash-map."
  (let [headers (. response getAllHeaders)]
    (zipmap (map #(. % getName) headers)
            (map #(. % getValue) headers))))

(defn response-body [response]
  "Returns the body of the response."
  (io/slurp* (.. response getEntity getContent)))

(defn create-request
  "Returns a new instance of a request of the specified method. Param method
   should be one of :get, :post, :put, :head, :options, :delete. Param url
   should be a string describing the URL that will receive the request."
  [method url]
  (cond (= method :get) (HttpGet. url)
        (= method :post) (HttpPost. url)
        (= method :put) (HttpPut. url)
        (= method :head) (HttpHead. url)
        (= method :options) (HttpOptions. url)
        (= method :delete) (HttpDelete. url)
        :else nil))

;;(create-request :get "http://www.google.com")
;;(create-request :post "http://www.yahoo.com")
;;(create-request :put "http://www.bing.com")

(defn add-request-headers!
  "Adds headers to a request. Headers should be a map."
  [request headers]
  (when headers
    (doseq [[name value] headers]
      (.addHeader request name (str value)))))

(defn url-encode
  "Returns a UTF8 url-encoded version of param string."
  [string]
  (URLEncoder/encode string "UTF-8"))


(defn encode-query-params
  "Returns query-params as a URL-encoded string. Param query-params should
   be a map."
  [query-params]
  (when (and query-params (not-empty query-params))
    (->> (for [[key value] query-params]
           (str (url-encode (str key)) "=" (url-encode (str value))))
         (interpose "&")
         (apply str))))

;;(encode-query-params {"val1" "Chickety" "val2" 399 "val3" "Hello!!"})
;;(encode-query-params {})


(defn get-basic-auth-value
  "Returns the value of the basic auth header."
  [user pwd]
  (str "Basic "
       (string/chomp (base64/encode-str (str user ":" pwd)))))

;;(get-basic-auth-value "CookieMonster" "Abcd@^&HiJk+=-)(")

(defn add-basic-auth-header!
  "Adds a basic authentication header to the HTTP request."
  [request user pwd]
  (. request addHeader "Authorization" (get-basic-auth-value user pwd)))


(defn get-full-url
  "Returns the full URL. If method is :get and there is a query string,
   or if method is :post and there is a query string and a body. Otherwise,
   returns the url unchanged."
  [method url query-string body]
  (if query-string
    (if (= method :get)
      (str url "?" query-string)
      (if (and (= method :post) body)
        (str url "?" query-string)
        url))
    url))

(defn set-body!
  "For a POST, if there's a body, set the body. Otherwise, if there are query
   params and no body, put the query params in the body, and make sure we send
   the url-form-encoded header."
  [request method query-string body]
  (if (= method :post)
    (if body
      (. request setEntity (StringEntity. body))
      (if query-string
        ((add-request-headers!
          request
          {"Content-Type" "application/x-www-form-urlencoded"})
         (. request setEntity (StringEntity. query-string)))))))


(defn build-request
  "Returns an HttpReqest object. You can make a sequence of requests and
   pass them into execute-batch!

   Param options is a map with the following keys. :method and :url are
   required. The rest are optional:

   :method             The HTTP request method. This is a symbol, and should be
                       one of :get, :post, :put, :head, :options, :delete.

   :url                A string: the url you want to get or post to.

   :headers            A map of request headers.

   :basic-auth-name    The user/account name to use for a server that requires
                       basic authentication.

   :basic-auth-pwd     The password to use for a server that requires basic
                       authentication.

   :body               A string. This will become the body of the PUT OR POST.

   :query-params       A map of query parameters. These will be URL-encoded
                       and added to the query string for a GET request or
                       to the body of a PUT or POST."
  [{:keys [method url headers basic-auth-name basic-auth-pwd body query-params]}]
  (let [query-string (encode-query-params query-params)
        full-url (get-full-url method url query-string body)
        request (create-request method full-url)]

    ;; Set the body if this is a post
    (set-body! request method query-string body)

    ;; Add all the request headers specified by the caller.
    (add-request-headers! request headers)

    ;; Add basic auth header, if necessary
    (if (and basic-auth-name basic-auth-pwd)
      (add-basic-auth-header! request basic-auth-name basic-auth-pwd))

    request))




;;(comment
  ;; Sample usage

  (defn on-success [response]
    ;;(io/spit "___output___.html"
    ;;         (io/slurp* (.. response getEntity getContent)))
    ;;(println (.. response getRequestLine))
    (println "STATUS")
    (println (response-status response))
    (println "HEADERS")
    (println (response-headers response))
    (println "BODY")
    (println (response-body response))
    "OK")
  (defn on-cancel [] (println "Request cancelled"))
  (defn on-fail [ex] (println (str "Request Error: " (.getMessage ex))))

  (def requests
       [{:method :get   :url "http://www.google.com"}
        {:method :post  :url "http://www.hotelicopter.com"}
        {:method :get   :url "http://www.bing.com"}
        {:method :get   :url "http://www.jsonlint.com"}
        {:method :get   :url "http://www.google.com/search"
         :query-params {"source" "ig"
                        "hl" "en"
                        "rlz" ""
                        "q" "clojure decompose options"
                        "aq" "f"
                        "aqi" ""
                        "aql" ""
                        "oq=" nil}}])

  (defn run-gets
    ""
    []
    (let [conn-mgr (connection-manager {} nil)]
      (execute-batch! conn-mgr
                      (map build-request requests)
                      on-success
                      on-cancel
                      on-fail)))

;; (run-gets)

;; (map #(prn %) requests)

;;  )

