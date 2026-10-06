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
- Policy/static documents: 1-24 hours

## Tenant-Aware Retrieval

Every RAG chunk should carry:

```text
visibility, tenantId, entitlementGroup, ticker, documentType, source, publishedAt
```

Every retrieval must filter by tenant id before returning chunks:

```text
(visibility == PUBLIC OR tenantId == currentTenant OR entitlementGroup IN userEntitlements)
AND ticker == requestedTicker
```

This prevents one client from seeing another client's private research, filings, reports or commentary.

## Production Agent Routing

The first implementation used `supports(message)` keyword checks inside each agent. That works for demos, but it is hard to defend in production because every agent owns a separate routing rule.

The current implementation uses a central `AgentSelector`:

```text
User message
  -> AgentSelector
  -> score every agent capability
  -> select top agent or multi-agent fan-out
  -> AgentOrchestrator calls selected agents
```

Each agent now exposes an `AgentCapability`:

```text
agentName, description, domains, keywords, examples
```

The equity agent does not depend on a hardcoded company list. Queries such as `HSBC current price` are routed by combining:

```text
ticker-like symbol detected in the raw user message
+ market intent words such as price, quote, news, earnings, filing
+ the equity agent capability profile
```

So a new listed symbol should not require a code deployment just to reach the right agent.

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
- Tenant context on chat requests.
- Entitlement groups on chat requests.
- Tenant-safe in-memory cache abstraction.
- RAG query caching.
- Tool response caching.
- Keycloak-ready token broker under `prod` profile.
- Keycloak issuer validation under `prod` profile for secured API.
- Downstream HTTP connect/read timeouts.
- AWS/profile-based environment-variable config files.

## Still Needed For Full Production

- Replace in-memory cache with Redis/ElastiCache implementation.
- Replace in-memory vector store with Qdrant, OpenSearch Serverless or pgvector.
- Add SQS ingestion worker module.
- Add Dockerfiles and ECS task definitions/CDK/Terraform.
- Add OpenTelemetry/Micrometer tracing export to CloudWatch/X-Ray.
- Add circuit breakers and retry policies.
- Add production evaluation suite and load tests.
