# Production Notes

## Model Tuning

- Router agents should use low temperature, usually `0`, because routing should be deterministic.
- Tool-calling agents should use `0` to `0.2` because they should select tools predictably.
- Final synthesis can use `0.2` to `0.4` when multiple tool outputs must be turned into a readable answer.
- `max tokens` caps output length. It controls maximum response size, not randomness.
- `top_p` controls sampling diversity. It is another randomness control, not a length control.
- Tool choice means whether the model is allowed to call tools freely, is forced to call a specific tool, or is prevented from calling tools.

## Vector Store Choice

Qdrant is a good fit for this application when:

- We want a dedicated vector database instead of putting vectors inside the relational database.
- We need metadata filters such as `tenantId`, `ticker`, `documentType`, `publishedAt`, and `source`.
- We expect high RAG query volume across equities, indexes, commodities and client-specific data.
- We want operational separation between transactional data and semantic retrieval data.

Recommended decision:

- Demo/local: Spring AI in-memory vector store.
- Small production/simple infra: Aurora PostgreSQL with pgvector.
- Production market-data RAG with tenant filtering and higher scale: Qdrant or OpenSearch Serverless vector search.

For this app, Qdrant is a strong choice because agents retrieve by semantic meaning plus strict metadata filters.

## SQS Ingestion Usage

SQS is not on the synchronous chat path.

Chat path:

```text
User -> /chat -> agents -> cache/RAG/API -> response
```

Async ingestion path:

```text
EventBridge schedule
  -> fetch market data/news/filings
  -> publish document jobs to SQS
  -> ingestion workers consume
  -> filter, chunk, embed
  -> write to vector store
```

SQS helps with retries, burst handling, dead-letter queues and horizontal worker scaling.

## Cache Strategy

Implemented in code as `ProductionCache` with an in-memory implementation.

Production replacement:

```text
ProductionCache -> Redis / ElastiCache
```

Cache keys must include tenant id:

```text
tenantId:stock:price:AAPL
tenantId:index:NIFTY50
tenantId:commodity:GOLD
tenantId:rag:AAPL:recent news query
```

Suggested TTLs:

- Live prices: 15-30 seconds
- Index/commodity snapshots: 30-60 seconds
- News/tool responses: 1-5 minutes
- Static research documents: 1-24 hours

## Tenant-Aware Retrieval

Every RAG chunk should carry:

```text
visibility, tenantId, entitlementGroup, ticker, documentType, source, publishedAt
```

Every retrieval must filter by tenant id before returning chunks:

```text
visibility == 'PUBLIC'
  OR (visibility == 'TENANT_PRIVATE'         AND tenantId == currentTenant)
  OR (visibility == 'ENTITLEMENT_RESTRICTED' AND entitlementGroup IN userEntitlements)
AND ticker == requestedTicker
```

The visibility scoping is load-bearing. Entitlement-restricted documents carry an empty `tenantId`,
so folding the branches together as `visibility == PUBLIC OR tenantId == currentTenant OR ...` lets
an anonymous caller match them through `tenantId == ''`. Each attribute comparison is therefore
gated by its own visibility value, and the whole predicate is grouped before it is ANDed with the
ticker and freshness clauses.

The predicate is built as a typed expression tree rather than a string, so a tenant id or group name
cannot alter the expression's meaning.

This prevents one client from seeing another client's private research, filings, reports or commentary.

## Production Agent Routing

The first implementation used `supports(message)` keyword checks inside each agent. That works for demos, but it is hard to defend in production because every agent owns a separate routing rule.

The current implementation uses a central `AgentSelector` supervisor:

```text
User message
  -> tiny deterministic fast path for obvious cases
  -> LlmAgentSelector as the primary router
  -> heuristic fallback only if the LLM router fails or returns no route
  -> AgentOrchestrator calls selected agents in parallel
```

Each agent now exposes an `AgentCapability`:

```text
agentName, description, exampleQueries
```

The capability object intentionally does not contain large keyword lists. The LLM router receives descriptions and example queries, so paraphrases such as these still route correctly:

```text
How is HSBC doing?       -> equity-agent
Is Tata Motors a buy?    -> equity-agent
Apple vs gold            -> equity-agent + commodity-agent
```

