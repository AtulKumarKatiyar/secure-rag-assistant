# Architecture

Secure multi-agent RAG assistant over market data. This document describes what is built, how a
request flows, and why the significant decisions were made.

---

## 1. System at a glance

Four Spring Boot modules, each an independently deployable jar.

| Module | Port | Responsibility |
| --- | ---: | --- |
| `ai-assistant` | 8080 | `/chat` and `/rag/ingest`. Supervises domain agents, retrieves RAG context, calls secured tools. |
| `token-broker` | 8082 | Mints short-lived, narrowly scoped JWTs. Keycloak client-credentials under the `prod` profile. |
| `mock-stock-api` | 8083 | Secured market-data backend. Validates JWTs and enforces per-endpoint scopes. |
| `evaluation-harness` | — | Runs reusable chat evaluation cases against a live stack. |

Technology: Java 17, Spring Boot 3.4.5, Spring AI 1.0.0, Ollama (`llama3.2` chat, `nomic-embed-text`
embeddings), Spring Security OAuth2 resource server, JUnit 5 + AssertJ + Mockito.

---

## 2. End-to-end request flow

```
Client
  |  1. POST /token                 -> short-lived JWT (120s) with scopes + tenant claims
  |  2. POST /chat  Bearer <jwt>    -> answer
  v
```

### Stage 1 — Authentication and identity (`ChatController`)

1. Spring Security validates the JWT signature and expiry and requires `SCOPE_assistant:chat`.
2. `ChatController` receives the verified `Jwt` and calls `tenantContext.setFromToken(jwt)`.
3. `TenantContext` is `@RequestScope` and extracts `tenant_id` and `entitlement_groups` from the
   claims. The request body carries **only** `message`.

If the `tenant_id` claim is absent, the tenant stays blank and retrieval fails closed — public
documents only, never another tenant's private research.

### Stage 2 — Routing (`AgentSelector`)

```
message
  -> HeuristicAgentSelector.fastPath   (deterministic, no model call)
  -> LlmAgentSelector.select           (supervisor persona, strict JSON)
  -> HeuristicAgentSelector.fallback   (any score > 0)
  -> configured default agent
```

Returns a `RoutingDecision`: either a list of agents, or `needsClarification`.

If clarification is requested the orchestrator **short-circuits** and returns a question rather than
answering with a possibly wrong specialist.

### Stage 3 — Parallel fan-out (`AgentOrchestrator`)

Selected agents run concurrently on a bounded `ThreadPoolTaskExecutor`
(`agent-worker-`, core 4 / max 8 / queue 50) with a per-agent timeout of 8000 ms:

```java
CompletableFuture
    .supplyAsync(() -> agent.answer(message), agentExecutor)
    .orTimeout(executionProperties.timeoutMs(), TimeUnit.MILLISECONDS)
    .handle((answer, failure) -> toAgentAnswer(agent.name(), answer, failure))
```

A cross-asset question therefore costs roughly `max(t₁, t₂)` rather than `t₁ + t₂`.

**Request-context propagation.** `TenantContext` and `ToolTraceRecorder` are `@RequestScope`, so
worker threads have no request by default. A `TaskDecorator` copies `RequestContextHolder` into each
worker. Without it, tool calls in the fan-out would throw or observe a blank tenant and silently
return only public documents. A test asserts a missing tenant yields public-only access.

### Stage 4 — Agent loop (tool calling)

Each agent is a `ChatClient` built with its own system prompt and tool set:

```java
public String answer(String message) {
    return agent.prompt().user(message).call().content();
}
```

The multi-step loop — model selects a tool → tool executes → result is fed back → model decides
again → final text — is performed by Spring AI's tool-calling manager **inside `call()`**. It is not
implemented in `AgentOrchestrator`, which is why the response field is `agentCount` (fan-out width)
and not an iteration count.

### Stage 5 — Tool execution

| Tool | Class | Backing source |
| --- | --- | --- |
| `searchStockNews` | `RagSearchTool` | Vector store, falling back to the secured API |
| `searchPolicies` | `PolicySearchTool` | Vector store (policy documents) |
| `getLiveStockNews` | `StockNewsTool` | Secured API, `stock-news:read` |
| `getLiveStockPrice` | `StockNewsTool` | Secured API, `stock-news:read` |
| `getIndexData` | `IndexManagementTool` | Secured API, `index-data:read` |
| `getCommodityData` | `CommodityDataTool` | Secured API, `commodity-data:read` |

Every live tool mints its **own** service token for the exact scope it needs, then calls the secured
backend with `Authorization: Bearer <jwt>`. Results are cached under a tenant-scoped key and recorded
in the tool trace.

