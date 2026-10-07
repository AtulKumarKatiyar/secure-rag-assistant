# Complete Request Flow — Worked Example

This document walks through one request from start to finish, covering every phase the system
performs: authentication, agent selection, parallel fan-out, RAG retrieval, live API calls, caching,
metadata filtering, and public-versus-private segregation.

It is written to be read aloud or talked over. The routing decision shown here was **verified by
executing the real selector**; the tool choices are representative, because the model decides which
tools to call.

Related documents: [ARCHITECTURE.md](ARCHITECTURE.md), [AGENT_ROUTING.md](AGENT_ROUTING.md).

---

## The example

**User:** alice — employee `1001`, tenant `clientA`, entitlement `premium-research`

**Question:**

```text
Compare AAPL's latest news and current price with gold
```

This question was chosen deliberately, because it is the one query that touches every phase: it
selects **two** agents, makes the equity agent use **both RAG and the live API**, makes the commodity
agent use the live API, exercises **caching** twice over, and exercises **tenant and entitlement
filtering**.

---

## Phase 1 — The client gets a token

Before it can call the assistant, the client asks the token broker for a token.

```http
POST http://localhost:8082/token
Content-Type: application/json

{ "username": "alice", "password": "password",
  "employeeId": "1001", "scopes": ["assistant:chat"] }
```

The broker looks alice up, checks the password and employee id, and then does something important: it
takes the scopes the client **asked for** and keeps only the ones alice is actually **allowed** to
have. A client can therefore never grant itself extra permissions simply by requesting them.

It then signs a JWT that lives for 120 seconds and contains:

```text
issuer             : mock-token-broker
subject            : alice
employee_id        : 1001
tenant_id          : clientA                     <-- who she belongs to
entitlement_groups : [premium-research]          <-- what she is entitled to
scope              : assistant:chat
expires            : 120 seconds from now
```

Those two claims — `tenant_id` and `entitlement_groups` — are the foundation of everything that
follows. They live inside a signed token, so the client cannot alter them.

---

## Phase 2 — The client calls the assistant

```http
POST http://localhost:8080/chat
Authorization: Bearer <the token>
Content-Type: application/json

{ "message": "Compare AAPL's latest news and current price with gold" }
```

The request body contains **only the message**. Earlier in this project's life the body also carried
`tenantId` and `entitlementGroups`, which meant any caller could simply claim to be another client.
That is gone.

---

## Phase 3 — The assistant verifies who is asking

Spring Security checks three things before the request reaches any application code:

1. the signature is valid,
2. the token has not expired,
3. the scope list contains `assistant:chat`.

If the scope is missing, the request is rejected with **403** before anything else runs.

Once the request is allowed through, the controller reads the verified token and stores the identity
for this request only:

```java
tenantContext.setFromToken(jwt);
// tenantId          = "clientA"
// entitlementGroups = ["premium-research"]
```

If the token had no `tenant_id` claim, the tenant would stay blank, and the effect would be that the
caller sees public documents only. The system **fails closed** rather than failing open.

---

## Phase 4 — Choosing the agent, or agents

The orchestrator asks the selector which agents should handle this question. Running the real selector
on this exact sentence produces:

```text
tokens : [compare, aapl, s, latest, news, current, price, gold]
```

Then each agent is scored:

| Agent | What matched | Score |
| --- | --- | ---: |
| `equity-agent` | the word "price" (+3) and the capitalised symbol "AAPL" (+2) | **5.0** |
| `commodity-agent` | the word "gold" (+4) | **4.0** |
| `index-agent` | nothing matched | 0 → dropped |

Because the sentence contains the comparison word **"compare"**, and because **more than one** agent
passed the threshold, the fast path decides to **fan out**. The verified result is:

```text
equity-agent     score=5.0  reason=heuristic: equity-term, ticker-like-symbol
commodity-agent  score=4.0  reason=heuristic: commodity-term
```

The important point: **the LLM was never consulted.** The keyword scoring was confident enough on its
own, so a model round trip was saved. The LLM supervisor router is only used when the fast path is
unsure.

See [AGENT_ROUTING.md](AGENT_ROUTING.md) for the full cascade and the rules behind it.

---

## Phase 5 — Both agents run at the same time

The orchestrator does not run the agents one after another. It submits both to a thread pool and waits
for both:

```java
CompletableFuture.supplyAsync(() -> agent.answer(message), agentExecutor)
                 .orTimeout(8000, TimeUnit.MILLISECONDS)
```

So the total wait is roughly the **slower of the two**, not the sum of both.

One detail here matters more than it looks. `TenantContext` and the tool-trace recorder are
request-scoped, meaning they normally exist only on the original request thread. A **task decorator**
copies the request into each worker thread. Without it, the agents would run with no tenant at all —
and because the design fails closed, they would silently return public documents only, instead of
throwing an error anyone would notice.

---

## Phase 6 — The equity agent retrieves context (RAG)

