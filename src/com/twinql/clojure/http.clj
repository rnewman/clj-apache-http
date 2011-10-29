(ns com.twinql.clojure.http
  (:refer-clojure :exclude [get])
  (:use clojure.set)
  (:require
     [clojure.contrib.io :as io]
     [clojure.contrib.json :as json])
  (:import 
    (java.lang Exception)
    (java.net URI)
    (java.io InputStream)
    (org.apache.http
      HttpHost        ; For proxy.
      HttpRequest
      HttpRequestInterceptor
      HttpResponse
      HttpEntity
      Header
      HeaderIterator
      StatusLine)
    (org.apache.http.auth
      AuthScope
      AuthState
      Credentials
      UsernamePasswordCredentials)
    (org.apache.http.client.entity
      UrlEncodedFormEntity)
    (org.apache.http.client.methods
      HttpOptions
      HttpUriRequest
      HttpGet HttpPost HttpPut HttpDelete HttpHead)
    (org.apache.http.protocol
      BasicHttpContext
      HttpContext)
    (org.apache.http.client
      CookieStore
      CredentialsProvider
      HttpClient
      ResponseHandler)
    (org.apache.http.client.protocol
      ClientContext)
    (org.apache.http.client.utils
      URIUtils
      URLEncodedUtils)
    (org.apache.http.message
      AbstractHttpMessage
      BasicNameValuePair)
    (org.apache.http.impl.auth
      BasicScheme)
    (org.apache.http.conn.ssl
      SSLSocketFactory)
    (org.apache.http.conn.params
      ConnPerRoute
      ConnManagerPNames)
    (org.apache.http.conn.scheme
      PlainSocketFactory
      SchemeRegistry
      Scheme)
    (java.security
      KeyStore)
    (org.apache.http.params
      BasicHttpParams
      HttpParams)
    (org.apache.http.conn
      ClientConnectionManager)
    (org.apache.http.impl.conn
      SingleClientConnManager)
    (org.apache.http.impl.conn.tsccm
      ThreadSafeClientConnManager)
    (org.apache.http.impl.client
      DefaultHttpClient
      BasicResponseHandler)))

;(set! *warn-on-reflection* true)

(defn- #^String as-str
  "Because contrib's isn't typed correctly."
  [x]
  (if (instance? clojure.lang.Named x)
    (name x)
    (str x)))
    
