# clojure-datomic-adb-23ai-vector-search

## What this demo is

This microservice receives a question about food and returns the 5 most-likely foods from the datomic db (based on similarity search from Oracle Autonomous 23ai).

Example question: "Gostaria de sugestões de comidas baianas"

Anwser: 
1) Um(a) delicioso(a) moqueca, do litoral baiano, preparado artesanalmente e feito(a) com azeite de dendê, que aquece a alma
2) Um(a) saboroso(a) vatapá, do recôncavo baiano, cozido(a) lentamente e feito(a) com coco ralado, que derrete na boca
3) Um(a) crocante acarajé, típico da Bahia, frito(a) no azeite de dendê e recheado(a) com vatapá.
4) Um(a) tradicional caruru, feito(a) com quiabo e camarão, temperado(a) com azeite de dendê.
5) Um(a) cremoso(a) bobó de camarão, preparado(a) com mandioca e azeite de dendê, típico da culinária baiana.

This is a small, end‑to‑end demo of a Clojure microservice that combines:
- Clojure + Pedestal (HTTP)
- Datomic using SQL storage on Oracle
- Oracle Autonomous Database Vector Search (LangChain4j `OracleEmbeddingStore`)

Endpoints (open in your browser):
- `/greet?name=World` → simple greeting (check service is alive)
- `/food?text=your+query` → JSON with top‑5 food matches `{id,name,description,score}`

### What you’ll see
- How an HTTP handler calls a vector search on Oracle, then hydrates results from Datomic
- Clean separation of pure functions and side‑effects, and explicit dependency injection

## Quick start

1) Start the service
```bash
lein run
```

2) Open in your browser
- Greeting: `http://localhost:8080/greet?name=World` (check service is alive)
- Food search (Portuguese): `http://localhost:8080/food?text=comida baiana com dende"`
- Food search (English): `http://localhost:8080/food?text="Brazilian seafood with dende"`

3) Microservice output
- Greeting: a plain text “Hello World!” (service is alive)
- Food search (example)
```json
{
  "query": "comida baiana com dende",
  "results": [
    { "id": 17, "name": "moqueca", "description": "Um(a) delicioso(a) moqueca, do litoral baiano, preparado artesanalmente e feito(a) com azeite de dendê, que aquece a alma.", "score": 0.86 },
    { "id": 203, "name": "vatapá",  "description": "Um(a) saboroso(a) vatapá, do recôncavo baiano, cozido(a) lentamente e feito(a) com coco ralado, que derrete na boca.", "score": 0.81 },
    { "id": 42, "name": "acarajé", "description": "Um(a) crocante acarajé, típico da Bahia, frito(a) no azeite de dendê e recheado(a) com vatapá.", "score": 0.78 },
    { "id": 58, "name": "caruru", "description": "Um(a) tradicional caruru, feito(a) com quiabo e camarão, temperado(a) com azeite de dendê.", "score": 0.75 },
    { "id": 99, "name": "bobó de camarão", "description": "Um(a) cremoso(a) bobó de camarão, preparado(a) com mandioca e azeite de dendê, típico da culinária baiana.", "score": 0.73 }
  ],
  "count": 5
}
```

## How to initialize it

### Prerequisites
You need to set up two databases before running this demo:

1. **Oracle Autonomous Database 23ai with Vector Search enabled**
   - Create an Oracle Autonomous Database 23ai instance (OCI cloud)
   - Ensure vector search is enabled (this feature comes pre-configured in 23ai)
   - Download wallet files from your ADB console
   - Oracle Instant Client + ADB Wallet (set `TNS_ADMIN` to the wallet directory)

2. **Datomic Database (SQL storage on Oracle)**
   - Install and configure Datomic Transactor
   - Configure Datomic to use Oracle as the SQL storage backend
   - Ensure Datomic can connect to your Oracle 23ai database
   - Note: The demo will automatically create the Datomic database schema

- Java 17+, Leiningen

### Environment Setup
Set these environment variables (PowerShell example):
```powershell
$env:TNS_SERVICE="<tns_service>"
$env:ORACLE_DB_USER="<oracle_db_user>"
$env:ORACLE_DB_PASSWORD="<oracle_db_pw>"
$env:TNS_ADMIN="<wallet_folder>"
$env:DATOMIC_DB_NAME="<datomic_db_name>"
```

### Database Preparation
Before running the demo, you need to prepare both Datomic and Oracle databases:

**Note on Data Generation:** 
This demo uses an automated data generation function (`make-food-dataset`) that creates 200 Brazilian food items with rich descriptions. The generated data includes:
- Traditional Brazilian food names
- Detailed descriptions with cooking methods, ingredients, and cultural context
- Vector embeddings stored in Oracle for similarity search
- Metadata stored in Datomic for result hydration

If you want to use your own data instead, you can modify the dataset generation or replace the populate calls.

1. **Install dependencies and start REPL:**
```bash
lein deps
lein repl
```

2. **Run the database setup script:**
```clojure
(require '[clojure-datomic-adb-23ai-vector-search.dbconf :as db])

;; Create configuration
(def cfg (db/make-config))
(def uris (db/build-uris cfg))

;; Ensure Datomic database exists
(db/ensure-datomic-db-exists! (:datomic-uri uris))

;; Connect to Datomic
(def conn (db/connect-datomic! (:datomic-uri uris)))

;; Create Oracle connection pool
(def pds (db/oracle-pool-data-source! {:jdbc-url (:jdbc-url uris)
                                       :db-username (:db-username cfg)
                                       :db-password (:db-password cfg)}))

;; Create Oracle embedding store (creates vector table if needed)
(def store (db/oracle-embedding-store! pds (:datomic-db-name cfg)))

;; Populate with demo data (200 Brazilian food items)
(db/populate-datomic-oracle-db! conn store (db/make-food-descriptions 200))

;; Verify setup
(println "Setup complete! Database contains" 
         (count (db/q '[:find (count ?e) :where [?e :food/name]] (db/db conn))) 
         "food items")
```

3. **Exit REPL and start the service:**
```bash
lein run
```

## How it works (very short)
- `/food` handler embeds your query, searches Oracle Vector Store, gets top matches
- It looks up details in Datomic (stored on Oracle via SQL storage)
- Returns merged results as JSON

## Troubleshooting
- `TNS_ADMIN` must point to a valid Oracle wallet directory
- Oracle Instant Client/JDBC must be installed
- Port `8080` must be free

## References
- I followed tutorials and had help from several people, so thank you! Here I mention some:
- https://scicloj.github.io/clojure-data-tutorials/projects/ml/llm/vectorstore.html
- https://medium.com/oracledevs/retrieval-augmented-generation-rag-with-spring-ai-oracle-database-23ai-and-openai-61281b96d18a

