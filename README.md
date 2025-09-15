# clojure-datomic-adb-23ai-vector-search

## What this demo is

This is a small, end‑to‑end demo of a Clojure microservice that combines:
- Clojure + Pedestal (HTTP)
- Datomic using SQL storage on Oracle
- Oracle Autonomous Database Vector Search (LangChain4j `OracleEmbeddingStore`)

Endpoints (open in your browser):
- `/greet?name=World` → simple greeting
- `/food?text=your+query` → JSON with top‑5 matches `{id,name,description,score}`

### What you’ll see
- How an HTTP handler calls a vector search on Oracle, then hydrates results from Datomic
- Clean separation of pure functions and side‑effects, and explicit dependency injection

## Quick start

1) Start the service
```bash
lein run
```

2) Open in your browser
- Greeting: `http://localhost:8080/greet?name=World`
- Food search (Portuguese): `http://localhost:8080/food?text=comida%20baiana%20com%20dend%C3%AA`
- Food search (English): `http://localhost:8080/food?text=Brazilian%20seafood%20with%20dend%C3%AA%20flavor`

3) You should see
- Greeting: a plain text “Hello World!”
- Food search (example)
```json
{
  "query": "comida baiana com dendê",
  "results": [
    { "id": 17, "name": "moqueca", "description": "Um(a) delicioso(a) moqueca, do litoral baiano, preparado artesanalmente e feito(a) com azeite de dendê, que aquece a alma.", "score": 0.86 },
    { "id": 203, "name": "vatapá",  "description": "Um(a) saboroso(a) vatapá, do recôncavo baiano, cozido(a) lentamente e feito(a) com coco ralado, que derrete na boca.", "score": 0.81 }
  ],
  "count": 2
}
```

## How to initialize it

Prerequisites
- Java 17+, Leiningen
- Oracle Instant Client + ADB Wallet (set `TNS_ADMIN` to the wallet directory)

## Optional: seed demo data

Open a REPL to load a small demo dataset:
```clojure
(require '[clojure-datomic-adb-23ai-vector-search.dbconf :as db])
(def cfg (db/make-config))
(def uris (db/build-uris cfg))
(db/ensure-datomic-db-exists! (:datomic-uri uris))
(def conn (db/connect-datomic! (:datomic-uri uris)))
(def pds (db/oracle-pool-data-source! {:jdbc-url (:jdbc-url uris)
                                       :db-username (:db-username cfg)
                                       :db-password (:db-password cfg)}))
(def store (db/oracle-embedding-store! pds (:datomic-db-name cfg)))
(db/populate-datomic-oracle-db! conn store (db/make-food-descriptions 200))
```

## How it works (very short)
- `/food` handler embeds your query, searches Oracle Vector Store, gets top matches
- It looks up details in Datomic (stored on Oracle via SQL storage)
- Returns merged results as JSON

## Troubleshooting
- `TNS_ADMIN` must point to a valid Oracle wallet directory
- Oracle Instant Client/JDBC must be installed
- Port `8080` must be free

