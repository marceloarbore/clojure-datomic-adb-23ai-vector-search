(defproject clojure-datomic-adb-23ai-vector-search "0.1.0-SNAPSHOT"
  :description "Clojure microservice demo combining Datomic SQL storage on Oracle with Oracle ADB vector search"
  :url "https://github.com/yourusername/clojure-datomic-adb-23ai-vector-search"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [com.oracle.database.jdbc/ojdbc17 "23.9.0.25.07"]
                 [com.oracle.database.jdbc/ucp17 "23.9.0.25.07"]
                 [io.pedestal/pedestal.jetty "0.7.1"]
                 [scicloj/tablecloth "7.062"]
                 [org.slf4j/slf4j-simple "2.0.9"]
                 [hiccup/hiccup "2.0.0-RC3"]
                 [com.datomic/peer "1.0.7277"]
                 [com.datomic/client-pro "1.0.81"]
                 [dev.langchain4j/langchain4j "1.3.0"]
                 [dev.langchain4j/langchain4j-oracle "1.3.0-beta9"]
                 [dev.langchain4j/langchain4j-open-ai "1.3.0"]
                 [dev.langchain4j/langchain4j-embeddings-all-minilm-l6-v2-q "1.3.0-beta9"]
                 [org.clojure/data.json "2.4.0"]]
  :repositories [["my.datomic.com" "https://my.datomic.com/repo"]]
  :main ^:skip-aot clojure-datomic-adb-23ai-vector-search.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})