(defn- map->name-value-pairs
  "Take an associative structure and return a sequence of BasicNameValuePairs.
  Any associated value that is sequential will appear multiple times in the output, so that
  
    {:foo [\"bar\" \"baz\"]}
  
  will produce pairs that encode to
  
    ?foo=bar&foo=baz"
  [q]
  (mapcat
    (fn [[param value]]
      (if (and (sequential? value)
               (not (empty? value)))
        (map (partial (fn [#^String p v]
                        (new BasicNameValuePair p (as-str v)))
                      (as-str param))
             value)
        [(new BasicNameValuePair (as-str param) (as-str value))]))
       q))

(defn encode-query [q]
  "Return an encoded query string.
  q is a map or list of pairs."
  (when (and q (not (empty? q)))
    (. URLEncodedUtils format
       (map->name-value-pairs q)
       "UTF-8")))

(defn- combine-query
  "Combine two query strings."
  [q1 q2]
  (if q1
    (if q2
      (str q1 "&" q2)
      q1)
    q2))

(defmacro ensure-int
  "If x is an integer or string, return it as an integer."
  [x]
  `(if (integer? ~x)
     ~x
     (if (string? ~x)
       (Integer/parseInt ~x))))

(defmulti ensure-uri class)

(defmethod ensure-uri String [#^String x] (new URI x))
(defmethod ensure-uri URI [#^URI x] x)

(defn header-pair
  "Return a vector of [name, value]."
  [#^Header h]
  [(.getName h)
   (.getValue h)])

(defn header-element-pair
  "Return a vector of [name, array of HeaderElement]."
  [#^Header h]
  [(.getName h)
   (.getElements h)])

(def header-seq (partial map header-pair))
(def header-element-seq (partial map header-element-pair))

(defn- collect-map [f seq]
  (apply
    merge-with conj
    (map f seq)))

(defn header-map
  "Return a map from header names to vectors of values."
  [headers]
  (collect-map (fn [#^Header h] {(.getName h) [(.getValue h)]}) headers))

(defn header-element-map
  "Return a map from header names to vectors of array of HeaderElement"
  [headers]
  (collect-map (fn [#^Header h] {(.getName h) [(.getElements h)]}) headers))

;;; Header processors.
(defmulti headers-as (fn [headers as] as))

(defmethod headers-as :identity [#^HeaderIterator headers as]
  headers)

(defmethod headers-as :header-seq [#^HeaderIterator headers as]
  (iterator-seq headers))
  
(defmethod headers-as :seq [#^HeaderIterator headers as]
  (map header-pair (iterator-seq headers)))
  
(defmethod headers-as nil [#^HeaderIterator headers as]
  (headers-as headers :seq))

(defmethod headers-as :element-seq [#^HeaderIterator headers as]
  (map header-element-pair (iterator-seq headers)))

(defmethod headers-as :map [#^HeaderIterator headers as]
  (header-map (iterator-seq headers)))

(defmethod headers-as :element-map [#^HeaderIterator headers as]
  (header-element-map (iterator-seq headers)))

;;; Entity processors.
;;; None of these care about the third argument, which is the status
;;; code.
(defmulti entity-as (fn [entity as status] as))

(defmethod entity-as :identity
  [entity as status] entity)

(defmethod entity-as nil
  [entity as status] entity)

;; Client is responsible for cleanup.
(defmethod entity-as :stream [#^HttpEntity entity as status]
  (.getContent entity))

;; Client is responsible for cleanup.
(defmethod entity-as :reader [#^HttpEntity entity as status]
  (io/reader (.getContent entity)))

(defmethod entity-as :string [#^HttpEntity entity as status]
  (with-open [#^InputStream stream (.getContent entity)]
    (io/slurp* stream)))

;;; JSON handling.
;;; We prefer keywordizing.
(defmethod entity-as :json [#^HttpEntity entity as status]
  (with-open [#^InputStream stream (.getContent entity)]
    (clojure.contrib.json/read-json (io/reader stream) true)))

(defmethod entity-as :json-string-keys [#^HttpEntity entity as status]
  (with-open [#^InputStream stream (.getContent entity)]
    (clojure.contrib.json/read-json (io/reader stream) false)))


;;; To avoid overhead in shutting down a ClientConnectionManager,
;;; support a thread pool.

(defn #^SchemeRegistry scheme-registry [ssl?]
  (let [#^SchemeRegistry scheme-registry (SchemeRegistry.)]
    (when ssl?
      (.register scheme-registry
                 (Scheme. "https"
                          (SSLSocketFactory.
                            (KeyStore/getInstance
                              (KeyStore/getDefaultType)))
                          443)))

    (.register scheme-registry
               (Scheme. "http"
                        (PlainSocketFactory.)
                        80))
    scheme-registry))

(defn #^ClientConnectionManager single-client-connection-manager
  "Produce a new SingleClientConnManager with http and https, or
   the provided registry."
  ([]
   (single-client-connection-manager (scheme-registry true)))
  ([#^SchemeRegistry registry]
   ;; The HTTP params go away in 4.1.
   (SingleClientConnManager. (BasicHttpParams.) registry)))

(defn #^BasicHttpParams connection-limits 
  "Return HTTP Parameters for the provided connection limit."
  [#^Integer max-total-connections]
  (doto (BasicHttpParams.)
    (.setParameter ConnManagerPNames/MAX_TOTAL_CONNECTIONS max-total-connections)))
   
(defn #^ClientConnectionManager thread-safe-connection-manager
  "Produce a new ThreadSafeClientConnManager with http and https, or
   the provided registry."
  ([]
   (thread-safe-connection-manager (scheme-registry true)))
  
  ([#^SchemeRegistry registry]
   ;; The HTTP params go away in 4.1.
   ;; BasicHttpParams isn't thread-safe...!
   (thread-safe-connection-manager registry (BasicHttpParams.)))
  
  ([#^SchemeRegistry registry #^HttpParams params]
   (ThreadSafeClientConnManager. params registry)))

(defn shutdown-connection-manager
  [#^ClientConnectionManager ccm]
  (.shutdown ccm))

(defmacro with-connection-manager [[v kind] & body]
  (let [valid #{:thread-safe :single-client}]
    (when-not (contains? valid kind)
      (throw (IllegalArgumentException.
               (str "Valid connection manager kinds: " valid))))
    `(let [~v (~({:thread-safe `thread-safe-connection-manager
                  :single-client `single-client-connection-manager}
                 kind))]
       (try
         (do ~@body)
         (finally
           (try
             (shutdown-connection-manager ~v)
             (catch Exception e#)))))))

(defn preemptive-basic-auth-filter
  "Returns a function suitable for passing to HTTP
  requests. Implements preemptive Basic auth."
  [#^String user-pass]
  (let [#^Credentials c (UsernamePasswordCredentials. user-pass)]

    ;; 4.1 version. Untested, of course.
    #_
    (fn [#^HttpClient  client
         #^HttpRequest request
         #^HttpContext context]
      (let [#^URI u (.getURI request)
            #^String target-host (.getHost u)
            target-port (or (.getPort u)
                            (if (= "https" (.getScheme u))
                              443
                              80))])
      (.setCredentials (.getCredentialsProvider client)
                       (AuthScope. target-host target-port)
                       c)
      (.setAttribute context ClientContext/AUTH_CACHE
                     (doto (BasicAuthCache.)
                       (.put target-host (BasicScheme.)))))

    ;; 4.0.1 version.
    (fn [#^DefaultHttpClient  client
         #^HttpRequest request
         #^HttpContext context]

      ;; Introduce a "filter" abstraction to apply operations to the
      ;; participants in an HTTP request.
      ;; Implemented this way because auth (and presumably other
      ;; things) changes between 4.0.1 and 4.1, and 4.1 is only in
      ;; alpha right now. *sigh*
      ;; In 4.1, set up the AuthCache. In 4.0.1, make a
      ;; RequestInterceptor.
      (let [#^HttpRequestInterceptor prx
            (proxy [HttpRequestInterceptor] []
              (process
               [#^HttpUriRequest request
                #^HttpContext context]

               (let [#^AuthState auth-state (.getAttribute context ClientContext/TARGET_AUTH_STATE)
                     #^CredentialsProvider c-p (.getAttribute context ClientContext/CREDS_PROVIDER)
                     #^URI u (.getURI request)
                     #^String target-host (.getHost u)
                     target-port (or (.getPort u)
                                     (if (= "https" (.getScheme u))
                                       443
                                       80))]
                 
                 (when-not (.getAuthScheme auth-state)
                   (.setCredentials c-p (AuthScope. target-host target-port) c)
                   (.setAuthScheme auth-state (BasicScheme.))
                   (.setCredentials auth-state c)))))]
        
        (.addRequestInterceptor client prx 0)))))


(defn- handle-http
  "Returns a map of
    code,
    reason,
    content
    (entity-as HttpEntity as),
    (headers-as headers),
    response,
    client."
  ([parameters http-verb as h-as]
   (handle-http parameters http-verb as h-as nil))

  ([parameters #^HttpUriRequest http-verb as h-as
    #^CookieStore cookie-store
    filters
    #^ClientConnectionManager
    connection-manager]
     
   (let [#^DefaultHttpClient http-client (if connection-manager
                                           (DefaultHttpClient.
                                             connection-manager
                                             ;; Params go away in 4.1.
                                             (BasicHttpParams.))
                                           (DefaultHttpClient.))
         params (.getParams http-client)]

     (when cookie-store
       (.setCookieStore http-client cookie-store))

     ;; Used for, e.g., proxy addition.
     (when parameters
       (doseq [[pname pval] parameters]
         (.setParameter params pname pval)))
    
     (let [#^HttpResponse http-response
           ;; Used for manipulation of the request prior to execution.
           ;; Allows things like Basic Auth.
           (if filters
             (let [#^BasicHttpContext context (BasicHttpContext.)]
               (doseq [f filters]
                 (f http-client http-verb context))
               (.execute http-client http-verb context))

             ;; Otherwise, be simple.
             (.execute http-client http-verb))
            
           #^StatusLine   status-line   (.getStatusLine http-response)
           #^HttpEntity   entity        (.getEntity http-response)

           response {:code (.getStatusCode status-line)
                     :reason (.getReasonPhrase status-line)
                     :content
                     (entity-as entity as (.getStatusCode status-line))
                     
                     :entity entity
                     :client http-client
                     :response http-response
                     :headers (headers-as
                               (.headerIterator http-response)
                               h-as)}]

       ;; I don't know if it's actually a good thing to do this.
       ;; (.. http-client getConnectionManager closeExpiredConnections)
       (when-not connection-manager
         (.. http-client getConnectionManager shutdown))
       
       response))))

(defn- adding-headers! [#^AbstractHttpMessage verb headers]
  (when headers
    (doseq [[h v] headers]
      (.addHeader verb h (str v))))
  verb)

(defn #^URI resolve-uri [uri-parts query-parameters]
  (cond
    (instance? URI uri-parts)
    (let [#^URI u uri-parts]
      (if (and query-parameters
               (not (empty? query-parameters)))
        (. URIUtils createURI 
           (or (.getScheme u) "http")
           (.getHost u)
           (.getPort u)
           (.getPath u)
           (combine-query
             (.getQuery u)
             (encode-query query-parameters))
           (.getFragment u))
        u))
    
    (associative? uri-parts)
    (let [{:keys [scheme host path port query fragment]} uri-parts]
      (. URIUtils createURI
         (or scheme "http")
         host
         (ensure-int port)
         path
         (combine-query
           (encode-query query)
           (encode-query query-parameters))
         fragment))
    
    (string? uri-parts)
    (recur (URI. uri-parts) query-parameters)))

;; For requests with no body.
;; Sorry for the shotgun punctuation!
(defmacro def-http-verb [verb class]
  `(defn ~verb
     ~(str "Submit an HTTP " verb " request. The query string is appended to the URI.")
     [uri-parts# & rest#]
     (let [{:keys [~'query ~'headers ~'parameters ~'as ~'headers-as
                   ~'cookie-store ~'filters
                   ~'connection-manager]} (apply hash-map rest#)]
       (handle-http
         ~'parameters
         (adding-headers!
           (new ~class
                (resolve-uri uri-parts# ~'query))
           ~'headers)
         ~'as
         ~'headers-as
         ~'cookie-store
         ~'filters
         ~'connection-manager))))
 
;; For requests with bodies.
(defmacro def-http-body-verb [verb class]
  `(defn ~verb
     ~(str "Submit an HTTP " verb " request.
Optional keyword arguments:
  :query      -- a query parameter map.
  :headers    -- a map of HTTP headers.
  :body       -- an HttpEntity.
                 See <http://hc.apache.org/httpcomponents-core/httpcore/apidocs/org/apache/http/HttpEntity.html?is-external=true>.
  :parameters -- a map of values to be passed to HttpParams.setParameter.
  :filters    -- a sequence of request filter functions, as produced by `preemptive-basic-auth-filter`.
  :connection-manager -- an instance of ClientConnectionManager, or nil.

If both query and body are provided, the query string is appended to the URI.
If only a query parameter map is provided, it is included in the body.")
    [uri-parts# & rest#]
    (let [{:keys [~'query ~'headers ~'body ~'parameters
                  ~'as ~'headers-as
                  ~'cookie-store
                  ~'filters
                  ~'connection-manager]}
          (apply hash-map rest#)]
      (let [http-verb# (new ~class (resolve-uri uri-parts# (when ~'body ~'query)))]
        (if ~'body
          (.setEntity http-verb# ~'body)
          (when ~'query
            (.setEntity http-verb#
                        (new UrlEncodedFormEntity
                             (seq (map->name-value-pairs ~'query)) "UTF-8"))))
        (handle-http
          ~'parameters
          (adding-headers!
            http-verb# ~'headers)
          ~'as
          ~'headers-as
          ~'cookie-store
          ~'filters
          ~'connection-manager)))))
  
(def-http-body-verb post HttpPost)
(def-http-body-verb put HttpPut)
(def-http-verb get HttpGet)
(def-http-verb head HttpHead)
(def-http-verb delete HttpDelete)
(def-http-verb options HttpOptions)

(defn #^HttpHost http-host [& args]
  (let [{:keys [host port scheme]} (apply hash-map args)]
    (HttpHost. host (ensure-int port) scheme)))


(let [rename-to
      {:default-headers                       org.apache.http.client.params.ClientPNames/DEFAULT_HEADERS ; Collection of Headers.
       :default-host                          org.apache.http.client.params.ClientPNames/DEFAULT_HOST ; HttpHost.
       :default-proxy                         org.apache.http.conn.params.ConnRoutePNames/DEFAULT_PROXY ; HttpHost.
       :virtual-host                          org.apache.http.client.params.ClientPNames/VIRTUAL_HOST ; HttpHost.
       :forced-route                          org.apache.http.conn.params.ConnRoutePNames/FORCED_ROUTE ; HttpRoute.
       :max-total-connections                 org.apache.http.conn.params.ConnManagerPNames/MAX_TOTAL_CONNECTIONS ; ConnPerRoute.
       :local-address                         org.apache.http.conn.params.ConnRoutePNames/LOCAL_ADDRESS ; InetAddress.
       :protocol-version                      org.apache.http.params.CoreProtocolPNames/PROTOCOL_VERSION ; ProtocolVersion.
       :max-status-line-garbage               org.apache.http.conn.params.ConnConnectionPNames/MAX_STATUS_LINE_GARBAGE ; Integer.
       :max-connections-per-route             org.apache.http.conn.params.ConnManagerPNames/MAX_CONNECTIONS_PER_ROUTE ; Integer.
       :connection-timeout                    org.apache.http.params.CoreConnectionPNames/CONNECTION_TIMEOUT ; Integer.
       :max-header-count                      org.apache.http.params.CoreConnectionPNames/MAX_HEADER_COUNT ; Integer.
       :max-line-length                       org.apache.http.params.CoreConnectionPNames/MAX_LINE_LENGTH ; Integer.
       :so-linger                             org.apache.http.params.CoreConnectionPNames/SO_LINGER ; Integer.
       :so-timeout                            org.apache.http.params.CoreConnectionPNames/SO_TIMEOUT ; Integer.
       :socket-buffer-size                    org.apache.http.params.CoreConnectionPNames/SOCKET_BUFFER_SIZE ; Integer.
       :wait-for-continue                     org.apache.http.params.CoreProtocolPNames/WAIT_FOR_CONTINUE ; Integer.
       :max-redirects                         org.apache.http.client.params.ClientPNames/MAX_REDIRECTS ; Integer.
       :timeout                               org.apache.http.conn.params.ConnManagerPNames/TIMEOUT ; Long.
       :stale-connection-check                org.apache.http.params.CoreConnectionPNames/STALE_CONNECTION_CHECK ; Boolean.
       :tcp-nodelay                           org.apache.http.params.CoreConnectionPNames/TCP_NODELAY ; Boolean.
       :strict-transfer-encoding              org.apache.http.params.CoreProtocolPNames/STRICT_TRANSFER_ENCODING ; Boolean.
       :use-expect-continue                   org.apache.http.params.CoreProtocolPNames/USE_EXPECT_CONTINUE ; Boolean.
       :handle-authentication                 org.apache.http.client.params.ClientPNames/HANDLE_AUTHENTICATION ; Boolean.
       :handle-redirects                      org.apache.http.client.params.ClientPNames/HANDLE_REDIRECTS ; Boolean.
       :reject-relative-redirect              org.apache.http.client.params.ClientPNames/REJECT_RELATIVE_REDIRECT ; Boolean.
       :allow-circular-redirects              org.apache.http.client.params.ClientPNames/ALLOW_CIRCULAR_REDIRECTS ; Boolean.
       :single-cookie-header                  org.apache.http.cookie.params.CookieSpecPNames/SINGLE_COOKIE_HEADER ; Boolean.
       :connection-manager-factory-class-name org.apache.http.client.params.ClientPNames/CONNECTION_MANAGER_FACTORY_CLASS_NAME ; String.
       :http-content-charset                  org.apache.http.params.CoreProtocolPNames/HTTP_CONTENT_CHARSET ; String.
       :http-element-charset                  org.apache.http.params.CoreProtocolPNames/HTTP_ELEMENT_CHARSET ; String.
       :origin-server                         org.apache.http.params.CoreProtocolPNames/ORIGIN_SERVER ; String.
       :user-agent                            org.apache.http.params.CoreProtocolPNames/USER_AGENT ; String.
       :cookie-policy                         org.apache.http.client.params.ClientPNames/COOKIE_POLICY ; String.
       :credential-charset                    org.apache.http.auth.params.AuthPNames/CREDENTIAL_CHARSET ; String.
       :date-patterns                         org.apache.http.cookie.params.CookieSpecPNames/DATE_PATTERNS ; String.
       }]
  
  (defn map->params
    "Put more pleasant names on the Apache constants."
    [m]
    (rename-keys
     m
     rename-to)))

