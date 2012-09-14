(ns com.twinql.clojure.async-client
  (:import (java.util.concurrent
            CountDownLatch
            TimeUnit))
  (:import (java.io IOException))
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
            BasicHttpParams
            SyncBasicHttpParams))
  (:import (org.apache.http.conn.params
            ConnManagerPNames
            ConnPerRouteBean))
  (:import (org.apache.http.conn.routing
            HttpRoute))
  (:import (org.apache.http.impl.nio.client
            DefaultHttpAsyncClient))
  (:import (org.apache.http.nio.client
            HttpAsyncClient))
  (:import (org.apache.http.nio.concurrent
            FutureCallback))
  (:import (org.apache.http.nio.conn.ssl
            SSLLayeringStrategy))
  (:import (org.apache.http.conn.ssl
            X509HostnameVerifier
            AllowAllHostnameVerifier
            StrictHostnameVerifier))
  (:import (org.apache.http.impl.nio.reactor
            DefaultConnectingIOReactor))
  (:import (org.apache.commons.codec.binary
            Base64))
  (:require [clojure.string :as string])
  (:require [com.twinql.clojure.http :as http])

  (:require [com.twinql.clojure.sync-libs :as sync])
  (:require [com.twinql.clojure.async-libs :as async]))


(def #^AllowAllHostnameVerifier allow-all-hostname-verifier
     (AllowAllHostnameVerifier.))


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
             (try
               ((:on-complete this) response)
               (finally
                (if latch
                  (. latch countDown)))))
  (cancelled [this]
             (try
               (:on-cancel this)
               (finally
                (if latch
                  (. latch countDown)))))
  (failed    [this ex]
             (try
               ((:on-fail this) ex)
               (finally
                (if latch
                  (. latch countDown))))))

