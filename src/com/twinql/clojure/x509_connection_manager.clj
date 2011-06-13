(ns com.twinql.clojure.x509-connection-manager
  (:require [com.twinql.clojure.http :as http])
  (:require [com.twinql.clojure.NaiveTrustManager :as trust-mgr])
  (:require [com.twinql.clojure.PermissiveHostnameVerifier :as verifier])
  (:require [clojure.contrib.java-utils :as jutil])
  (:import
   (java.security
    KeyStore
    SecureRandom)
   (java.security.cert
    X509Certificate
    CertificateFactory)
   (javax.net.ssl
    KeyManagerFactory
    SSLContext
    HttpsURLConnection)
   (java.io
    File
    FileInputStream
    InputStreamReader
    FileNotFoundException)
   (org.apache.http.params
    BasicHttpParams
    HttpParams)
   (org.apache.http.conn.ssl
    SSLSocketFactory
    X509HostnameVerifier)
   (org.apache.http.conn.scheme
    Scheme
    SchemeRegistry)
   (org.apache.http.conn
    ClientConnectionManager)
   (org.apache.http.impl.conn.tsccm
    ThreadSafeClientConnManager)
   (org.apache.http.impl.conn
    SingleClientConnManager)
   (org.apache.http.entity
    StringEntity)))



(defn- #^FileInputStream load-embedded-resource [#^String resource]
  "Loads a resource embedded in a jar file. Returns a FileInputStream"
  (let [thr (Thread/currentThread)
        loader (.getContextClassLoader thr)
        resource (.getResource loader resource)]
    (FileInputStream. (File. (. resource toURI)))))




(defn- #^FileInputStream resource-stream [#^String path]
  "Loads the resource at the specified path, and returns it as a
   FileInputStream. If there is no file at the specified path, and we
   are running as a jar, we'll attempt to load the resource embedded
   within the jar at the specified path."
  (try
    (if (. (jutil/file path) exists)
      (FileInputStream. path)
      (load-embedded-resource path))
    (catch Exception _ (throw (new FileNotFoundException
                                   (str "File or resource \""
                                        path
                                        "\" could not be found."))))))




(defn #^X509Certificate load-x509-cert [#^String path]
  "Loads an x509 certificate from the specified path, which may be either
   a file system path or a path to an embedded resource in a jar file.
   Returns an instace of java.security.cert.X509Certificate."
  (let [cert-file-instream (resource-stream path)
        cert-factory (CertificateFactory/getInstance "X.509")
        cert (. cert-factory generateCertificate cert-file-instream)]
    (. cert-file-instream close)
    (cast X509Certificate cert)))