The equity agent is a single model call wrapped with its own system prompt and its own four tools. The
model chooses which tools to call, so the exact sequence can vary, but for this question it would
typically call `searchStockNews` and then `getLiveStockPrice`.

### 6a. Checking the cache first

The first thing the tool does is build a cache key and look it up. Notice the key begins with the
tenant and the entitlement groups:

```text
clientA:premium-research:rag:AAPL:latest news outlook    ->  MISS (first request)
```

Because the tenant is part of the key, an answer cached for one client can never be served to a
different client.

### 6b. Building the access filter

On a cache miss, the tool builds a metadata filter from the caller's verified claims. This is the heart
of the security model. In readable form:

```text
(
     visibility == 'PUBLIC'
  OR (visibility == 'TENANT_PRIVATE'         AND tenantId == 'clientA')
  OR (visibility == 'ENTITLEMENT_RESTRICTED' AND entitlementGroup == 'premium-research')
)
AND ticker == 'AAPL'
AND publishedAtEpochMs >= <30 days ago>
```

Two subtle things are load-bearing here.

**First, each branch is scoped by its own visibility value.** You might wonder why the filter is not
simply "public, or mine, or my entitlements" in one flat list. The reason is that entitlement documents
are stored with an **empty `tenantId`**. If the filter were written as a flat
`PUBLIC OR tenantId == ''`, an anonymous caller would match those entitlement documents through the
empty tenant, and paid content would leak to everyone. Requiring
`visibility == 'ENTITLEMENT_RESTRICTED'` before checking the group closes that hole.

**Second, the whole block is wrapped in brackets** before the ticker and date conditions are attached.
Without those brackets the expression would be read as
"public, **or** tenant-private-and-AAPL-and-recent", which would mean the freshness and ticker rules
stopped applying to public documents.

### 6c. Searching the vector store

The query text is converted into an embedding, and the vector store compares it against every stored
chunk, keeping only those that pass the filter and score at least **0.75** by cosine similarity. It
returns the best **5**.

Here is what the corpus holds for AAPL, and what the filter decides about each document:

| Stored document | Visibility | tenantId | Passes? | Why |
| --- | --- | --- | :---: | --- |
| `NEWS-AAPL-001` | PUBLIC | `""` | ✅ | public, right ticker |
| `FILING-AAPL-10Q-001` | PUBLIC | `""` | ✅ | public, right ticker |
| `CLIENTA-AAPL-RESEARCH-001` | TENANT_PRIVATE | `clientA` | ✅ | alice's own tenant |
| `CLIENTB-NVDA-RESEARCH-001` | TENANT_PRIVATE | `clientB` | ❌ | wrong tenant **and** wrong ticker |
| `PREMIUM-GOLD-OUTLOOK-001` | ENTITLEMENT_RESTRICTED | `""` | ❌ | ticker is GOLD, not AAPL |
| `PREMIUM-NIFTY-SECTOR-001` | ENTITLEMENT_RESTRICTED | `""` | ❌ | ticker is NIFTY50, not AAPL |

So alice sees public Apple documents **plus her own firm's private Apple research note**. She does
**not** see client B's private memo, and she does not see the premium gold and NIFTY reports either —
not because she lacks the entitlement, but because those reports concern different instruments than
the one she asked about.

### 6d. What each chunk carries

Every returned chunk is not just text. It carries the metadata attached at ingestion time:

```text
chunkId            : CLIENTA-AAPL-RESEARCH-001-chunk-0
documentId         : CLIENTA-AAPL-RESEARCH-001
ticker             : AAPL
companyName        : Apple Inc.
documentType       : ANALYST_COMMENTARY
title              : Client A internal Apple investment note
source             : Client Research Portal
url                : internal://clientA/research/CLIENTA-AAPL-RESEARCH-001
publishedAt        : 2026-10-06T11:20:00Z
publishedAtEpochMs : 1759749600000
visibility         : TENANT_PRIVATE        <-- who may see it
tenantId           : clientA
entitlementGroup   : (not set)
portfolio          : clientA-growth
analystDesk        : Client A Research
```

The publication time is stored **twice on purpose**. The human-readable string is for display, and the
numeric `publishedAtEpochMs` is what the filter compares against. Spring AI's filter converter rewrites
strings that look like dates, and `Instant.toString()` sometimes includes milliseconds and sometimes
does not, so comparing those strings as text would occasionally order them wrongly. A number compares
correctly every time.

The chunk text itself is also stored, and the model finally receives the returned chunks as context.

### 6e. The live price call

The model also wants the current price, so it calls `getLiveStockPrice("AAPL")`. That tool takes a
different route:

1. It checks its cache with the key `clientA:stock:price:AAPL` — a **miss** on the first request.
2. It asks the token broker to mint a **service token** for exactly one scope: `stock-news:read`.
3. It calls the secured API with that token.
4. The API verifies the JWT and enforces
   `@PreAuthorize("hasAuthority('SCOPE_stock-news:read')")` before running the method.
