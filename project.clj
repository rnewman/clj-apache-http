(defproject diamondap/clj-apache-https "1.0.15-SNAPSHOT"
  :description "Clojure HTTP library using the Apache HttpClient. Based on clj-apache-http, but includes support for SSL client certificates and HttpAsyncClient."

  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.apache.httpcomponents/httpcore "4.1.1"]
                 [org.apache.httpcomponents/httpmime "4.1.1"]
                 [commons-logging/commons-logging "1.1.1"]
                 [org.apache.httpcomponents/httpclient "4.1.1"]
                 [org.apache.httpcomponents/httpasyncclient "4.0-alpha2"]]
  :dev-dependencies [[swank-clojure "1.3.0-SNAPSHOT"]]
  :repositories {"releases" ~(str "file://"
                                  user/local-maven-clone "/releases")
                 "snapshots" ~(str "file://"
                                   user/local-maven-clone "/snapshots")})