### Stage 6 — Composition and response

One agent → its answer verbatim. Multiple agents → wrapped with `[agent-name]` section headers so
the caller can see which specialist said what.

```json
{
  "answer": "...",
  "toolTrace": [{ "tool", "args", "result", "ok", "durationMs" }],
  "mode": "AGENT",
  "agentCount": 2,
  "needsClarification": false,
  "agentSelections": [{ "agentName", "score", "reason" }],
  "agentAnswers": [{ "agentName", "answer" }]
}
```

---

## 3. RAG design

### 3.1 Corpus

20 documents across four connectors:

| Connector | Docs | Visibility assigned |
| --- | ---: | --- |
| `DemoMarketDataConnector` | 6 | `PUBLIC` |
| `ClientDocumentConnector` | 2 | `TENANT_PRIVATE` (`clientA`, `clientB`) |
| `PremiumVendorConnector` | 2 | `ENTITLEMENT_RESTRICTED` (`premium-research`) |
| `PolicyDocumentConnector` | 10 | `PUBLIC` |

Document types: `NEWS`, `SUMMARY`, `FILING`, `REPORT`, `EARNINGS_TRANSCRIPT`, `POLICY`,
`ANALYST_COMMENTARY`, `PROMOTER_ACTIVITY`, `PROMOTER_COMMENTARY`.

### 3.2 Ingestion pipeline

```
Scheduler / POST /rag/ingest
  -> DataSourceConnector.fetch()
  -> DocumentFilter.isRelevant()
  -> DocumentChunker.chunk()
  -> ChunkDocumentMapper.toDocument()
  -> VectorStore.add()
```

- **`DocumentFilter`** rejects documents shorter than 300 chars, untracked tickers, untrusted
  sources, stale market data (>180 days), and text with no financial signal. It requires valid access
  metadata on everything. Policies are exempt from the ticker/source/signal checks but **not** from
  the access-metadata requirement.
- **`DocumentChunker`** splits on blank lines and drops paragraphs under 80 chars.
- **`ChunkDocumentMapper`** converts a chunk into a Spring AI `Document`, using the **chunk id as the
  document id**, so re-ingesting a source overwrites its vectors instead of accumulating duplicates.
  This makes ingestion idempotent.

Startup seeding is wrapped so an unreachable embedding model logs a warning and the application still
boots, degrading to live-API answers.

### 3.3 Metadata schema

Every stored chunk carries the fields retrieval filters on, plus the values that decide access:

```
documentId, chunkId, ticker, companyName, documentType, title, source, url,
publishedAt, publishedAtEpochMs,
visibility, tenantId, entitlementGroup
```

`publishedAtEpochMs` is a `Long` deliberately. Spring AI's filter converter recognises ISO-8601
shaped strings and rewrites them as date literals, and `Instant.toString()` emits variable precision
(`...T09:00:00Z` vs `...T09:00:00.500Z`), so string time comparisons sort incorrectly. A numeric
epoch is unambiguous.

### 3.4 Retrieval and the access predicate

`RagAccessFilter` builds the predicate as a **typed expression tree**, never a string:

```
(
  visibility == 'PUBLIC'
  OR (visibility == 'TENANT_PRIVATE'         AND tenantId == '<caller tenant>')
  OR (visibility == 'ENTITLEMENT_RESTRICTED' AND (entitlementGroup == 'g1' OR ...))
)
AND ticker == '<ticker>'
AND publishedAtEpochMs >= <now - max-age>
```

Two properties are load-bearing:

1. **Each branch is scoped by its own visibility value.** Entitlement-restricted documents carry an
   empty `tenantId`, so a flattened `PUBLIC || tenantId == ''` would hand every one of them to an
   anonymous caller.
2. **The whole predicate is grouped before it is ANDed** with the ticker and freshness clauses.
   SpEL binds `and` tighter than `or`, so an ungrouped predicate would render as
   `A or B and ticker == 'X'` and the ticker restriction would stop applying to the public branch.

Because it is built as an AST, a tenant id or group name cannot alter the expression's meaning —
there is no string concatenation to escape.

Tests evaluate the predicate exactly as `SimpleVectorStore` does (convert to SpEL, execute against
metadata) rather than string-matching it.

### 3.5 Live fallback and write-through

`searchStockNews` searches the vector store first. If nothing clears `min-score` (0.75):

1. Fetch from the live secured API.
2. Write the fetched articles back into the vector store (best-effort, tenant-safe: public market data).
3. Return the **actual** articles with `source: "API"`.

