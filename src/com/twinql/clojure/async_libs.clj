;; The Apache HTTP Client libraries define several classes, including
;; Scheme and SchemeRegistry under two separate namespaces:
;; org.apache.http.conn and org.apache.http.nio.conn. The first is for
;; synchronous clients. The second is for async clients.
;;
;; Clojure complains when we try to include Scheme and SchemeRegistry
;; from both namespaces in the same Clojure namespace. For this reason,
;; the synchronous and async Apache classes are split into the
;; sync-libs and async-libs Clojure namespaces.
;;
;; API users should genarally not need to call into sync-libs and async-libs.
;; The x509 certificate manager and the async client are the main consumers
;; of these APIs.

(ns com.twinql.clojure.async-libs
  (:import
   (javax.net.ssl
    SSLContext)
   (org.apache.http.conn.ssl
    SSLSocketFactory)
   (org.apache.http.nio.conn.ssl
    SSLLayeringStrategy)
   (org.apache.http.conn.ssl
    SSLSocketFactory
    X509HostnameVerifier
    AllowAllHostnameVerifier)
   (org.apache.http.nio.conn.scheme
    Scheme
    SchemeRegistry)))


(defn #^SSLLayeringStrategy layering-strategy
  "Returns a new LayeringStrategy for managing SSL connections."
  [#^SSLContext ssl-context #^X509HostnameVerifier hostname-verifier]
  (let [verifier (or hostname-verifier (AllowAllHostnameVerifier.))]
  (SSLLayeringStrategy. ssl-context verifier)))

(defn #^Scheme scheme
  "Returns a new org.apache.http.nio.conn.scheme.Scheme. Param name should
   be \"http\" or \"https\". Param port is the port to connect to on the
   remote host."
  [#^String name #^int port #^LayeringStrategy strategy]
  (Scheme. name port strategy))


(defn #^Scheme default-http-scheme []
  (Scheme. "http" 80 nil))

(defn #^Scheme default-https-scheme []
  (let [ctx (SSLContext/getInstance "SSL")]
    (.init ctx nil nil nil)
    (Scheme. "https" 443 (SSLLayeringStrategy. ctx))))

(defn #^SchemeRegistry default-scheme-registry []
  (doto (SchemeRegistry.)
    (. register (default-http-scheme))
    (. register (default-https-scheme))))

(defn #^org.apache.http.nio.conn.scheme.SchemeRegistry scheme-registry
  "Creates a scheme registry using the given socket factory to connect
   on the port you specify. This scheme registry is suitable for the async
   http client."
  [#^SSLSocketFactory socket-factory #^Integer port
   #^SSLLayeringStrategy ssl-layering-strategy]
  (let [#^SchemeRegistry scheme-registry (SchemeRegistry.)]
    (.register scheme-registry (Scheme. "https" port ssl-layering-strategy))
    scheme-registry))