5. The result — `{ticker: AAPL, price: 231.45, currency: USD, asOf: ...}` — is cached for
   **30 seconds**.

There is a deliberate design point in step 2. The token the tools mint is **not** the caller's token.
It is a fresh service token carrying only market-data scopes. It never carries `assistant:chat`, so
even if it leaked it could not be used to call `/chat`. This limits the damage a compromised tool token
could do.

Cache lifetimes differ by source on purpose, because different data goes stale at different speeds:

| Data | TTL | Reasoning |
| --- | ---: | --- |
| Live price | 30 s | moves constantly |
| Index / commodity snapshot | 45 s | moves quickly, but not tick by tick |
| News | 2 min | changes on the scale of minutes |
| RAG result | 5 min | derived from already-ingested content |
| API fallback result | 1 min | transient until written through to the vector store |

---

## Phase 7 — The commodity agent fetches its data

In parallel, the commodity agent calls `getCommodityData("GOLD")`, which follows the same pattern:

1. cache lookup under `clientA:commodity:GOLD` — miss,
2. mint a token with scope `commodity-data:read`,
3. call the secured API,
4. the API verifies `SCOPE_commodity-data:read`,
5. cache the result for **45 seconds**.

Notice that the commodity agent has **no access to stock or news tools at all**. It holds only
`getCommodityData` and `searchPolicies`. This is what keeps each agent narrow: even if the model were
persuaded to ask for a stock price, that tool is simply not in its list.

---

## Phase 8 — Every tool call is recorded

Throughout both agents, each tool call writes an entry into a shared per-request trace:

```json
{ "tool": "searchStockNews",   "args": "{ticker=AAPL, query=latest news outlook}", "ok": true, "durationMs": 412 }
{ "tool": "getLiveStockPrice", "args": "{ticker=AAPL}",                            "ok": true, "durationMs": 96  }
{ "tool": "getCommodityData",  "args": "{commoditySymbol=GOLD}",                   "ok": true, "durationMs": 88  }
```

This is what makes a wrong or slow answer diagnosable after the fact rather than something you can only
guess at.

---

## Phase 9 — Combining the answers

Once both agents finish, the orchestrator joins their results. Because there is more than one, it wraps
them so the caller can see which specialist said what:

```text
Multi-agent answer:

[equity-agent]
Apple's recent coverage focuses on enterprise AI features and services revenue...
The stock is currently trading at 231.45 USD.

[commodity-agent]
Gold is at 3,875.20 USD per troy ounce, up 0.31% today...
```

If only one agent had been selected, its answer would be returned as-is with no wrapper.

---

## Phase 10 — The response

```json
{
  "answer": "Multi-agent answer: ...",
  "toolTrace": [ "...three entries..." ],
  "mode": "AGENT",
  "agentCount": 2,
  "needsClarification": false,
  "agentSelections": [
    { "agentName": "equity-agent",    "score": 5.0, "reason": "heuristic: equity-term, ticker-like-symbol" },
    { "agentName": "commodity-agent", "score": 4.0, "reason": "heuristic: commodity-term" }
  ],
  "agentAnswers": [
    { "agentName": "equity-agent",    "answer": "..." },
    { "agentName": "commodity-agent", "answer": "..." }
  ]
}
```

The answer is only part of the response. The routing decision and the full tool trace come back too,
so anyone reviewing a response can see exactly which agents ran, why they were chosen, and which tools
were called.

---

## Phase 11 — What happens on the second identical request

If the same user asks the same question again within the cache lifetimes, very little work happens:

| Step | First request | Second request |
| --- | --- | --- |
| Routing | tokenise + score | tokenise + score |
| `searchStockNews` | embed + vector search | **cache hit** (5 min) |
| `getLiveStockPrice` | mint token + API call | **cache hit** (30 s) |
| `getCommodityData` | mint token + API call | **cache hit** (45 s) |
| Service tokens minted | 2 | **0** |
| External API calls | 2 | **0** |

The model still runs, because the *answer text* is not cached — only the tool data is. So the saving is
real but modest, which is worth being honest about rather than overstating.

---

## The three ideas to lead with

**1. Identity is never taken from the request.**
It comes from a signed token, and the same claims drive both the routing context and the data filter.

**2. Access control is applied inside the query, not after it.**
The filter is part of the vector search, so documents the caller may not see are never even candidates.
Filtering afterwards would be slower and easier to get wrong.

**3. Routing affects relevance, never permission.**
Even if the router had picked the wrong agent, that agent would still have to pass the same access
filter and the same API scope checks. This is precisely why it is safe to let a language model take
part in routing at all.

---

## One caveat to state plainly

The **routing decision, the access filter and the cache keys are deterministic** and are covered by
tests. The **specific tools an agent calls are chosen by the model**, so the tool trace above is
representative rather than guaranteed. Being clear about which parts are deterministic and which are
model-driven is more credible than implying the whole flow is fixed.