The response never claims cached data it did not return, and an error payload from the live tool is
propagated as `source: "ERROR"` rather than presented as content.

Embedding failures are caught inside the retrieval attempt so an embedding outage degrades to the
live API instead of failing the tool.

---

## 4. Multi-agent design

### 4.1 One model, four personas

There is a single Ollama model. It is instantiated four times with different system prompts and
different tool sets:

| Persona | Tools | Purpose |
| --- | --- | --- |
| supervisor | none | Routing only. Must return strict JSON. |
| equity-agent | `searchStockNews`, `getLiveStockNews`, `getLiveStockPrice`, `searchPolicies` | Companies, prices, filings, earnings |
| index-agent | `getIndexData`, `searchPolicies` | Index levels, constituents, sector weights |
| commodity-agent | `getCommodityData`, `searchPolicies` | Oil, gold, silver, gas |

Each agent publishes an `AgentCapability` (`name`, `description`, `exampleQueries`) which doubles as
the catalogue the supervisor reasons over. Deliberately no large keyword lists there — the LLM router
is expected to handle paraphrases.

Every agent's system prompt also states that retrieved documents are untrusted content and must not
change the instrument or user identity.

### 4.2 Routing tiers

**Tier 1 — deterministic fast path.** Tokenise, score each agent, and route without a model call when
the answer is unambiguous:

| Signal | Score |
| --- | ---: |
| Equity term (`stock`, `price`, `equity`, `earnings`, `ticker`, `quote`, `share`) | +3 |
| Ticker-like ALL-CAPS symbol | +2 |
| Index term (`index`, `nifty`, `sensex`, `nasdaq`, `sp500`, `constituents`) | +4 |
| Commodity term (`gold`, `oil`, `crude`, `brent`, `wti`, `silver`, `copper`, `commodity`) | +4 |

Decision rule:

- Keep agents scoring ≥ `min-score` (3)
- Comparison term present (`compare`, `comparison`, `versus`, `vs`, `between`) **and** >1 candidate → fan out
- Exactly one candidate → route to it
- Multiple candidates without a comparison term → only if the leader is ahead by ≥ `fanout-score-gap` (3); otherwise defer

**Tier 2 — LLM supervisor.** Primary router for anything the fast path declines. Receives the
capability catalogue plus the query and returns:

```json
{"agents": ["equity-agent"], "needsClarification": false, "confidence": 0.92, "reason": "..."}
```

Agent names are filtered against the real agent set, so the model cannot invent a route. Failures are
logged (never silent) and fall through.

**Tier 3 — heuristic fallback.** Any agent with score > 0, capped at `max-agents`.

**Tier 4 — configured default agent**, so a request always gets an answer.

### 4.3 Worked routing examples

| Query | Outcome | Why |
| --- | --- | --- |
| "What is the current price of AAPL?" | fast path → equity | `price` +3, `AAPL` +2 = 5.0 |
| "Compare NIFTY 50 with gold and Brent crude today" | fast path → index + commodity | fanout term; both score 4.0 |
| "What is AAPL trading at right now?" | LLM router → equity | only the ticker signal scores 2.0, below threshold |
| "Apple vs gold" | LLM router | fanout term but only commodity scores; a comparison with one domain is ambiguous |
| "NIFTY and gold" | LLM router | both score 4.0, gap 0 < 3; `and` is a stop word, not a fanout term |

### 4.4 Failure isolation

`orTimeout` plus `handle` convert a slow or throwing agent into `"Agent failed: <cause>"` text. The
request still returns the other agents' answers — one specialist failing never fails the whole chat.

---

## 5. Security model

### 5.1 Trust boundaries

```
Untrusted            Authenticated                 Service-to-service
--------             -------------                 ------------------
client body  -->  /chat (JWT, assistant:chat)
                        |
                        |-- tools mint scoped service tokens (never assistant:chat)
                        v
                  /token  ->  JWT(120s, scopes, tenant_id, entitlement_groups)
                        |
                        v
                  mock-stock-api  (JWT validated, @PreAuthorize per scope)
```

**Two token types, deliberately separated.** The caller's token carries `assistant:chat`; the tokens
tools mint request only market-data scopes. Because the broker intersects requested scopes with the
user's allowed scopes, a service token can never be replayed against `/chat`.

### 5.2 Scopes

| Endpoint | Required scope |
| --- | --- |
| `POST /chat` | `assistant:chat` |
| `POST /rag/ingest` | `rag:ingest` |
| `GET /stocks/{ticker}/news` | `stock-news:read` |
| `GET /stocks/{ticker}/price` | `stock-news:read` |
| `GET /indexes/{symbol}/data` | `index-data:read` |
| `GET /commodities/{symbol}/data` | `commodity-data:read` |

