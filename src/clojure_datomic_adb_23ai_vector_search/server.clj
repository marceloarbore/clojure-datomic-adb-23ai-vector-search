(ns clojure-datomic-adb-23ai-vector-search.server
  (:require [io.pedestal.http :as http]
            [clojure-datomic-adb-23ai-vector-search.dbconf :as dbconf]
            [clojure.data.json :as json]))



;; -----------------------------------------------------------------------------
;; Handlers (pure where possible) and server wiring with explicit dependencies
;; -----------------------------------------------------------------------------

;; hello-world
;; Objective: return a simple greeting HTTP response.
;; Params: request (Ring request map)
;; Flow: extract optional name parameter and format greeting string
;; Purity: pure (no side-effects)
;; Return: Ring response map {:status int :body string}
(defn hello-world
  [request]
  (let [name (get-in request [:params :name] "World")]
    {:status 200 :body (str "Hello " name "!\n")}))

;; question-5-likely-foods-handler
;; Objective: build a handler that returns top-5 foods as JSON based on query text.
;; Params: deps {:conn-datomic Conn, :oracle-embedding-store OracleEmbeddingStore}
;; Flow: returns a Ring handler that validates :text, queries vector store, then Datomic, and serializes JSON
;; Purity: returns a pure function; inner function has no side-effects assuming deps are valid resources
;; Return: Ring handler (request -> response)
(defn question-5-likely-foods-handler
  [{:keys [conn-datomic oracle-embedding-store]}]
  (fn [request]
    (try
      (let [query-text (get-in request [:params :text])
            _ (when (nil? query-text)
                (throw (ex-info "Adicione o param text com uma pergunta sobre a sua comida favorita"
                                {:type :bad-request})))
            results (dbconf/query-top5-food-items oracle-embedding-store conn-datomic query-text)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:query query-text
                                :results results
                                :count (count results)})})
      (catch Exception e
        (let [status (if (= (:type (ex-data e)) :bad-request) 400 500)]
          {:status status
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error (.getMessage e)})})))))

;; make-routes
;; Objective: assemble the Pedestal route table from injected dependencies.
;; Params: deps {:conn-datomic Conn, :oracle-embedding-store OracleEmbeddingStore}
;; Flow: build route table using injected dependencies for handlers
;; Purity: pure (returns data structure)
;; Return: set of Pedestal route vectors
(defn make-routes
  [deps]
  #{["/greet" :get hello-world :route-name :greet]
    ["/food"  :get (question-5-likely-foods-handler deps) :route-name :food-recommendations]})

;; make-service
;; Objective: construct a Pedestal service map given routes and server settings.
;; Params: {:routes route-set, :port int, :env keyword}
;; Flow: construct Pedestal service map from inputs
;; Purity: pure
;; Return: service map suitable for http/create-server
(defn make-service
  [{:keys [routes port env]
    :or   {port 8080 env :prod}}]
  {:env                 env
   ::http/routes        routes
   ::http/resource-path "/public"
   ::http/type          :jetty
   ::http/port          port})

;; start-server!
;; Objective: start the Pedestal HTTP server.
;; Params: service-map
;; Flow: create and start Pedestal server
;; Purity: impure (starts external server)
;; Return: running server instance
(defn start-server!
  [service]
  (let [srv (http/create-server service)]
    (http/start srv)))

