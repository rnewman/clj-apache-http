(ns com.twinql.clojure.http
  (:refer-clojure :exclude [get])
  (:use clojure.set)
  (:require [clojure.contrib.duck-streams :as duck])
  (:import 
    (java.lang Exception)
    (java.net URI)
    (org.apache.http
      HttpHost        ; For proxy.
      HttpResponse
      HttpEntity
      Header
      StatusLine)
    (org.apache.http.client.entity UrlEncodedFormEntity)
    (org.apache.http.client.methods
      HttpGet HttpPost HttpPut HttpDelete HttpHead)
    (org.apache.http.client
      CookieStore
      HttpClient
      ResponseHandler)
    (org.apache.http.client.utils URIUtils URLEncodedUtils)
    (org.apache.http.message
      AbstractHttpMessage
      BasicNameValuePair)
    (org.apache.http.impl.client DefaultHttpClient BasicResponseHandler)))

(defn- #^String as-str
  "Because contrib's isn't typed correctly."
  [x]
  (if (instance? clojure.lang.Named x)
    (name x)
    (str x)))
    
(defn- map->name-value-pairs
  "Take an associative structure and return a sequence of BasicNameValuePairs."
  [q]
  (map (fn [[param value]] 
         (new BasicNameValuePair (as-str param) (as-str value)))
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


(defmulti entity-as (fn [entity as] as))

(defmethod entity-as :identity [entity as] entity)
(defmethod entity-as nil [entity as] entity)

(defmethod entity-as :stream [#^HttpEntity entity as]
  (.getContent entity))
(defmethod entity-as :reader [#^HttpEntity entity as]
  (duck/reader (.getContent entity)))
(defmethod entity-as :string [#^HttpEntity entity as]
  (duck/slurp* (.getContent entity)))

(defn- handle-http
  "Returns a map of code, reason, content (entity-as HttpEntity as),
  headers, response, client."
  ([parameters http-verb as]
   (handle-http parameters http-verb as nil))

  ([parameters http-verb as #^CookieStore cookie-store]
   (let [#^DefaultHttpClient http-client (new DefaultHttpClient)
         params (.getParams http-client)]

     (when cookie-store
       (.setCookieStore http-client cookie-store))

     ;; Used for, e.g., proxy addition.
     (when parameters
       (doseq [[pname pval] parameters]
         (.setParameter params pname pval)))

     (let [#^HttpResponse http-response (. http-client execute http-verb)
           #^StatusLine   status-line   (.getStatusLine http-response)
           #^HttpEntity   entity        (.getEntity http-response)

           response {:code (.getStatusCode status-line)
                     :reason (.getReasonPhrase status-line)
                     :content (entity-as entity as)
                     :entity entity
                     :client http-client
                     :response http-response
                     :headers (iterator-seq (.headerIterator http-response))}]

       (.. http-client getConnectionManager shutdown)
       response))))

(defn- adding-headers! [#^AbstractHttpMessage verb headers]
  (when headers
    (doseq [[h v] headers]
      (.addHeader verb h (str v))))
  verb)

(defn resolve-uri [uri-parts query-parameters]
  (cond
    (instance? URI uri-parts)
    (if (and query-parameters
             (not (empty? query-parameters)))
      (. URIUtils createURI 
         (or (.getScheme uri-parts) "http")
         (.getHost uri-parts)
         (.getPort uri-parts)
         (.getPath uri-parts)
         (combine-query
           (.getQuery uri-parts)
           (encode-query query-parameters))
         (.getFragment uri-parts))
      uri-parts)
    
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
     (let [{:keys [~'query ~'headers ~'parameters ~'as ~'cookie-store]} (apply hash-map rest#)]
       (handle-http
         ~'parameters
         (adding-headers!
           (new ~class
                (resolve-uri uri-parts# ~'query))
           ~'headers)
         ~'as
         ~'cookie-store))))
 
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

If both query and body are provided, the query string is appended to the URI.
If only a query parameter map is provided, it is included in the body.")
    [uri-parts# & rest#]
    (let [{:keys [~'query ~'headers ~'body ~'parameters ~'as
                  ~'cookie-store]}
          (apply hash-map rest#)]
      (let [http-verb# (new ~class (resolve-uri uri-parts# (when ~'body ~'query)))]
        (if ~'body
          (.setEntity http-verb# ~'body)
          (when ~'query
            (.setEntity http-verb#
                        (new UrlEncodedFormEntity
                             (seq (map->name-value-pairs ~'query))))))
        (handle-http
          ~'parameters
          (adding-headers!
            http-verb# ~'headers)
          ~'as
          ~'cookie-store)))))
  
(def-http-body-verb post HttpPost)
(def-http-body-verb put HttpPut)
(def-http-verb get HttpGet)
(def-http-verb head HttpHead)
(def-http-verb delete HttpDelete)

(defn #^HttpHost http-host [& args]
  (let [{:keys [host port scheme]} (apply hash-map args)]
    (HttpHost. host (ensure-int port) scheme)))

(defn map->params
  "Put more pleasant names on the Apache constants."
  [m]
  ;; In Clojure a1397390d8b3b63f2039359520629d87b152d717, rename-keys is buggy.
  (rename-keys
    m
    {:default-headers                       org.apache.http.client.params.ClientPNames/DEFAULT_HEADERS                       ; Collection of Headers.
     :default-host                          org.apache.http.client.params.ClientPNames/DEFAULT_HOST                          ; HttpHost.
     :default-proxy                         org.apache.http.conn.params.ConnRoutePNames/DEFAULT_PROXY                        ; HttpHost.
     :virtual-host                          org.apache.http.client.params.ClientPNames/VIRTUAL_HOST                          ; HttpHost.
     :forced-route                          org.apache.http.conn.params.ConnRoutePNames/FORCED_ROUTE                         ; HttpRoute.
     :max-total-connections                 org.apache.http.conn.params.ConnManagerPNames/MAX_TOTAL_CONNECTIONS              ; ConnPerRoute.
     :local-address                         org.apache.http.conn.params.ConnRoutePNames/LOCAL_ADDRESS                        ; InetAddress.
     :protocol-version                      org.apache.http.params.CoreProtocolPNames/PROTOCOL_VERSION                       ; ProtocolVersion.
     :max-status-line-garbage               org.apache.http.conn.params.ConnConnectionPNames/MAX_STATUS_LINE_GARBAGE         ; Integer.
     :max-connections-per-route             org.apache.http.conn.params.ConnManagerPNames/MAX_CONNECTIONS_PER_ROUTE          ; Integer.
     :connection-timeout                    org.apache.http.params.CoreConnectionPNames/CONNECTION_TIMEOUT                   ; Integer.
     :max-header-count                      org.apache.http.params.CoreConnectionPNames/MAX_HEADER_COUNT                     ; Integer.
     :max-line-length                       org.apache.http.params.CoreConnectionPNames/MAX_LINE_LENGTH                      ; Integer.
     :so-linger                             org.apache.http.params.CoreConnectionPNames/SO_LINGER                            ; Integer.
     :so-timeout                            org.apache.http.params.CoreConnectionPNames/SO_TIMEOUT                           ; Integer.
     :socket-buffer-size                    org.apache.http.params.CoreConnectionPNames/SOCKET_BUFFER_SIZE                   ; Integer.
     :wait-for-continue                     org.apache.http.params.CoreProtocolPNames/WAIT_FOR_CONTINUE                      ; Integer.
     :max-redirects                         org.apache.http.client.params.ClientPNames/MAX_REDIRECTS                         ; Integer.
     :timeout                               org.apache.http.conn.params.ConnManagerPNames/TIMEOUT                            ; Long.
     :stale-connection-check                org.apache.http.params.CoreConnectionPNames/STALE_CONNECTION_CHECK               ; Boolean.
     :tcp-nodelay                           org.apache.http.params.CoreConnectionPNames/TCP_NODELAY                          ; Boolean.
     :strict-transfer-encoding              org.apache.http.params.CoreProtocolPNames/STRICT_TRANSFER_ENCODING               ; Boolean.
     :use-expect-continue                   org.apache.http.params.CoreProtocolPNames/USE_EXPECT_CONTINUE                    ; Boolean.
     :handle-authentication                 org.apache.http.client.params.ClientPNames/HANDLE_AUTHENTICATION                 ; Boolean.
     :handle-redirects                      org.apache.http.client.params.ClientPNames/HANDLE_REDIRECTS                      ; Boolean.
     :reject-relative-redirect              org.apache.http.client.params.ClientPNames/REJECT_RELATIVE_REDIRECT              ; Boolean.
     :allow-circular-redirects              org.apache.http.client.params.ClientPNames/ALLOW_CIRCULAR_REDIRECTS              ; Boolean.
     :single-cookie-header                  org.apache.http.cookie.params.CookieSpecPNames/SINGLE_COOKIE_HEADER              ; Boolean.
     :connection-manager-factory-class-name org.apache.http.client.params.ClientPNames/CONNECTION_MANAGER_FACTORY_CLASS_NAME ; String.
     :http-content-charset                  org.apache.http.params.CoreProtocolPNames/HTTP_CONTENT_CHARSET                   ; String.
     :http-element-charset                  org.apache.http.params.CoreProtocolPNames/HTTP_ELEMENT_CHARSET                   ; String.
     :origin-server                         org.apache.http.params.CoreProtocolPNames/ORIGIN_SERVER                          ; String.
     :user-agent                            org.apache.http.params.CoreProtocolPNames/USER_AGENT                             ; String.
     :cookie-policy                         org.apache.http.client.params.ClientPNames/COOKIE_POLICY                         ; String.
     :credential-charset                    org.apache.http.auth.params.AuthPNames/CREDENTIAL_CHARSET                        ; String.
     :date-patterns                         org.apache.http.cookie.params.CookieSpecPNames/DATE_PATTERNS                     ; String.
    }))
