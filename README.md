# Secure Multi-Agent RAG Assistant over Market Data

This project is an interview-ready Spring Boot demo for a secure financial assistant. It combines:

- Multi-agent orchestration for equities, indexes and commodities.
- RAG over stock news, filings, reports, transcripts, promoter activity and policy documents.
- A protected mock market-data API.
- A token broker that mints short-lived, narrowly scoped JWTs.
- Spring AI tool calling with full tool trace visibility.
- A scheduler-friendly ingestion pipeline that filters, chunks and stores market documents.

The demo uses a mock HS256 token broker so the secure path runs locally. In production, that broker can be swapped for Keycloak client-credentials or token-exchange flow while keeping the assistant and secured API contracts the same.

## Documentation

| Document | Contents |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Request flow, RAG pipeline, access predicate, multi-agent routing, security model, optimisations, architectural decisions and honest limitations. |
| [INTERVIEW_QA.md](INTERVIEW_QA.md) | Anticipated questions with grounded answers, plus a demo script. |
| [PRODUCTION_NOTES.md](PRODUCTION_NOTES.md) | Model tuning, vector-store choice, caching, tenant-aware retrieval and the production gap list. |

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

The assistant's own endpoints are protected the same way: `/chat` requires `assistant:chat` and
`/rag/ingest` requires `rag:ingest`, both validated against the broker's signing key locally and
against the Keycloak issuer under the `prod` profile.

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
ChunkDocumentMapper
 |
VectorStore   <-- the same store RagSearchTool and PolicySearchTool read from
```

Ingestion and retrieval share one store. Documents are written with the chunk id as their vector id,
so re-ingesting a source overwrites rather than duplicating.

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
ticker, companyName, documentType, source, url, publishedAt, publishedAtEpochMs, sector, sentiment
```

Access metadata is assigned by the connector and preserved on every chunk:

```text
visibility, tenantId, entitlementGroup
```

Retrieval applies a fail-closed predicate built from the caller's verified claims:

```text
visibility == 'PUBLIC'
  OR (visibility == 'TENANT_PRIVATE'         AND tenantId == <caller tenant>)
  OR (visibility == 'ENTITLEMENT_RESTRICTED' AND entitlementGroup IN <caller groups>)
```

Each branch is scoped by its own visibility value. Entitlement-restricted documents carry an empty
`tenantId`, so an unscoped `tenantId == ''` clause would hand them to every anonymous caller.

Manual ingestion endpoint (requires the `rag:ingest` scope):

```bash
curl -X POST http://localhost:8080/rag/ingest -H "Authorization: Bearer $TOKEN"
```

Startup and scheduled ingestion are also seeded automatically. If the embedding model is
unreachable, ingestion logs a warning and the assistant degrades to the live API rather than failing
to boot.

## Build

```bash
mvn clean package
```

Each module produces an executable Spring Boot jar:

```bash
java -jar token-broker/target/token-broker-0.1.0-SNAPSHOT.jar
```

Unit tests run as part of the build. `mvn test` runs them alone.

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

## Authentication

The assistant no longer trusts its callers. `POST /chat` requires a bearer token issued by the
token broker, and the caller's tenant and entitlement groups are read from that token's verified
claims — they are **not** accepted from the request body.

| Endpoint | Required scope |
| --- | --- |
| `POST /chat` | `assistant:chat` |
| `POST /rag/ingest` | `rag:ingest` |

Each demo user carries its own tenant and entitlements:

| User | Employee | Tenant | Entitlement groups |
| --- | --- | --- | --- |
| `alice` | `1001` | `clientA` | `premium-research` |
| `ben` | `1002` | `clientB` | none |

Get a token once and reuse it (tokens live for 120 seconds):

```bash
TOKEN=$(curl -s -X POST http://localhost:8082/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password","employeeId":"1001","scopes":["assistant:chat"]}' \
  | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
```

The tools mint their own separate service tokens for downstream market-data calls, requesting only
market-data scopes. Those tokens never carry `assistant:chat`, so they cannot be replayed against
`/chat`.

## Example Questions

The request body carries only the message. Equity:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Summarize recent AAPL news and current price"}'
```

Index:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is NIFTY 50 doing and what are the top constituents?"}'
```

Commodity:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Give me the latest gold and crude oil data"}'
```

Cross-asset:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Compare NIFTY with gold and Brent crude today"}'
```

Policy documents are retrieved through the same RAG path:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is the remote work policy?"}'
```

The response includes `toolTrace` (every tool call, its arguments, result and duration),
`agentSelections` (which specialist agents were chosen and why), `agentCount`, and
`needsClarification`. When the supervisor cannot confidently route a question it sets
`needsClarification=true` and returns a clarifying question instead of guessing.

## Production Upgrade Path

Already handled in this build:

- Caller identity comes from a verified JWT; tenant and entitlements are never taken from the body.
- Every retrieval applies a tenant- and entitlement-aware metadata filter.
- Ingestion deduplicates by chunk id and applies a freshness window.

Still to do:

- Replace the mock token broker with Keycloak:
  - broker calls Keycloak `token-uri`
  - secured API and the assistant validate the Keycloak `issuer-uri` (already wired under the `prod` profile)
- Replace the in-memory vector store with pgvector, Qdrant, OpenSearch or another enterprise vector DB.
- Replace the in-memory cache with Redis/ElastiCache.
- Move ingestion onto SQS/EventBridge with dead-letter queues.
- Add Micrometer tracing around supervisor routing, tool calls, token minting and RAG retrieval.
- Add retries, timeouts, circuit breakers and structured error codes for every tool call.
- Add Dockerfiles and infrastructure definitions.
