(ns clojure-datomic-adb-23ai-vector-search.dbconf
  (:require [datomic.api :as d]
            [tablecloth.api :as tc])
  (:import
   [java.util Properties]
   [dev.langchain4j.data.segment TextSegment]
   [dev.langchain4j.model.embedding.onnx.allminilml6v2q AllMiniLmL6V2QuantizedEmbeddingModel]
   [dev.langchain4j.store.embedding.oracle OracleEmbeddingStore]
   [dev.langchain4j.store.embedding.oracle CreateOption]
   [dev.langchain4j.store.embedding EmbeddingSearchRequest]
   [oracle.ucp.jdbc PoolDataSourceFactory]))

;; =============================================================================
;; Configuration and URI builders (pure where possible)
;; =============================================================================

;; make-config
;; Objective: build a configuration map from env with optional overrides.
;; Params: optional overrides map {:db-service :db-username :db-password :tns-admin :datomic-db-name}
;; Flow: merges overrides onto env or sensible defaults to produce a config map
;; Purity: reads env (impure); keep call site at the edge (e.g., main)
;; Return: map with configuration keys
(defn make-config
  ([] (make-config {}))
  ([overrides]
   (merge {:db-service      (or (System/getenv "TNS_SERVICE") "ladtestazure_tp")
           :db-username     (or (System/getenv "ORACLE_DB_USER") "datomic")
           :db-password     (or (System/getenv "ORACLE_DB_PASSWORD") "Oracle4Developers")
           :tns-admin       (or (System/getenv "TNS_ADMIN") "C:/marbore/Oracle/code/Wallet_ladtestazure")
           :datomic-db-name (or (System/getenv "DATOMIC_DB_NAME") "foodsid300")}
          overrides)))

;; build-uris
;; Objective: derive JDBC and Datomic URIs from config.
;; Params: cfg map from make-config
;; Flow: construct JDBC URL and Datomic SQL storage URI from config
;; Purity: pure
;; Return: {:jdbc-url string :datomic-uri string}
(defn build-uris
  [{:keys [db-service db-username db-password tns-admin datomic-db-name]}]
  {:jdbc-url   (str "jdbc:oracle:thin:@" db-service "?TNS_ADMIN=" tns-admin)
   :datomic-uri (str "datomic:sql://" datomic-db-name "?jdbc:oracle:thin:" db-username "/" db-password "@" db-service "?TNS_ADMIN=" tns-admin)})


;; =============================================================================
;; Connection and Connection Pool Configuration
;; =============================================================================


;; oracle-pool-data-source!
;; Objective: create and configure an Oracle UCP pooled data source.
;; Params: {:jdbc-url string :db-username string :db-password string :initial-pool-size int}
;; Flow: construct and configure an Oracle UCP PoolDataSource
;; Purity: impure (allocates resources)
;; Return: PoolDataSource
(defn oracle-pool-data-source!
  [{:keys [jdbc-url db-username db-password initial-pool-size]
    :or   {initial-pool-size 10}}]
  (let [pds (PoolDataSourceFactory/getPoolDataSource)
        props (doto (Properties.)
                (.setProperty "oracle.jdbc.vectorDefaultGetObjectType" "String"))]
    (doto pds
      (.setConnectionFactoryClassName "oracle.jdbc.pool.OracleDataSource")
      (.setURL jdbc-url)
      (.setUser db-username)
      (.setPassword db-password)
      (.setConnectionProperties props)
      (.setInitialPoolSize (int initial-pool-size)))))


;; =============================================================================
;; Oracle Embedding Store Creation
;; =============================================================================

;; oracle-embedding-store!
;; Objective: build an OracleEmbeddingStore backed by the provided data source.
;; Params: pds (PoolDataSource), table-name (string)
;; Flow: build an OracleEmbeddingStore; may create table if not exists
;; Purity: impure (may perform DDL via CreateOption)
;; Return: OracleEmbeddingStore
(defn oracle-embedding-store!
  [pds table-name]
  (-> (OracleEmbeddingStore/builder)
      (.dataSource pds)
      (.embeddingTable table-name CreateOption/CREATE_IF_NOT_EXISTS)
      (.build)))



;; =============================================================================
;; Embeding model - AllMiniLmL6V2EmbeddingModel
;; =============================================================================

;; Create an instance of the embedding model, which can calculate an embedding for a piece of text.
(def embedding-model (AllMiniLmL6V2QuantizedEmbeddingModel.))


