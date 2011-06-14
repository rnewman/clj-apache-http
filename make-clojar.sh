lein compile com.twinql.clojure.NaiveTrustManager
lein compile com.twinql.clojure.PermissiveHostnameVerifier
lein jar clj-apache-https-1.0.2.jar
jar uf clj-apache-https-1.0.2.jar classes/com/twinql/clojure/*
lein pom
echo 'scp pom.xml clj-apache-https-1.0.2.jar clojars@clojars.org:'
