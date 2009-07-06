(ns com.twinql.clojure.http
  (:refer-clojure :exclude [get])
  (:require [clojure.contrib.duck-streams :as duck])
  (:import 
    (java.lang Exception)
    (java.net URI)
    (org.apache.http
      HttpResponse
      HttpEntity
      StatusLine)
    (org.apache.http.client.entity UrlEncodedFormEntity)
    (org.apache.http.client.methods
      HttpGet HttpPost HttpPut HttpDelete HttpHead)
    (org.apache.http.client HttpClient ResponseHandler)
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
  "Returns [code reason (entity-as HttpEntity as)]."
  [parameters http-verb as]
  (let [#^DefaultHttpClient http-client (new DefaultHttpClient)
        params (.getParams http-client)]
    
    ;; Used for, e.g., proxy addition.
    (when parameters
      (doseq [[pname pval] parameters]
        (.setParameter params pname pval)))
    
    (let [#^HttpResponse http-response (. http-client execute http-verb)
          #^StatusLine   status-line   (.getStatusLine http-response)
          #^HttpEntity   entity        (.getEntity http-response)
        
          response [(.getStatusCode status-line)
                    (.getReasonPhrase status-line)
                    (entity-as entity as)]]
      
      (.. http-client getConnectionManager shutdown)
      response)))

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
     (let [{:keys [~'query ~'headers ~'parameters ~'as]} (apply hash-map rest#)]
       (handle-http
         ~'parameters
         (adding-headers!
           (new ~class
                (resolve-uri uri-parts# ~'query))
           ~'headers)
         ~'as))))
 
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
    (let [{:keys [~'query ~'headers ~'body ~'parameters ~'as]} (apply hash-map rest#)]
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
          ~'as)))))
  
(def-http-body-verb post HttpPost)
(def-http-body-verb put HttpPut)
(def-http-verb get HttpGet)
(def-http-verb head HttpHead)
(def-http-verb delete HttpDelete)

(comment
  ;; TODO: make it easier to use parameters.
(defn map->params
  "Put more pleasant names on the Apache constants."
  [m]
  :default-proxy org.apache.http.conn.params.ConnRoutePNames/DEFAULT_PROXY
  :forced-route org.apache.http.conn.params.ConnRoutePNames/FORCED_ROUTE
  :local-address org.apache.http.conn.params.ConnRoutePNames/LOCAL_ADDRESS
  :max-status-line-garbage org.apache.http.conn.params.ConnConnectionPNames/MAX_STATUS_LINE_GARBAGE ; Expects Integer.
  :max-connections-per-route org.apache.http.conn.params.ConnManagerPNames/MAX_CONNECTIONS_PER_ROUTE ; Expects Integer.
  :max-total-connections org.apache.http.conn.params.ConnManagerPNames/MAX_TOTAL_CONNECTIONS ; Expects a ConnPerRoute.
  :timeout org.apache.http.conn.params.ConnManagerPNames/TIMEOUT))   ; Expects Long.
