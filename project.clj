(defproject diamondap/clj-apache-https "1.0.18"
  :description "Clojure HTTP library using the Apache HttpClient. Based on clj-apache-http, but includes support for SSL client certificates and HttpAsyncClient."

  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/data.json "0.1.1"]
                 [org.apache.httpcomponents/httpcore "4.1.1"]
                 [org.apache.httpcomponents/httpmime "4.1.1"]
                 [commons-logging/commons-logging "1.1.1"]
                 [org.apache.httpcomponents/httpclient "4.1.1"]
                 [org.apache.httpcomponents/httpasyncclient "4.0-alpha2"]
                 [commons-codec "1.5"]]
  :dev-dependencies [[swank-clojure "1.3.0-SNAPSHOT"]]
  :repositories {"releases" ~(str "file://"
                                  user/local-maven-clone "/releases")
                 "snapshots" ~(str "file://"
                                   user/local-maven-clone "/snapshots")
                 "hotelicopter_snapshots" "https://raw.github.com/g1nn13/maven/master/snapshots"
                 "hotelicopter_releases" "https://raw.github.com/g1nn13/maven/master/releases"})

