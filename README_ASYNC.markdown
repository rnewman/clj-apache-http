# Asynchronous HTTP Client #

This fork of rnewman's clj-apache-http includes support support for 
asynchronous HTTP(S) connections using Apache's HTTP Async client.
This client is currently in an alpha-2 release, but basic functionality 
appears to be stable.

The asynchronous client allows you to make multiple simultaneous HTTP(S) 
connections in a single thread. None of the requests block. They simply
trigger a callback when they complete.

For each request, you specify a set of callbacks: one to be executed on 
success, one to be executed on failure, and one to be executed if the 
request is cancelled. This is similar to setting up AJAX callbacks in 
JavaScript or Ruby callbacks using the Typhoeus HTTP client.

The async client works over HTTP, standard HTTPS, and with X509 client 
certificates.

# Samples #

Here is a sample of how to set up a connection manager and a client. Both
are reusable once they've been set up. The connection manager in particular
is highly configurable. None of the options in this example are required.
They're here just to give you a sense of what you can do with the connection
manager.

    (:require [com.twinql.clojure.http :as http]
              [com.twinql.clojure.async-client :as async])

    (def http-params { :connection-timeout 1000 
                       :cookie-policy 
                        org.apache.http.client.params.CookiePolicy/IGNORE_COOKIES
                       :default-proxy (http/http-host
                                  :host "my.proxy-server.kom"
                                  :port 8888)
                       :user-agent "Clojure-Apache HTTP(S)"
                       :use-expect-continue false 
                       :tcp-nodelay true 
                       :stale-connection-check false })

    (defn on-internal-error
      "Log internal errors from the async client's IOReactor. If we don't 
       provide a handler, the client blows up and can no longer service 
       requests. Handler should return true to continue servicing requests, 
       or false to stop. Returning false stops the IOReactor, and all 
       subsequent requests will fail."
      [exception]
      (prn "*** Caught exception inside IOReactor ***")
  	  (prn exception)
  	  true)
    
    
    (def conn-mgr (async/connection-manager
	  					(merge http-params
	   				{ :worker-threads 3
	    			  :time-to-live 4000
                      :max-total-connections 50
					  :internal-exception-handler on-internal-error
                      :max-per-route { "yahoo.com"              10 
                                       "hotelicopter.com"       15
                                       "google.com"             15 
                                       "jsonlint.com"           10 }})))
    
    (def client (async/http-client conn-mgr http-params))
    
    
    (defn on-success [response]
      (println "STATUS")
      (println (response-status response))
      (println "HEADERS")
      (println (response-headers response))
      (println "BODY")
      (println (response-body response)))

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
         :on-success (fn [resp] (println "custom google on-success"))
         :on-fail (fn [resp] (println "custom google on-fail"))
         :on-cancel (fn [resp] (println "custom google on-cancel"))}])
    
    (defn run-requests
      ""
      []
      ;; Be sure to start the client before using it!
      (.start client)
      (try
        (async/run! requests
              :client client
              :on-success on-success
              :on-fail on-fail
              :on-cancel on-cancel)
        (finally (.shutdown client)))))


Notice the last request defines its own on-success callback. If a callback is
defined within a request hash, the async client runs that callback. Otherwise,
it runs the callback passed in to the run! function. 

To use the async client with X509 client certificates, you set up an SSL
connection manager and pass that into the client constructor.

    (:require [com.twinql.clojure.x509-connection-manager :as x509]
              [com.twinql.clojure.async-client :as async])
   

    (defonce xml-request-template (get-resource "request.xml"))
    (defonce cert-dir "/certs")
    (defonce client-certificate-file (str cert-dir "/some_corp.certs.pem"))
    (defonce keystore-file (str cert-dir "/some_corp.keystore"))
    (defonce scheme-registry-opts
      {:keystore-file keystore-file
       :keystore-password "seekrit"
       :certificate-alias "default"
       :certificate-file client-certificate-file
       :certificate-password nil
       :port 8989
       :trust-managers nil
       :hostname-verifier nil })
    (defonce scheme-registry
      (x509/create-async-scheme-registry scheme-registry-opts))
    (def ssl-conn-manager
      (async/connection-manager {:scheme-registry scheme-registry
                                 :http-params http-params
                                 :worker-threads 5
                                 :max-total-connections 12
                                 :max-per-route { "some_corp.com" 12}}))
    (def client (async/http-client ssl-conn-manager http-params))


For more information, read the source and the doc comments in async_client.clj
and x509_connection_manager.clj.

Also, see the readme documents for the synchronous HTTP client and for the
X509 certification manager.

# Notes #

This Clojure library is currently using version 4.0-alpha2 of the Apache 
HttpAsyncClient library, which does not yet pass HTTP timeouts up to any
event handlers. This means that if your HTTP request times out because the
server did not respond before :so-timeout milliseconds, your on-fail event
handler will not be called.

There is a ticket open on this issue with Apache, and it should (should!) be 
fixed in an upcoming release.

