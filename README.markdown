# What is it? #

`clj-apache-http` is a Clojure wrapper library for the Apache HTTP Client
(version 4.0).

<http://hc.apache.org/>

It defines functions to perform HTTP requests, extensibly returning Clojure data
structures for most outputs.

Some knowledge of the underlying Apache `HttpClient` API is necessary for more
advanced usage, such as specifying cookie jars.

For real-world code using this library, see the `clj-mql` project:

<https://github.com/rnewman/clj-mql/tree/master>


# Building with Ant #

Invoke `ant`, optionally passing `-Dclojure.jar="..."` and `-Dclojure.contrib.jar="..."`.

Put `clj-apache-http.jar` on your classpath.

# Building with Leiningen #

If you use Leiningen, run `lein uberjar`. This will download the necessary
dependencies and build a single .jar named `clj-apache-http-standalone.jar`.

You can also refer to `com.twinql.clojure/clj-apache-http "2.2.0"` in Leiningen
or Maven to have the dependency automatically satisfied.

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
* The response headers (also subject to transformation): `:headers`.

Typically most of these can be ignored; `:code` and `:content` are the most important fields.

Note that minimal processing is applied to these results (you don't pay for
what you don't use).

You can specify the format in which you wish to receive body content and
headers using the `:as` and `:headers-as` keyword arguments. See below for
details.

# Query parameters *

Query parameters (as supplied to the `:query` argument) should be associative:
either a map or a sequence of pairs. Parameters will be processed with
`as-str`. Non-sequential values will also be processed with `as-str`;
sequential values (such as vectors) will be turned into multiple query
parameters, as expected by most HTTP servers. For example:

    (encode-query {:foo "bar" :baz ["noo" 5 true] :top {:x 5 :y 7}})
    =>
    "foo=bar&baz=noo&baz=5&baz=true&top=%7B%3Ax+5%2C+%3Ay+7%7D"


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

    (:headers (http/get "http://google.com/search" :query {:q "frobnosticate"} :headers-as :map))
    =>
    {"Transfer-Encoding" ["chunked"],
     "Server" ["gws"],
     "Set-Cookie"
     ["SS=Q0=ZnJvYm5vc3RpY2F0ZQ; path=/search"
      ["PREF=ID=9174bb4f419f1279:TM=1248028028:LM=1248028028:S=-Nxn6QHqif9SORnQ; expires=Tue, 19-Jul-2011 18:27:08 GMT; path=/; domain=.google.com"]
      ["NID=24=E9wDEpOrfSFh2bt36RkK7ZIkjH78DeKeA1mulw1A5562byJ2ngJjDPClEHUceb-6ewf7ANSrA-6CrXjUfeHszmv3OM7giddIDfX-RBvtZGYIWI0FbUNbYvoKQXtQRu9S; expires=Mon, 18-Jan-2010 18:27:08 GMT; path=/; domain=.google.com; HttpOnly"]],
     "Content-Type" ["text/html; charset=ISO-8859-1"],
     "Expires" ["-1"],
     "Date" ["Sun, 19 Jul 2009 18:27:08 GMT"],
     "Cache-Control" ["private, max-age=0"]}



# Keyword parameters #

You can use `:query`, `:headers`, `:parameters`, `:as`, and `:headers-as`.

The first three are associative. `:as` can be:

* `:identity` (or `nil`), returning the Apache HC entity object,
* `:stream`, returning a stream,
* `:reader`, returning a `Reader`,
* `:json`, which will parse a JSON response body and return the keys of maps as keywords,
* `:json-string-keys`, which leaves maps with string keys,
* or `:string`.

`:headers-as` can be

* `:identity`, returning a `HeaderIterator`,
* `:seq` (or `nil`), returning a sequence of [header, value] pairs,
* `:element-seq`, returning a sequence of [header, `Element[]`] pairs,
* `:map`, returning a map from header name to vector of values, or
* `:element-map`, returning a map from header name to vector of `Element[]`.

Define your own extensions by defining a method on '`entity-as`' that turns an
`HttpEntity` into the appropriate format, or '`headers-as`' that turns a
`HeaderIterator` into the format of your choice.

Each entity transformer receives the entity, the format, and the status code as arguments: you can thus avoid trying to process certain failure responses. For example:

    (defmethod http/entity-as :success-json [#^HttpEntity entity as status]
      (http/entity-as entity
        (if (<= 200 status 299)
         :json
         :string)
        status))


# Apache connection parameters #
You can pass a parameter map to the HTTP functions. This is used to set various
options on the HTTP client.

The keys are long-winded Java constants, but the capability is very useful
(e.g., for proxying). See

<http://hc.apache.org/httpcomponents-client-4.0.1/httpclient/apidocs/org/apache/http/client/params/AllClientPNames.html>

To avoid verbosity, a function `map->params` is provided. This will rename the keys in your input to the appropriate constants.

For example, to issue a `HEAD` request via a proxy:

    (http/head "http://github.com/"
      :parameters (http/map->params
                    {:default-proxy (http/http-host :host "localhost" :port 8080)}))

# Optimization #

If you're planning intensive use of HTTP operations, you might want more
control over the connection manager (the component which maintains a pool of
connections).

`clj-apache-http` exposes one utility, `thread-safe-connection-manager`. It can
be called with no arguments, or with a `SchemeRegistry`.

Simply hold on to one of these, then pass it as the value of
`:connection-manager`, as in this example:

    (let [ccm (http/thread-safe-connection-manager)]
      (println "Result:"
        (:code (http/get "http://github.com" :as :string
                         :connection-manager ccm)))
      (http/shutdown-connection-manager ccm))

Don't forget to call `shutdown-connection-manager` afterwards. The
`with-connection-manager` macro does this for you, if your uses will all be
within the same lexical scope:

    (http/with-connection-manager [ccm :thread-safe]
      (println "Result:"
        (:code (http/get "http://github.com" :as :string
                         :connection-manager ccm))))