Demo identities: `alice` (1001, `clientA`, `premium-research`) and `ben` (1002, `clientB`, no
entitlements). Tokens live 120 seconds.

### 5.3 Tenant isolation, concretely

`clientB`'s NVIDIA memo is stored as `visibility=TENANT_PRIVATE, tenantId=clientB`. When `alice`
(`clientA`) asks "Show me Client B's private NVIDIA memo", the predicate admits only:
her own tenant's documents, public documents, and entitlement documents for groups she holds. The
`clientB` document matches none of those branches, so it is unreachable — regardless of how the
question is phrased, and regardless of which agent the router picked.

### 5.4 Routing is not a security boundary

A wrong routing decision changes *relevance*, never *authorisation*. Even if the supervisor selected
the wrong agent, or a prompt-injection document told the model to call a tool or change the employee
identity, the tool-level access predicate and the API scope checks still apply independently. There
are no tools for HR or employee data in this build.

Retrieved documents are treated as untrusted content: system prompts instruct the agents not to
follow instructions found inside them, and a deliberate injection fixture
(`policies/prompt-injection-test.txt`) is part of the ingested corpus so this is exercised rather
than assumed.

### 5.5 Fail-closed defaults

| Condition | Behaviour |
| --- | --- |
| No `tenant_id` claim | Tenant blank; tenant branch omitted from the predicate → public only |
| Missing/invalid access metadata on a document | Rejected at ingestion |
| Unresolvable config in `prod` (e.g. credentials) | Startup failure, not silent fallback to demo values |
| LLM router unavailable | Logged, heuristic fallback used |

---

## 6. Optimisation

| Technique | Where | Effect |
| --- | --- | --- |
| Deterministic fast-path routing | `HeuristicAgentSelector` | Avoids an LLM round trip on unambiguous queries |
| Parallel agent fan-out | `AgentOrchestrator` + bounded pool | Cross-asset latency ≈ slowest agent, not the sum |
| Tenant-scoped response cache | `ProductionCache` in every tool | Repeat questions served from memory |
| RAG query cache (5 min) | `RagSearchTool` | |
| Live price 30 s / news 2 min / index & commodity 45 s | Tools | TTL tuned to how fast each source actually moves |
| Idempotent ingestion by chunk id | `ChunkDocumentMapper` | Re-ingestion overwrites; no duplicate vectors |
| Freshness window pushed into the query | `RagAccessFilter` | Stale chunks filtered in the store, not post-hoc |
| Numeric epoch filtering | metadata | Correct date range comparison |
| Bounded pool + per-agent timeout | `AppConfig` | Back-pressure instead of unbounded thread growth |
| Downstream connect/read timeouts | `RestClient` factories | A slow backend cannot pin a request thread |
| Graceful degradation | `RagSearchTool`, scheduler | Embedding outage → live API; ingestion failure → still boots |

Caching is tenant- and entitlement-scoped: keys are prefixed with `tenantId` and the caller's
entitlement groups, so one caller's cached answer can never be served to another.

---

## 7. Architectural decisions and trade-offs

**1. Central selector instead of `supports(message)` on each agent.**
The original design put a keyword rule inside every agent, so routing logic was scattered and each
new agent needed its own rule. A single `AgentSelector` with a capability registry means routing is
one auditable place, and the LLM router can reason over descriptions rather than keywords.
*Trade-off:* a central selector is a shared dependency; a bad capability description degrades all routing.

**2. LLM router primary, keywords as fast path and fallback.**
Keyword routing cannot handle paraphrases ("Is HSBC a buy?"). The LLM handles those; keywords exist
only for speed on obvious queries and as a safety net.
*Trade-off:* routing now costs a model call for ambiguous queries, and is only as good as the model.

**3. Filter predicate as an AST, not a string.**
String concatenation of tenant ids into a filter expression is an injection surface and makes
escaping the developer's problem. A typed tree removes the class of bug entirely.

**4. Identity from verified JWT claims, never the request body.**
Previously `tenantId` and `entitlementGroups` were accepted from the JSON body, which meant any caller
could assert any tenant — the isolation was advisory. Claims are signed and verified.
*Trade-off:* callers now need a token, which complicates local testing.

**5. One shared vector store rather than one per domain.**
Ingestion previously wrote to a store retrieval never read, so the RAG path was inert. Sharing one
store means one ingestion pipeline and one access predicate cover market data and policy documents.
*Trade-off:* an embedding failure affects every retrieval path at once — mitigated by degrading to
live APIs, but in production the stores have different availability profiles.

