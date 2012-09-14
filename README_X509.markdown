# x509-connection-manager #

This fork of rnewman's clj-apache-http includes support for https connections
that use client certificates.

The http verb functions in clj-apache-http use built-in connection managers
to set up http and https connections on standard ports. They also allow you 
to supply your own connection managers. 

The x509-connection-manager enables you to create a connection manager that
uses a keystore and an X509 client certificate (or certificate chain) file
to establish http connections that use client certificates.


# Building #

Before you start using this code, you may have to compile the 
NaiveTrustManager. You can do that by running the following command from the 
top-level project directory:

`lein compile com.twinql.clojure.NaiveTrustManager`


# Usage #

The following example represents a basic use case, where http-opts describe
the connection end point, and connection-opts describe security information
that x509-connection-manager needs to set up the secure connection.


    (defn test-request []
      (let [http-opts {:host "someserver.xyzzy.com"
                       :path "/DataLibrary/Secure"
                       :scheme "https"
                       :port 8686 }
            connection-opts {:keystore-file "certs/xyzzy.keystore"
                             :keystore-password "seekrit"
                             :certificate-alias "default"
                             :certificate-file "certs/my-client-cert.pem"
                             :certificate-password nil
                             :port 8686
                             :trust-managers nil
                             :hostname-verifier nil
                             :http-params nil
                             :connection-mgr-type "SingleClientConnManager" }
            conn-manager (init-connection-manager connection-opts)]
        (:content (http/post
                   http-opts
                   :as :string
                       :headers { "Content-Type" "text/xml" }
                       :body (StringEntity. "<test>hello</test>")
                       :parameters { }
                       :connection-manager conn-manager))))


This example shows all of the x509-connection-manager connection options. 
The keystore-file should contain the private key that goes with your client
certificate. Java requires a password for the keystore, so if you specify a
keystore file, you must also specify a keystore-password.

The certificate-file parameter should point to your client certificate. This
file may or may not be password protected.

If you specify a certificate-file, you should also specify a certificate-alias
to avoid a NullPointerException. The value of the certificate alias does not
seem to matter. Any string will do.

The trust-managers parameter should be either nil, or should be set to a
Java Array of javax.net.ssl.X509TrustManager. If it's nil, the 
x509-connection-manager will use a NaiveTrustManager that blindly trusts
all certificate authorities.

The hostname-verifier paramater should be an instance of 
org.apache.http.conn.ssl.X509HostnameVerifier, or nil. If it's nil, the 
x509-connection-manager will use Apache's AllowAllHostnameVerifier that 
blindly verifies all hosts. That is, it will tell the Java runtime that
the remote host is who it says it is, without actually taking any steps
to verify the claims.

The http-params parameter are passed through as-is to the underlying 
connection manager. These may specify connection-specific options, such as 
a proxy server.

The connection-mgr-type parameter may be either "SingleClientConnManager"
or "ThreadSafeClientConnManager". This determines what type of connection
manager init-connection-manager will create. If unspecified, it defaults to
ThreadSafeClientConnManager.

The function init-connection-manager initializes and returns a connection
manager. It also sets the value of *connection-manager*. So once you've
called init-connection-manager, you can simply refer to *connection-manager*.

# Debugging SSL Connections #

The easiest way to debug SSL connections is to do the following:

1. Create a core.clj file at clj-apache-http/src/com/twinql/clojure/core.clj
It should look like this:

    (ns com.twinql.clojure.core
     (:use com.twinql.clojure.x509-connection-manager)
     (:gen-class))
    (defn -main [] &lt;Your test code here&gt; )

Obviously, your test code would look something like the `test-request`
method above.

2. Add this line to your project.clj file:

    :main com.twinql.clojure.core

3. Compile the whole clj-apache-http project into an uberjar:

`lein uberjar`

4. Run the compiled jar from the command line with the SSL debug flag:

`java -Djavax.net.debug=all -jar clj-apache-http-2.3.1-standalone.jar`

This will run your test code, printing out information about what trusted
CA certificates are loaded, as well as which private keys and X509 client 
certificates are loaded. It also displays the certificate exchange and
handshake, to help you track down the cause of failed handshakes.

# Setting Up Certificates and Private Keys #

A full tutorial on SSL certificates and keys is beyond the scope of this 
README, but here are a few tips. Hopefully, this will spare you some pain.

If you're connecting with a client certificate, you'll likely need both of the
following 

1. The certificate (or certificate chain) file, which often ends in .pem
You get this from whoever controls the server you are connecting to.

2. The private key that goes with the certificate. This usually comes from
whoever gave you the certificate. It is often a binary file with a p12
extension though other private key formats can be converted for use in 
Jave keystores.

You will need to import the private key into a Java keystore, using the Java
keytool utility. A sample command looks something like this:

`keytool -importkeystore -destkeystore my_java.keystore -deststorepass seekrit -srckeystore my_private_key.p12 -srcstoretype PKCS12 -srcstorepass seekrit`

If your private key is protected with a password, be sure to set both
-deststorepass and -srcstorepass to the same value. Otherwise, you will get
this message when clojure tries to access the key at runtime:

java.security.UnrecoverableKeyException: Cannot recover key

You do not need to import your client certificate into your keystore. When
calling init-connection-manager, simply pass in the path to the client 
certificate file (the .pem file) using the :certificate-file paramater, and 
(if necessary) the :certificate-password parameter. The x509-connection-manager
will load the certificate at runtime.