Small keyword logic remains only as a low-latency fast path for obvious queries and as a last-resort fallback if the LLM router is unavailable. It is not the main intelligence of the router.

The selector returns `agentSelections` in the `/chat` response so the route is auditable:

```text
agentName=commodity-agent
score=9
reason=Matched capability signals: gold, commodity, example-overlap
```

For a client interview, position this as the production pattern:

- Deterministic router first for speed, repeatability and auditability.
- LLM router can be added for ambiguous queries, but it should output structured JSON with confidence.
- Low-confidence queries should either fan out to multiple agents or ask a clarification question.
- Routing logs become evaluation data, so wrong routes can improve the capability registry over time.
- Never let routing bypass authorization. Even if the wrong agent is selected, the tool and API scope checks still enforce access.

## Parallel Agent Execution

The orchestrator uses `CompletableFuture` with a bounded `agentExecutor` so selected agents run concurrently:

```text
index-agent      ----\
commodity-agent  ----- CompletableFuture fan-out -> join -> composed answer
equity-agent     ----/
```

This matters for cross-asset questions because index, equity and commodity agents may each call separate secured APIs or RAG searches.

The executor propagates Spring request context into worker threads so request-scoped tenant context and tool tracing still work during parallel calls.

Current build target is Java 17, so the implementation uses a bounded platform-thread pool. If the project moves to Java 21, the executor can be changed to `Executors.newVirtualThreadPerTaskExecutor()` for I/O-heavy agent calls, while keeping the same orchestrator code.

## Connector Access Classification

Access classification happens in ingestion connectors before chunking:

```text
DemoMarketDataConnector      -> PUBLIC
ClientDocumentConnector      -> TENANT_PRIVATE with tenantId
PremiumVendorConnector       -> ENTITLEMENT_RESTRICTED with entitlementGroup
```

The chunker does not guess privacy from text. It preserves connector-assigned metadata on every chunk.

Examples:

```text
Reuters / SEC / exchange data:
visibility=PUBLIC
tenantId=""

Client A internal research:
visibility=TENANT_PRIVATE
tenantId=clientA

Premium vendor report:
visibility=ENTITLEMENT_RESTRICTED
entitlementGroup=premium-research
```

Query-time filter examples:

```text
Client A without premium entitlement:
visibility == PUBLIC OR tenantId == clientA

Client A with premium entitlement:
visibility == PUBLIC OR tenantId == clientA OR entitlementGroup == premium-research
```

## Implemented Now

- Multi-agent supervisor.
- Equity, index and commodity agents.
- Scoped tools for stock, index and commodity APIs.
- Bedrock Converse configured for production chat/tool-calling.
- Bedrock Titan configured for production RAG embeddings.
- JWT-authenticated `/chat` and `/rag/ingest`, with per-endpoint scopes.
- Caller tenant and entitlement groups derived from verified JWT claims, never from the request body.
- Fail-closed, visibility-scoped metadata filtering on every retrieval.
- Ingestion and retrieval share one vector store; chunks are keyed by chunk id so re-ingestion overwrites.
- Supervisor clarification requests surfaced to the caller instead of being silently re-routed.
- Routing thresholds (`min-score`, `fanout-score-gap`, `max-agents`) actually applied.
- Tenant-safe in-memory cache abstraction.
- RAG query caching.
- Tool response caching.
- Live-API fallback with best-effort write-through into the vector store.
- Keycloak-ready token broker under `prod` profile.
- Keycloak issuer validation under `prod` profile for the secured API and the assistant.
- Downstream HTTP connect/read timeouts.
- AWS/profile-based environment-variable config files.
- Unit tests covering access control, routing, ingestion mapping and tool contracts.

## Still Needed For Full Production

- Replace in-memory cache with Redis/ElastiCache implementation.
- Replace in-memory vector store with Qdrant, OpenSearch Serverless or pgvector.
- Add SQS ingestion worker module.
- Add Dockerfiles and ECS task definitions/CDK/Terraform.
- Add OpenTelemetry/Micrometer tracing export to CloudWatch/X-Ray.
- Add circuit breakers and retry policies.
- Finish the end-to-end evaluation suite: the harness now enforces its assertions, but it needs a
  live stack (Ollama plus all three services) and load tests to run in CI.