;; =============================================================================
;; Vector-Store Operations
;; =============================================================================

;; insert-vector-store!
;; Objective: insert an embedded representation of a food item into the vector store.
;; Params: embedding-store (OracleEmbeddingStore), food-item {:food/id :food/description}
;; Flow: embed description, add embedding associated with id
;; Purity: impure (I/O with Oracle)
;; Return: the food id inserted
(defn insert-vector-store!
  [embedding-store food-item]
  (let [food-id (:food/id food-item)
        description (:food/description food-item)
        text (TextSegment/from description)
        text-id (TextSegment/from (str food-id))
        embedding (.content (.embed embedding-model text))]
    (.add embedding-store embedding text-id)
    food-id))

;; search-top-5-vector-store
;; Objective: search the vector store and return the top 5 matches for a query.
;; Params: embedding-store (OracleEmbeddingStore), query-text (string)
;; Flow: embed query, perform vector search, shape top 5 matches
;; Purity: impure (calls embedding model + store)
;; Return: vector of {:score double :text string}
(defn search-top-5-vector-store
  [embedding-store query-text]
  (let [query-embedding (.content (.embed embedding-model query-text))
        req (.. (EmbeddingSearchRequest/builder)
                (queryEmbedding query-embedding)
                (maxResults (int 5))
                (minScore 0.0)
                build)
        matches (.matches (.search embedding-store req))]
    (mapv (fn [m]
            {:score (.score m)
             :text  (.text (.embedded m))})
          matches)))


;; =============================================================================
;; Datomic Database Operations
;; =============================================================================

;; insert-datomic!
;; Objective: write a single entity to the Datomic database.
;; Params: conn (Datomic connection), food-item tx map
;; Flow: transact a single entity
;; Purity: impure (writes to Datomic)
;; Return: tx-report
;;(defn insert-datomic!
;;  [conn food-item]
;;  @(d/transact conn [food-item]))


;; search-datomic-by-id
;; Objective: hydrate vector store result ids from Datomic and attach scores.
;; Params: conn (Datomic connection), vector-results [{:text id-string :score double}]
;; Flow: build id->score map, query entities by ids, merge score
;; Purity: impure (reads from Datomic), pure data shaping
;; Return: vector of {:id :name :description :score}
(defn search-datomic-by-id
  [conn vector-results]
  (let [db (d/db conn)
        id->score (into {}
                        (map (fn [{:keys [text score]}]
                               [(Long/parseLong text) score])
                             vector-results))
        ids (keys id->score)
        results (d/q '[:find ?id ?name ?description
                       :in $ [?id ...]
                       :where
                       [?e :food/id ?id]
                       [?e :food/name ?name]
                       [?e :food/description ?description]]
                     db ids)]
    (mapv (fn [[id name description]]
            {:id id :name name :description description :score (get id->score id)})
          results)))


;; =============================================================================
;; Datomic Database Initialization
;; =============================================================================


