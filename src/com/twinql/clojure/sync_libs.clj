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

(ns com.twinql.clojure.sync-libs
  (:import
   (javax.net.ssl
    SSLContext)
   (org.apache.http.conn.ssl
    SSLSocketFactory)
   (org.apache.http.conn.scheme
    Scheme
    SchemeRegistry)))


(defn #^SchemeRegistry scheme-registry
  "Creates a scheme registry using the given socket factory to connect
   on the port you specify. This scheme registry is suitable for the normal
   (synchronous) connection manager and client."
  [#^SSLSocketFactory socket-factory #^Integer port]
  (let [#^SchemeRegistry scheme-registry (SchemeRegistry.)]
    (.register scheme-registry (Scheme. "https" port socket-factory))
    scheme-registry))


