lein compile com.twinql.clojure.NaiveTrustManager
lein jar clj-apache-https-1.0.18.jar
jar uf clj-apache-https-1.0.18.jar classes/com/twinql/clojure/*
lein pom
echo 'scp pom.xml clj-apache-https-1.0.18.jar clojars@clojars.org:'
