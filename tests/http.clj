(ns tests
  (:refer-clojure)
  (:require [com.twinql.clojure.http :as http])
  (:use clojure.test)
  (:import
     (java.net URI)))

(deftest test-resolve
         (are [x y] (= x y)
              
              (URI. "http://foo.com/bar?baz=5")
              (http/resolve-uri "http://foo.com/bar" {:baz 5})
              
              (URI. "http://foo.com/bar/?baz=1&noo=2&oz=bar#zam")
              (http/resolve-uri "http://foo.com/bar/#zam" [[:baz 1] [:noo 2] ["oz" "bar"]])
              
              (URI. "http://bar:8080/?foo=noo#zap")
              (http/resolve-uri {:host "bar" :port 8080 :query {:foo :noo} :fragment "zap"} {})
              
              (URI. "http://bar:8080/?foo=1&tar=bar")
              (http/resolve-uri (URI. "http://bar:8080/?foo=1")
                                {:tar "bar"})))

(clojure.test/run-tests)