(defrecord InternalExceptionHandler
  [fn]
  org.apache.http.nio.reactor.IOReactorExceptionHandler
  (#^boolean handle [this #^IOException ex] (fn ex))
  (#^boolean handle [this #^RuntimeException ex] (fn ex)))

(defrecord KeepAliveStrategy
  [#^long milliseconds]
  org.apache.http.conn.ConnectionKeepAliveStrategy
  (getKeepAliveDuration [this response context] milliseconds))

(defn #^CountDownLatch countdown-latch
  "Returns a CountdownLatch for managing async IO. Param num-requests must
   specify the number of http requests that the callback will be handling."
  [num-requests]
  (CountDownLatch. num-requests))

(defn #^DefaultConnectingIOReactor io-reactor
  "Returns a new instance of
   org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor"
  [#^Integer worker-count
   #^IOReactorExceptionHandler internal-exception-handler
   #^org.apache.http.params.HttpParams params]
  (let [reactor (DefaultConnectingIOReactor. worker-count params)]
    (if (not (nil? internal-exception-handler))
      (. reactor setExceptionHandler
         (InternalExceptionHandler. internal-exception-handler)))
    reactor))


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
        (. conn-bean setMaxForRoute (HttpRoute. (HttpHost. host))
           (get conns-per-host host)))
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


(defn #^SyncBasicHttpParams create-http-params
  "Returns an HttpParams object with the specified settings. To make your life
   easier, use com.twinql.clojure.http/map->params to construct a map with
   friendly param names. For example:

   (create-http-params (http/map->params {:connection-timeout 2000
                                          :so-timeout 2000
                                          :tcp-nodelay true }))"
  [options]
  (let [http-params (SyncBasicHttpParams.)]

    ;; We MUST call setDefaultHttpParams before setting our own params,
    ;; or else the entire client blows up. It would be nice if Apache
    ;; would document this fact. To see what these default settings are,
    ;; go to http://grepcode.com/file/repo1.maven.org/maven2/org.apache.httpcomponents/httpasyncclient/4.0-alpha1/org/apache/http/impl/nio/client/DefaultHttpAsyncClient.java#DefaultHttpAsyncClient.setDefaultHttpParams%28org.apache.http.params.HttpParams%29
    (DefaultHttpAsyncClient/setDefaultHttpParams http-params)
    (doseq [[name value] (map identity (http/map->params options))]
      (if (not= name :keep-alive)
        (. http-params setParameter name value)))
    http-params))


(defn #^PoolingClientConnectionManager pooling-conn-manager
  "Returns a PoolingClientConnectionManager"
  [#^org.apache.http.nio.reactor.ConnectingIOReactor ioreactor
   #^org.apache.http.nio.conn.scheme.SchemeRegistry registry
   #^long time-to-live]
  (PoolingClientConnectionManager. ioreactor
                                   registry
                                   time-to-live
                                   TimeUnit/MILLISECONDS))

(def *default-opts*
     {:worker-threads 1
      :hostname-verifier allow-all-hostname-verifier
      :time-to-live 4000
      :max-total-connections 20
      :http-params {}})



(defn #^PoolingClientConnectionManager connection-manager
  "Returns a PoolingClientConnectionManager with the specified options.
   Param options is a hash-map that may include the following. Any unset
   vars will default to the value in *default-opts*.

   :worker-threads         The number of threads the connection manager may use.

   :hostname-verifier      The hostname verifier to use for SSL connections.

   :time-to-live           Connection time-to-live, in milliseconds.

   :http-params            A map of options for the http clients in the pool.
                           These typically include timeout settings, proxy
                           settings, and other fine-grained settings. See
                           the available options in the rename-to var of
                           http.clj for available settings. See
                           test/async-client.clj for an example of how to set
                           up this hash. You can create these client params
                           like this:

                           {
                               :so-timeout 2000           ;; milliseconds
                               :connection-timeout 1000   ;; milliseconds
                           }

   :internal-exception-handler
                          A function for handling exceptions within the
                          IOReactor. The function should take one parameter,
                          which is a Java Exception object. It should return
                          true if it's OK for the reactor to continue
                          processing requests after the exception, or false
                          to shut down the reactor. If you don't supply this,
                          the reactor will shut down on all IO and runtime
                          exceptions. An example exception handler that logs
                          an exception and continues looks like this:

                          (defn log-ex [ex] (prn (. ex getMessage) true))


   :scheme-registry        An instance of
                           org.apache.http.nio.conn.scheme.SchemeRegistry
                           describing how to handle http and https protocols.
                           You really only need to set this if you are
                           connecting on non-standard http/https ports, or if
                           you are using client SSL certificates. If you leave
                           this nil, the client will use a default scheme
                           registry that knows how to communicate via http
                           on port 80 and https on port 443.

   :max-total-connections  The maximum total number of concurrent connections
                           to all hosts.

   :max-conns-per-route    Param max-conns-per-route is a hash-map specifying
                           the maximum number of connections to a specific
                           host. It should be a map like the one below,
                           which specifies a maximum of 12 simulataneous
                           connections to secure.mysite.com and a maximum of
                           8 simultaneous connections to public.theirhost.com:

                           {\"secure.mysite.com\" 12 \"public.theirhost.com\" 8}

                           Param conns-per-route may be nil, in which case,
                           the underlying Apache library defaults to 2
                           connections per route.

   To make things easy, you can merge your own hash with *default-opts*
   or *default-opts*.

   Typically, you want to create a single connection manager with a reasonably
   large pool of connections, then use that manager for all of the http clients
   you create."
  [options]
  (let [opts (merge *default-opts* (or options {}))
        registry (or (:scheme-registry options)
                     (async/default-scheme-registry))
        http-params (create-http-params (:http-params opts))
        http-params (set-conn-mgr-params! http-params
                                          (:max-total-connections opts)
                                          (:max-conns-per-route opts))
        reactor (io-reactor (:worker-threads opts)
                            (:internal-exception-handler opts)
                            http-params)]
    (pooling-conn-manager reactor registry (:time-to-live opts))))


(defn http-client
  "Returns an instance of DefaultHttpAsyncClient that uses the specified
   connection manager. Use the connection-manager function to create one
   instance of a connection manager. Use that one instance for all http
   clients.

   Param http params should be a map like {:so-timeout 2000 :timeout 1500}

   The available options are listed in http.clj. One additional option
   exists:

   :keep-alive  Time in milliseconds to keep http connections alive."
  [conn-manager http-params]
  (let [client (DefaultHttpAsyncClient. conn-manager
                 (create-http-params http-params))]
    (if (:keep-alive http-params)
      (. client setKeepAliveStrategy (KeepAliveStrategy.
                                      (:keep-alive http-params))))
    client))


(defn execute-batch!
  "Executes a batch of HTTP requests, calling the specified callback at the
   end of each request.

   Param client is an HTTP client. Param request-seq is a seq of http request
   objects. These include HttpDelete, HttpGet, HttpHead, HttpOptions, HttpPost
   and HttpPut.

   Param callback is an instance of HttpCallback. See the documentation for
   for async-client/HttpCallback."
  [conn-mgr http-params requests on-success on-cancel on-fail]
  (let [client (http-client conn-mgr http-params)
        latch (countdown-latch (count requests))
        callback (HttpCallback. on-success on-cancel on-fail latch)]
    (. client start)
    (try
      (doseq [request requests]
        (try
          (. client execute request callback)
          (catch Exception ex
            (println (str "Caught exception " (. ex getMessage)))
            (.printStackTrace ex))))
      (. latch await)
      (finally (. client shutdown)))))



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
  (slurp (.. response getEntity getContent)))

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

(def UTF8 (java.nio.charset.Charset/forName "UTF-8"))

(defn- encode-str [^String s]
  (String. (Base64/encodeBase64 (.getBytes s "UTF-8") false) UTF8))

(defn get-basic-auth-value
  "Returns the value of the basic auth header."
  [user pwd]
  (str "Basic "
       (string/trim-newline (encode-str (str user ":" pwd)))))


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

(defn set-query-params-in-body!
  "Puts url-encoded query params in the body of an HTTP post and sets the
   url-form-encoded header."
  [request query-string body]
  (. request setEntity (StringEntity. query-string))
  (add-request-headers! request
                        {"Content-Type" "application/x-www-form-urlencoded"}))


(defn set-body!
  "For a POST, if there's a body, set the body. Otherwise, if there are query
   params and no body, put the query params in the body, and make sure we send
   the url-form-encoded header."
  [request query-string body]
  (cond
   body (. request setEntity (StringEntity. body)) ;; URL encode this?
   query-string (set-query-params-in-body! request query-string body )))


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
    (if (= method :post)
     (set-body! request query-string body))



    ;; Add all the request headers specified by the caller.
    (add-request-headers! request headers)

    ;; Add basic auth header, if necessary
    (if (and basic-auth-name basic-auth-pwd)
      (add-basic-auth-header! request basic-auth-name basic-auth-pwd))

    request))

(defn run!
  "Executes a batch of HTTP requests, calling the specified callback at the
   end of each request.

   Param client is an HTTP client. Param request-seq is a seq of http request
   objects. These include HttpDelete, HttpGet, HttpHead, HttpOptions, HttpPost
   and HttpPut.

   Param callback is an instance of HttpCallback. See the documentation for
   for async-client/HttpCallback."
  [requests & {:keys [client on-success on-cancel on-fail]
               :or {on-success (fn [& _])
                    on-cancel (fn [& _])
                    on-fail (fn [& _])}
               :as opts}]
  (try
    (doseq [request requests]
      (let [cb (HttpCallback. (or (:on-success request) on-success)
                              (or (:on-cancel request) on-cancel)
                              (or (:on-fail request) on-fail)
                              nil)
            r (build-request request)]
        (. client execute r cb)))))




(comment

  ;; -------------------------------------------------------------------
  ;; SAMPLE CODE
  ;; -------------------------------------------------------------------

  (defn on-success [response]
    (println "generic success callback"))


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
                        "oq=" nil}
         :on-success (fn [resp] (println "got google cb"))}])

  (defn run-gets
    "This is a test method."
    []
    (let [conn-mgr (connection-manager *default-opts*)
          http-params {:so-timeout 2000
                       :connection-timeout 2000
                       :cookie-policy org.apache.http.client.params.CookiePolicy/IGNORE_COOKIES
                       :user-agent "Clojure-Apache HTTPS"
                       :use-expect-continue false
                       :tcp-nodelay true
                       :stale-connection-check false}
          client (http-client conn-mgr http-params)]
      (.start client)
      (try
        (run! requests
              :client client
              :on-success on-success
              :on-fail on-fail
              :on-cancel on-cancel)
        (run! requests
              :client client
              :on-success on-success
              :on-fail on-fail
              :on-cancel on-cancel)
        (finally (.shutdown client)))))

  ) ;; End of commented sample code
