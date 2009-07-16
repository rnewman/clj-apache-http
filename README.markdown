# What is it? #

`clj-apache-http` is a Clojure wrapper library for the Apache HTTP Client
(version 4.0).

<http://hc.apache.org/>

It defines functions to perform HTTP requests, returning Clojure data
structures for most outputs.

Some knowledge of the underlying Apache `HttpClient` API is necessary for more
advanced usage, such as extracting response headers and specifying cookie jars.

For real-world code using this library, see the `clj-mql` project:

<https://github.com/rnewman/clj-mql/tree/master>

 
# Building #

Simply use `ant`, optionally passing `-Dclojure.jar="..."` and `-Dclojure.contrib.jar="..."`.

Put `clj-apache-http.jar` on your classpath.


# Loading #

This library defines a function named get, so it's best to require it like
this:

    (require ['com.twinql.clojure.http :as 'http])

or in your `(ns)` form:

    (:require [com.twinql.clojure.http :as http])


# Functions #

The exported interface consists of the functions `get`, `post`, `put`, `head`,
and `delete`, and the utility function `http-host` (to return a value suitable
for the host parameters, such as `:default-proxy` / `DEFAULT_PROXY`).

All functions take a "uri part" as input -- a URI, a string parsed as a URI, or a map like

    {:host "foo.com" :path "/bar/" :port 9000 :query {:x 5} :scheme "https" :fragment "hah"}

and the following keyword arguments:

* `:query`      — a query parameter map.
* `:headers`    — a map of HTTP headers.
* `:parameters` — a map of values to be passed to `HttpParams.setParameter`.

`post` and `put` additionally have a `:body` argument, which must be an `HttpEntity`.

The result of calling these functions is a map as follows:

* The status code: `:code`
* The reason phrase: `:reason`
* The content (subject to transformation): `:content`
* The Apache `Entity` associated with the request/response pair: `:entity`
* The Apache `HttpClient` used for the request (which allows access to the cookie jar): `:client`
* The response headers, a sequence of Apache `Header` objects: `:headers`

Typically most of these can be ignored; `:code` and `:content` are the most important fields.


# Examples #

    (:content (http/get (java.net.URI. "http://example.com") :as :string))
    =>
    "<HTML>\r\n<HEAD>\r\n  <TITLE>Example Web Page</TITLE>\r\n</HEAD> \r\n<body>…"

    (select-keys
      (http/get "http://clojure.org/api" :as :stream) [:code :reason :content])
    => 
    {:content #<EofSensorInputStream org.apache.http.conn.EofSensorInputStream@4ba57633>,
     :reason "OK",
     :code 200}

    (:reason (http/post "http://google.com/search" :query {:q "frobnosticate"}))
    =>
    "Method Not Allowed"

    (:code (http/get "http://google.com/search" :query {:q "frobnosticate"}))
    =>
    200



# Keyword parameters #
 
You can use `:query`, `:headers`, `:parameters`, and `:as`. These are all maps except
for `:as`, which can be `:identity` (or `nil`), `:stream`, `:reader`, or `:string`. Define
your own by defining a method on '`entity-as`' that turns an `HttpEntity` into the
appropriate format.

The `clj-mql` project defines an entity transformation method for JSON output, allowing 
requests like

    (:content (http/get "http://api.freebase.com/api/status" :as :json))
    =>
    {:transaction_id "cache;cache01.p01.sjc1:8101;2009-07-16T19:36:52Z;0006",
     :status "200 OK", :relevance "OK", :graph "OK",
     :code "/api/status/ok", :blob "OK"}


# Apache connection parameters #
You can pass a parameter map to the HTTP functions. This is used to set various
options on the HTTP client.

The keys are long-winded Java constants, but the capability is very useful
(e.g., for proxying). See 

<http://hc.apache.org/httpcomponents-client/httpclient/apidocs/org/apache/http/client/params/AllClientPNames.html>

To avoid verbosity, a function map->params is provided. This will rename the keys in your input to the appropriate constants.

For example, to issue a `HEAD` request via a proxy:

    (http/head "http://github.com/"
      :parameters (http/map->params
                    {:default-proxy (http/http-host :host "localhost" :port 8080)}))
