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

# Examples #

    (http/get "http://clojure.org/api" :as :stream)
    => 
    [200 "OK" #<EofSensorInputStream
     org.apache.http.conn.EofSensorInputStream@76d3e1>]


    (http/get (java.net.URI. "http://example.com") :as :string)
    =>
    [200 "OK" "<HTML>\r\n<HEAD>\r\n  <TITLE>Example Web Page</TITLE>\r\n</HEAD> \r\n<body>  \r\n<p>You have reached this web page by typing &quot;example.com&quot;,\r\n&quot;example.net&quot;,\r\n  or &quot;example.org&quot; into your web browser.</p>\r\n<p>These domain names are reserved for use in documentation and are not available \r\n  for registration. See <a href=\"http://www.rfc-editor.org/rfc/rfc2606.txt\">RFC \r\n  2606</a>, Section 3.</p>\r\n</BODY>\r\n</HTML>\r\n\r\n"]

    (http/post "http://google.com/search" :query {:q "frobnosticate"})
    =>
    [405 "Method Not Allowed" #<BasicManagedEntity
     org.apache.http.conn.BasicManagedEntity@2ae4fe>]

    (http/get "http://google.com/search" :query {:q "frobnosticate"})
    =>
    [200 "OK" #<BasicManagedEntity org.apache.http.conn.BasicManagedEntity@9e7782>]



# Keyword parameters #
You can use `:query`, `:headers`, `:parameters`, and `:as`. These are all maps except
for `:as`, which can be `:identity` (or `nil`), `:stream`, `:reader`, or `:string`. Define
your own by defining a method on '`entity-as`' that turns an `HttpEntity` into the
appropriate format.


# Apache connection parameters #
You can pass a parameter map to the HTTP functions. This is used to set various
options on the HTTP client.

The keys are long-winded Java constants, but the capability is very useful
(e.g., for proxying). See 

<http://hc.apache.org/httpcomponents-client/httpclient/apidocs/org/apache/http/client/params/AllClientPNames.html>

To avoid verbosity, a function map->params is provided. This will rename the keys in your input to the appropriate constants.

For example, to issue a `HEAD` request via a proxy:

    (http/head "http://github.com/" :parameters {:default-proxy (http-host :host "localhost" :port 8080)})
