;; NaiveTrustManager is an implementation of javax.net.ssl.X509TrustManager
;; that blindly trusts all client certificates, server certificates and
;; certificate authorities. This is generally not a safe thing to do! Use
;; this for self-signed certs, or for certs that you don't care to verify.
;; But understand the risks: you may not be talking to the party you think
;; you're talking to. The NaiveTrustManager simply trusts everyone.
;;
;; You will need to compile this before you can use it. If you're using
;; leiningen, and you don't see a file in your project at this path:
;;
;; classes/com/twinql/clojure/NaiveTrustManager.class
;;
;; ... then you can compile this with the following command, run from the
;; top-level directory of your leiningen project:
;;
;; lein compile com.twinql.clojure.NaiveTrustManager
;;

(ns com.twinql.clojure.NaiveTrustManager
  (:import
   (java.security.cert
    X509Certificate
    CertificateFactory)
   (javax.net.ssl
    X509TrustManager))
  (:gen-class
   :name com.twinql.clojure.NaiveTrustManager
   :implements [javax.net.ssl.X509TrustManager]
   :constructors {[] []}))


;; Implementation of Java javax.net.ssl.X509TrustManager methods
;;
;; public void checkClientTrusted(X509Certificate[] chain, String auth-type)
;; public void checkServerTrusted(X509Certificate[] chain, String auth-type)
;;
;; In a real implementation, these methods throw a CertificateException if
;; the certificate is not trusted. In this naive implementation, they do
;; nothing. By not throwing an exception, they imply that the certificate
;; is trusted.
;;
(defn -checkClientTrusted
  [#^NaiveTrustManager this #^"[LX509Certificate;" chain #^String auth-type]
  "Always returns nil, never throws CertificateException"
  nil)

(defn -checkServerTrusted
  [#^NaiveTrustManager this #^"[LX509Certificate;" chain #^String auth-type]
  "Always returns nil, never throws CertificateException"
  nil)

(defn #^"[LX509Certificate;" -getAcceptedIssuers [#^NaiveTrustManager this]
  "Always returns nil"
  nil)