;; make-food-descriptions
;; Objective: generate a dataset of random Brazilian food descriptions.
;; Params: n (int)
;; Flow: generate random Brazilian food descriptions dataset
;; Purity: impure due to randomness; pass a seed if determinism is required
;; Return: tablecloth dataset {:food-id :food-name :food-description}
(defn make-food-dataset
  [n]
  (let [food-items ["feijoada" "arroz com feijão" "farofa" "macarronada" "lasanha" "frango assado"
                    "bife acebolado" "estrogonofe" "moqueca" "escondidinho" "arroz carreteiro"
                    "galinhada" "dobradinha" "picadinho" "rabada" "moela" "carne de panela"
                    "arroz de forno" "salada de maionese" "tutu de feijão" "cuscuz" "empadão"
                    "pão de queijo" "pastel" "coxinha" "quibe" "esfiha" "torta salgada"
                    "bolinho de chuva" "bolinho de bacalhau" "arroz de frango" "arroz de carne"
                    "arroz de legumes" "arroz de camarão" "arroz de peixe" "arroz de marisco"
                    "arroz de polvo" "arroz tropeiro" "frango com quiabo" "carne louca"
                    "costela assada" "linguiça assada" "baião de dois" "carne de sol"
                    "moqueca de peixe" "moqueca de camarão" "bobó de camarão" "bobó de frango"
                    "torta de frango" "torta de legumes" "torta de sardinha" "empada de frango"
                    "empada de palmito" "empada de queijo" "empada de carne" "empada de camarão"
                    "empadão de frango" "empadão de carne seca" "empadão de queijo"
                    "empadão de milho" "empadão de palmito" "empadão de camarão"
                    "empadão de bacalhau" "empadão de frango com catupiry" "empadão de frango com milho"
                    "empadão de frango com queijo" "empadão de frango com presunto"
                    "empadão de frango com bacon" "empadão de frango com ervilha"
                    "empadão de frango com cenoura" "empadão de frango com batata"
                    "empadão de frango com tomate" "empadão de frango com cebola"
                    "empadão de frango com pimentão" "empadão de frango com azeitona"
                    "empadão de frango com requeijão" "empadão de frango com creme de leite"
                    "empadão de frango com milho verde" "empadão de frango com ervas"
                    "empadão de frango com curry" "empadão de frango com mostarda"
                    "empadão de frango com maionese"]
        adjectives ["delicioso(a)" "apetitoso(a)" "saboroso(a)" "divino(a)" "excelente(a)"
                    "suculento(a)" "temperado(a)" "cremoso(a)" "perfeito(a)" "esplêndido(a)"
                    "delicioso(a)" "apetitoso(a)" "saboroso(a)" "divino(a)" "excelente(a)"
                    "suculento(a)" "temperado(a)" "cremoso(a)" "perfeito(a)" "esplêndido(a)"]
        origins ["da receita da vovó" "que vem sendo guardada por gerações"
                 "do caderno de receitas da família" "com segredos passados de mãe para filha"
                 "inspirada na mesa de domingo" "dos tempos em que tudo era feito à mão"
                 "do livro de receitas amarelado" "de um bilhete antigo na cozinha"
                 "criada com carinho artesanal" "aperfeiçoada ao longo dos anos"
                 "da tradição de família" "da cozinha afetiva" "do forno da casa da infância"
                 "de encontros de família" "da festa de aniversário de antigamente"
                 "de um ritual de domingo" "de um costume antigo" "de uma lembrança carinhosa"
                 "de uma história contada à mesa" "de uma herança culinária" "da sabedoria popular"
                 "do modo caseiro" "com jeitinho de casa" "com jeito de casa de avó"
                 "com alma artesanal" "com carinho de mãe" "com capricho artesanal"
                 "com sabor de infância" "com memória afetiva" "com afeto em cada passo"]
        methods ["cozido(a) lentamente" "assado(a) no forno a lenha" "grelhado(a) na chapa" "grelhado(a) à perfeição"
                 "assado(a) no forno" "cozido(a) no vapor suavemente" "frito(a) rapidamente" "cozido(a) em fogo brando"
                 "assado lentamente" "preparado artesanalmente"]
        ingredients ["azeite de dendê" "farinha de mandioca" "tucupi" "castanha-do-pará"
                     "coco ralado" "pimenta-de-cheiro" "pequi"
                     "queijo coalho" "feijão preto" "banana-da-terra"
                     "camarão seco" "erva-mate" "umbu" "buriti"
                     "carne de sol" "pimentão" "alho" "cebola" "cheiro-verde" "coentro" "salsinha"]
        effects ["que deleita os sentidos" "que derrete na boca"
                 "que deixa você querendo mais" "que é um banquete para os olhos"
                 "que aquece a alma" "que explode de sabor"
                 "que traz conforto e alegria" "que excita o paladar"
                 "que encanta cada papila gustativa" "que é puro prazer"]
        food-data (map-indexed (fn [idx _]
                                 (let [food        (rand-nth food-items)
                                       adj         (rand-nth adjectives)
                                       origin      (rand-nth origins)
                                       method      (rand-nth methods)
                                       ingredient  (rand-nth ingredients)
                                       effect      (rand-nth effects)
                                       description (str "Um(a) " adj " " food ", " origin ", "
                                                        method " e feito(a) com " ingredient ", "
                                                        effect ".")]
                                   {:food-id (inc idx)
                                    :food-name food
                                    :food-description description}))
                               (range n))]
    (tc/dataset {:food-id          (map :food-id food-data)
                 :food-name        (map :food-name food-data)
                 :food-description (map :food-description food-data)})))

