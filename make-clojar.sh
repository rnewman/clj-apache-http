lein compile com.twinql.clojure.NaiveTrustManager
lein jar clj-apache-https-1.0.17.jar
jar uf clj-apache-https-1.0.17.jar classes/com/twinql/clojure/*
lein pom
echo 'scp pom.xml clj-apache-https-1.0.17.jar clojars@clojars.org:'
