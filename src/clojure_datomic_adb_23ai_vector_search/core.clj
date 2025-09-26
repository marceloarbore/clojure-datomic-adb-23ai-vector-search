(ns clojure-datomic-adb-23ai-vector-search.core
  (:gen-class)
  (:require [io.pedestal.http :as http]
            [clojure-datomic-adb-23ai-vector-search.server :as server]
            [clojure-datomic-adb-23ai-vector-search.dbconf :as dbconf]))




;; build-system!
;; Objective: assemble and start all external resources (Datomic, Oracle, HTTP server).
;; Params: overrides (optional config overrides)
;; Flow: resolve config, build URIs, ensure Datomic DB, connect, build Oracle pool + store, build routes + service, start server
;; Purity: impure (allocates external resources, starts server)
;; Return: system map with resources for shutdown
(defn build-system!
  ([] (build-system! {}))
  ([overrides]
   (let [cfg         (dbconf/make-config overrides)
         {:keys [jdbc-url datomic-uri]} (dbconf/build-uris cfg)
         _           (dbconf/ensure-datomic-db-exists! datomic-uri)
         conn        (dbconf/connect-datomic! datomic-uri)
         pds         (dbconf/oracle-pool-data-source!
                      {:jdbc-url jdbc-url
                       :db-username (:db-username cfg)
                       :db-password (:db-password cfg)
                       :initial-pool-size 10})
         store       (dbconf/oracle-embedding-store! pds (:datomic-db-name cfg))
;;         gen-data    (dbconf/populate-datomic-oracle-db! conn store (dbconf/make-food-dataset 50)) ;; somente usar se precisar recriar/popular o banco datomic + embeddings (oracle)
         routes      (server/make-routes {:conn-datomic conn
                                          :oracle-embedding-store store})
         service     (server/make-service {:routes routes :port 8080 :env :prod})
         http        (server/start-server! service)]
     {:config cfg :uris {:jdbc jdbc-url :datomic datomic-uri}
      :conn conn :pds pds :store store :http http})))

;; stop-system!
;; Objective: gracefully stop the HTTP server and close external resources.
;; Params: system map from build-system!
;; Flow: stop http server and close any resources that support close
;; Purity: impure
;; Return: nil
(defn stop-system!
  [{:keys [http pds] :as _system}]
  (when http (http/stop http))
  (when (instance? java.io.Closeable pds)
    (.close ^java.io.Closeable pds))
  nil)




(defn -main
  "The entry-point for 'lein run'. Params: _args. Flow: build system, add shutdown hook, block main thread. Return: nil. Objective: start the app and keep it running."
  [& _args]
  (println "\n[core] start - Oracle for Developers!!")
  (println "\n[core] Building system and starting HTTP server...")
  (let [system (build-system!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (println "[core] Shutting down...")
                                    (stop-system! system)
                                    (println "[core] Shutdown complete"))))
    (println (str "[core] Server started on port " (get-in system [:config :port] 8080)))
    @(promise)))