;; make-food-schema
;; Objective: define the Datomic schema for food entities.
;; Params: none
;; Flow: returns Datomic tx data for food schema
;; Purity: pure
;; Return: vector of tx maps
(defn make-food-schema
  []
  [{:db/ident :food/id
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "ID do prato"}
   {:db/ident :food/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Nome do prato"}
   {:db/ident :food/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Descrição detalhada do prato"}])

;; populate-datomic-oracle-db!
;; Objective: populate Datomic and the Oracle vector store with food data.
;; Params: conn (Datomic connection), embedding-store, ds (dataset with columns :food-id :food-name :food-description)
;; Flow: transact schema, transact data in batches, add embeddings per item
;; Purity: impure (writes to Datomic and Oracle)
;; Return: summary map {:inserted n}
(defn populate-datomic-oracle-db!
  [conn embedding-store ds]
  @(d/transact conn (make-food-schema))
  (let [rows (count (:food-name ds))
        food-data (map (fn [idx]
                         {:db/id (d/tempid :db.part/user)
                          :food/id (nth (:food-id ds) idx)
                          :food/name (nth (:food-name ds) idx)
                          :food/description (nth (:food-description ds) idx)})
                       (range rows))]
    (doseq [chunk (partition-all 100 food-data)]
      @(d/transact conn chunk)
      (doseq [food-item chunk]
        (insert-vector-store! embedding-store food-item)))
    {:inserted rows}))


;; =============================================================================
;; Combined Query Functions
;; =============================================================================

;; query-top5-food-items
;; Objective: return the top 5 food items for a query combining vector search and Datomic.
;; Params: oracle-es (OracleEmbeddingStore), conn (Datomic connection), query-text (string)
;; Flow: search vector store, hydrate from Datomic, sort by score descending
;; Purity: impure (reads from external systems)
;; Return: vector of {:id :name :description :score}
(defn query-top5-food-items
  [oracle-es conn query-text]
  (let [vector-results (search-top-5-vector-store oracle-es query-text)
        datomic-results (search-datomic-by-id conn vector-results)]
    (sort-by :score > datomic-results)))

;; ensure-datomic-db-exists!
;; Objective: ensure the Datomic database exists (create if missing).
;; Params: datomic-uri (string)
;; Flow: attempts to create database; no-op if it already exists
;; Purity: impure
;; Return: true if created or already exists
(defn ensure-datomic-db-exists!
  [datomic-uri]
  (try
    (d/create-database datomic-uri)
    true
    (catch Exception _e
      true)))

;; connect-datomic!
;; Objective: open a connection to the Datomic database.
;; Params: datomic-uri (string)
;; Flow: connects to Datomic
;; Purity: impure
;; Return: Datomic connection
(defn connect-datomic!
  [datomic-uri]
  (d/connect datomic-uri))

;; =============================================================================
;; Example Usage
;; =============================================================================
(comment
  ;; =============================================================================
  ;; Complete Workflow Example: Oracle + Datomic + Vector Store
  ;; =============================================================================

  ;; 1. Setup connections  
  (let [cfg (make-config)
        {:keys [jdbc-url datomic-uri]} (build-uris cfg)
        _ (ensure-datomic-db-exists! datomic-uri)
        conn-datomic (connect-datomic! datomic-uri)
        pds (oracle-pool-data-source! {:jdbc-url jdbc-url
                                       :db-username (:db-username cfg)
                                       :db-password (:db-password cfg)})
        oracle-es (oracle-embedding-store! pds (:datomic-db-name cfg))]

    ;; 2. Initialize and populate databases
    (populate-datomic-oracle-db! conn-datomic oracle-es (make-food-dataset 1000))

    ;; 3. Query examples
    (let [db (d/db conn-datomic)
          results (d/q '[:find ?name ?description
                         :where
                         [?e :food/name ?name]
                         [?e :food/description ?description]] db)]
      (println "Query results:" (take 3 results)))

    ;; Count all food descriptions in Datomic database
    (let [db (d/db conn-datomic)
          count-result (d/q '[:find (count ?e)
                              :where
                              [?e :food/description]] db)]
      (println "Total food descriptions:" (first count-result)))

    ;; 4. Vector store search
    (let [query-text "comida fresca com sabores de minas gerais"
          search-results (search-top-5-vector-store oracle-es query-text)]
      (doseq [result search-results]
        (println "  Score:" (:score result) "| Text:" (:text result))))

    ;; 5. Combined search (vector + datomic)
    (query-top5-food-items oracle-es conn-datomic "comidas de são paulo")

    ;; 6. Cleanup
    (.close pds)
    (d/release conn-datomic)
    (println "Connections closed successfully")))