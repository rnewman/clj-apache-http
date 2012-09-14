# *** DEPRECATED *** #

This library is built on an alpha version of the Apache HTTP async client. 
That underlying alpha library has some race conditions that cause connections
to leak from the connection pool. Under heavy use, you may lose all connections
in the pool.

The leaks were fixed in later versions of the underlying Apache library,
but those versions are not compatible with the alpha upon which this library was
built.

Please use Neotyk's http.async.client here: https://github.com/neotyk/http.async.client

Neotyk's library has all of the same features as this one, is more stable,
and is actively maintained!

# Secure Synchronous and Asynchronous HTTP Client #

This is a Clojure wrapper around the Apache Foundation's HTTP client. It 
supports synchronous and asynchronous HTTP connections, as well as X509
client certificates.

For information on using the standard synchronous HTTP client, see:

<https://github.com/diamondap/clj-apache-http/blob/master/README_HTTP.markdown>

For information on using the standard synchronous HTTP client with X509
client certificates, see:

<https://github.com/diamondap/clj-apache-http/blob/master/README_X509.markdown>

For information on using the asynchronous client, with or without X509 
certificates, see:

<https://github.com/diamondap/clj-apache-http/blob/master/README_ASYNC.markdown>