(defn #^KeyStore load-keystore
  [#^FileInputStream keystore-stream #^String password]
  "Loads a KeyStore from the specified file. Param keystore-stream is
   an InputStream.  If password is provided, that will be used to unlock
   the KeyStore. Password may be nil. If keystore-stream is nil, this
   returns an empty default KeyStore."
  (let [ks (KeyStore/getInstance (KeyStore/getDefaultType))]
    (if keystore-stream
      (if password
        (. ks load keystore-stream (.toCharArray password))
        (. ks load keystore-stream nil))
      (. ks load nil nil))
    ks))



(defn #^KeyStore add-x509-cert
  [#^KeyStore keystore #^String cert-alias #^String certificate]
  "Adds the x509 certificate to the specified keystore. Param cert-alias
   is a name for this cert. Returns KeyStore with the certificate loaded."
  (. keystore setCertificateEntry cert-alias certificate)
  keystore)




(defn #^KeyManagerFactory key-manager-factory
  [#^KeyStore keystore #^String password]
  "Returns a key manager for X509 certs using the speficied keystore."
  (let [kmf (KeyManagerFactory/getInstance "SunX509")]
    (if password
      (. kmf init keystore (. password toCharArray))
      (. kmf init keystore nil))
    kmf))




(defn #^SSLContext create-ssl-context
  [#^X509TrustManager trust-manager #^KeyManagerFactory key-manager-factory]
  "Creates a new SSL context with the specified trust manager.
   If trust-manager is nil, we'll use a NaiveTrustManager that
   trusts everyone."
  (let [ctx (SSLContext/getInstance "TLS")
        sr (new SecureRandom)
        key-managers (. key-manager-factory getKeyManagers)
        naive-trust-manager
        (into-array javax.net.ssl.X509TrustManager
                    (list (new com.twinql.clojure.NaiveTrustManager)))]
    (if (nil? trust-manager)
      (. ctx init key-managers naive-trust-manager sr)
      (. ctx init key-managers trust-manager sr))
    ctx))




(defn #^SSLSocketFactory ssl-socket-factory
  [#^SSLContext ssl-context #^X509HostnameVerifier hostname-verifier]
  "Returns a new SSLSocketFactory with the specified SSL context.
   Param hostname-verifier is an instance of
   org.apache.http.conn.ssl.X509HostnameVerifier -- not the Sun version.
   If hostname-verifier is nil, we'll use a PermissiveHostnameVerifier,
   which always says all hosts are verified."
  (let [sf (SSLSocketFactory. ssl-context)]
    (if hostname-verifier
      (. sf setHostnameVerifier hostname-verifier)
      (. sf setHostnameVerifier
         (new com.twinql.clojure.PermissiveHostnameVerifier)))
    sf))




(defn #^SchemeRegistry scheme-registry
  [#^SSLSocketFactory socket-factory #^Integer port]
  "Creates a scheme registry using the given socket factory to connect
   on the port you specify."
  (let [#^SchemeRegistry scheme-registry (SchemeRegistry.)]
    (.register scheme-registry (Scheme. "https" socket-factory port))
    scheme-registry))


;; This is the connection you pass to the http get/post function.
;; You must call init-connection-manager to initialize the
;; connection manager with your certs and private keys.
(declare *connection-manager*)


(defn #^SchemeRegistry init-scheme-registry
  "Initializes and returns a SchemeRegistry. Use this if you need a
   SchemeRegistry for some external connection manager or for the
   async-client. See the doc for init-connection-manager."
  [opts]
  (let [initial-keystore (load-keystore
                          (resource-stream (:keystore-file opts))
                          (:keystore-password opts))
        keystore-with-cert (add-x509-cert
                            initial-keystore
                            (:certificate-alias opts)
                            (load-x509-cert (:certificate-file opts)))
        key-mgr-factory (key-manager-factory
                         keystore-with-cert
                         (:keystore-password opts))
        hostname-verifier (:hostname-verifier opts)
        port (or (:port opts) 443)
        ctx (create-ssl-context (:trust-managers opts) key-mgr-factory)
        socket-factory (ssl-socket-factory ctx (:hostname-verifier opts))]
    (scheme-registry socket-factory port)))



(defn #^ClientConnectionManager init-connection-manager
  "Creates an instance of ClientConnectionManager using the specified
   configuration options. After calling this, an instance of
   ClientConnectionManager will be available in *connection-manager*.

   The opts param is a map with the following keys:

   :keystore-file [optional] Path to Java keystore containing any
   private keys and trusted certificate authority certificates required
   for this connection.

   :keystore-password [optional] Password to unlock KeyStore.

   :certificate-alias [optional] A name by which to access an X509
   certificate that will be loaded into the KeyStore.

   :certificate-file [optional] The path to the file containing an
   X509 certificate (or certificate chain) to be used in the https
   connection

   :hostname-verifier [optional] An implementation of
   org.apache.http.conn.ssl.X509HostnameVerifier for verifying the hostname
   of the remote server during the SSL handshake. If you omit this option,
   the connection will use a PermissiveHostnameVerifier, which does not
   actually verify the host. It just blindly accepts that the host is who
   it says it is. This is convenient, but not safe.

   :port [optional] The port number to connect to on the remote host. If
   this is not specified, defaults to 443.

   :trust-managers [optional] An array of javax.net.ssl.X509TrustManager.
   These are used to verify the certificates sent by the remote host. If
   you don't specify this option, the connection will use an instance of
   NaiveTrustManager, which blindly trusts all certificates. Again, this
   is handy, but it's not safe.

   :connection-mgr-type [optional] The type of connection manager you want
   to use. Valid values are 'SingleClientConnManager' and
   'ThreadSafeClientConnManager' (which is the default).

   Although none of these options are required, the realistic minimum
   required options for a secure connection using client-side certificates
   would be :keystore-file and :keystore-password (for your private key)
   and :certificate-file (and :certificate-password if your client cert
   is password-protected) to load the X509 client certificate."
  [opts]
  (let [scheme-registry (init-scheme-registry opts)
        http-params (or (:http-params opts) (BasicHttpParams.))]
    (when-not (bound? #'*connection-manager*)
      (if (= (:connection-mgr-type opts) "SingleClientConnManager")
        (def *connection-manager* (SingleClientConnManager.
                                   http-params scheme-registry))
        (def *connection-manager* (ThreadSafeClientConnManager.
                                   http-params scheme-registry)))))
  *connection-manager*)

