;; Compile with:
;;
;; lein compile com.twinql.clojure.PermissiveHostnameVerifier
;;
;; Use this verifier to bypass SSL hostname verification.
;;
(ns com.twinql.clojure.PermissiveHostnameVerifier
  (:import
   (java.security.cert
    X509Certificate)
   (javax.net.ssl
    HostnameVerifier
    SSLSession
    SSLSocket)
   (org.apache.http.conn.ssl
    X509HostnameVerifier))
  (:gen-class
   :name com.twinql.clojure.PermissiveHostnameVerifier
   :implements [org.apache.http.conn.ssl.X509HostnameVerifier]
   :constructors {[] []}))

(defn -verify
  [#^PermissiveHostnameVerifier this #^String host #^SSLSocket socket]
  "Always returns null"
  (println "Called first version")
  nil)

(defn -verify
  [#^PermissiveHostnameVerifier this #^String host
   #^"[String;" cns #^"[String;" subjectAlts]
  "Always returns null"
  (println "Called second version")
  nil)

(defn -verify
  [#^PermissiveHostnameVerifier this #^String host #^X509Certificate cert]
  "Always returns null"
  (println "Called third version")
  nil)