**6. Chunk-id keyed upsert for idempotent ingestion.**
Re-running ingestion would otherwise duplicate vectors and skew similarity results.

**7. Tool methods return JSON `String`.**
Forced by Spring AI 1.0.0, whose `MethodToolCallbackProvider` discards any `@Tool` method whose
return type is assignable from `Function`/`Supplier`/`Consumer` — which includes `Object`. Returning
`Object` silently removed all four market-data tools and made the application unstartable. A test now
encodes the constraint.

**8. Policies exposed as a shared tool on all three agents.**
Policy documents are cross-cutting enterprise knowledge, not a market domain, so they do not belong
to one agent and adding a fourth agent purely for policies would add routing surface for little gain.
*Trade-off:* every agent carries one extra tool in its selection prompt.

**9. In-memory vector store and cache for the demo.**
Zero external dependencies, which keeps the demo runnable and the focus on the architecture.
*Trade-off:* state is lost on restart and does not scale horizontally. Production path is Qdrant or
pgvector, and Redis for the cache — both behind existing interfaces (`VectorStore`, `ProductionCache`).

**10. Bounded platform-thread pool rather than virtual threads.**
The build targets Java 17. On Java 21 the executor swaps to
`Executors.newVirtualThreadPerTaskExecutor()` with no orchestrator changes, since the fan-out is
I/O-bound.

---

## 8. Honest limitations

These are deliberate demo simplifications, not oversights — do not claim otherwise.

- In-memory vector store and cache: no persistence, no horizontal scale.
- No circuit breakers, retries or structured error codes on downstream calls.
- No distributed tracing export (Micrometer/OpenTelemetry) yet.
- Ingestion is in-process; the SQS/EventBridge worker path is designed but not built.
- No Dockerfiles or infrastructure definitions.
- The mock broker is not a real IdP; Keycloak is wired but only under the `prod` profile.
- The evaluation harness needs a live stack (Ollama + broker + API) and has not been run end-to-end.
- No rate limiting or abuse controls on `/chat`.
- `DocumentFilter`'s tracked tickers and trusted sources are hardcoded sets — adding an instrument
  currently needs a code change.

---

## 9. Verification

`mvn clean package` builds all four modules and produces executable Spring Boot jars.

| Module | Tests |
| --- | ---: |
| `ai-assistant` | 52 |
| `token-broker` | 6 |
| `evaluation-harness` | 8 |
| **Total** | **66** |

Coverage highlights:

- **Access control** — the predicate is executed against realistic metadata, including a regression
  test that an anonymous caller cannot reach entitlement documents through an empty `tenantId`, and
  that grouping survives being ANDed with the ticker clause.
- **Routing** — fast path, fan-out, ambiguity deferral, configured thresholds.
- **Ingestion** — filter rules, metadata preservation, policy exemption, idempotent document ids.
- **Tool contract** — no `@Tool` method returns a type Spring AI would discard.
- **Context load** — the whole application context starts; this is what caught three
  startup-blocking defects that compilation could not.
- **Token claims** — tenant and entitlement claims are present and scope escalation is impossible.

**Not proven:** a live end-to-end `/chat` through all four services. That requires Ollama and three
running ports. Tool behaviour is covered with mocked collaborators; the context test proves bean
wiring.

---

## 10. Configuration reference

| Property | Default | Meaning |
| --- | --- | --- |
| `assistant.rag.top-k` | 5 | Chunks retrieved per search |
| `assistant.rag.min-score` | 0.75 | Minimum cosine score to accept a RAG hit |
| `assistant.rag.max-age-hours` | 720 | Freshness window for market chunks |
| `assistant.agent-routing.min-score` | 3 | Score to route without consulting the LLM |
| `assistant.agent-routing.fanout-score-gap` | 3 | Lead required to avoid deferring an ambiguous query |
| `assistant.agent-routing.max-agents` | 3 | Maximum agents per request |
| `assistant.agent-routing.default-agent` | equity-agent | Last-resort route |
| `assistant.agent-execution.core-pool-size` | 4 | |
| `assistant.agent-execution.max-pool-size` | 8 | |
| `assistant.agent-execution.queue-capacity` | 50 | |
| `assistant.agent-execution.timeout-ms` | 8000 | Per-agent timeout |
| `services.connect-timeout-ms` | 1000 | Downstream connect timeout |
| `services.read-timeout-ms` | 3000 | Downstream read timeout |
| `rag.ingestion.delay-ms` | 600000 | Scheduled ingestion interval |
