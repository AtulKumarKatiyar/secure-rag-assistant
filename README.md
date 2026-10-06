# Secure Multi-Agent RAG Assistant over Market Data

This project is an interview-ready Spring Boot demo for a secure financial assistant. It combines:

- Multi-agent orchestration for equities, indexes and commodities.
- RAG over stock news, filings, reports, transcripts, promoter activity and policy documents.
- A protected mock market-data API.
- A token broker that mints short-lived, narrowly scoped JWTs.
- Spring AI tool calling with full tool trace visibility.
- A scheduler-friendly ingestion pipeline that filters, chunks and stores market documents.

The demo uses a mock HS256 token broker so the secure path runs locally. In production, that broker can be swapped for Keycloak client-credentials or token-exchange flow while keeping the assistant and secured API contracts the same.

## Modules

| Module | Port | Purpose |
| --- | ---: | --- |
| `ai-assistant` | `8080` | Exposes `/chat`, supervises domain agents, retrieves RAG context and calls secured tools. |
| `token-broker` | `8082` | Simulates Keycloak-style token brokering and returns short-lived scoped JWTs. |
| `mock-stock-api` | `8083` | Secured market-data backend for stock news/prices, index data and commodity data. |
| `evaluation-harness` | none | Executes reusable chat evaluation cases. |

## Multi-Agent Design

The chat flow is supervised by `AgentOrchestrator`.

```text
User
 |
 | POST /chat
 v
AI Assistant / Supervisor
 |
 |-- EquityResearchAgent
 |     tools: searchStockNews, getLiveStockNews, getLiveStockPrice
 |
 |-- IndexResearchAgent
 |     tool: getIndexData
 |
 |-- CommodityResearchAgent
 |     tool: getCommodityData
 |
 v
Answer + per-agent answers + toolTrace
```

Each agent owns a narrow domain and a narrow set of tools:

- `EquityResearchAgent`: equities, company news, prices, earnings, filings, promoter activity.
- `IndexResearchAgent`: NIFTY, SENSEX, NASDAQ, S&P 500, constituents, sector weights.
- `CommodityResearchAgent`: oil, Brent, WTI, gold, silver, natural gas.

The supervisor can select more than one agent for cross-asset questions, for example:

```text
Compare NIFTY movement with gold and crude oil today.
```

That can invoke both the index and commodity agents and return a combined response.

## Secured Backend APIs

`mock-stock-api` validates JWT bearer tokens and enforces scopes:

| Endpoint | Required scope |
| --- | --- |
| `GET /stocks/{ticker}/news` | `stock-news:read` |
| `GET /stocks/{ticker}/price` | `stock-news:read` |
| `GET /indexes/{indexSymbol}/data` | `index-data:read` |
| `GET /commodities/{commoditySymbol}/data` | `commodity-data:read` |

Example backend protection:

```java
@PreAuthorize("hasAuthority('SCOPE_index-data:read')")
```

The assistant never calls these APIs directly without a token. Each tool asks the token broker for the exact scope it needs, then calls the secured backend with:

```text
Authorization: Bearer <jwt>
```

## RAG Ingestion

The ingestion pipeline converts market sources into a common `RagDocument` model, filters bad/noisy records, chunks useful text and stores chunks with metadata.

```text
Scheduler / manual trigger
 |
DataSourceConnector
 |
DocumentFilter
 |
DocumentChunker
 |
MarketRagStore / VectorStore
```

Stored content types include:

- News article text
- Summaries
- Filings
- Reports
- Earnings transcripts
- Policy documents
- Analyst commentary
- Promoter activity
- Promoter commentary

Typical metadata:

```text
ticker, companyName, documentType, source, url, publishedAt, sector, sentiment
```

Manual ingestion endpoint:

```bash
curl -X POST http://localhost:8080/rag/ingest
```

## Build

```bash
mvn test
```

## Run Locally

Use three terminals:

```bash
mvn -pl token-broker spring-boot:run
```

```bash
mvn -pl mock-stock-api spring-boot:run
```

```bash
mvn -pl ai-assistant spring-boot:run
```

The assistant expects Ollama locally for Spring AI:

```text
http://localhost:11434
model: llama3.2
embedding model: nomic-embed-text
```

## Example Questions

Equity:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Summarize recent AAPL news and current price"}'
```

Index:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is NIFTY 50 doing and what are the top constituents?"}'
```

Commodity:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Give me the latest gold and crude oil data"}'
```

Cross-asset:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Compare NIFTY with gold and Brent crude today"}'
```

## Production Upgrade Path

- Replace the mock token broker with Keycloak:
  - broker calls Keycloak `token-uri`
  - secured API validates Keycloak `issuer-uri`
- Replace in-memory vector store with pgvector, OpenSearch, Pinecone or another enterprise vector DB.
- Add tenant-aware metadata filters for client isolation.
- Add Micrometer tracing around supervisor routing, tool calls, token minting and RAG retrieval.
- Add retries, timeouts, circuit breakers and structured error codes for every tool call.
- Add deduplication and freshness policies to ingestion.
