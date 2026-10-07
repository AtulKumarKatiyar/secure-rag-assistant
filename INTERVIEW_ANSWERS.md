# Interview Answers

Concise, grounded answers to the questions most likely to come up. Numbering matches
[INTERVIEW_QA.md](INTERVIEW_QA.md) and the question list.

Where something is a known gap, it says so. Naming your own weaknesses before the interviewer finds
them is stronger than defending them.

Related: [ARCHITECTURE.md](ARCHITECTURE.md), [AGENT_ROUTING.md](AGENT_ROUTING.md),
[FLOW_WALKTHROUGH.md](FLOW_WALKTHROUGH.md), [DEPLOYMENT.md](DEPLOYMENT.md).

---

# Part 1 — RAG Questions (11–30)

## Plain-English Primer: How the RAG Pipeline Actually Works

Read this before the numbered answers. The formal answer to question 11 is written for an interviewer
who already knows RAG; this version is what you would actually say out loud to build up to it.

### The one-sentence version

RAG means: **before anyone asks a question, we take all our documents, cut them into small pieces, and
store them in a way that lets us later find "the pieces most similar to this question."**

Everything else is detail.

### Why there are four stages

Each stage exists because skipping it would cause a specific problem.

| Stage | The problem it solves |
| --- | --- |
| **Connector** | Sources all look different. One returns JSON, one returns files, one returns database rows. |
| **Filter** | Some documents are junk. Storing junk means finding junk later. |
| **Chunker** | A whole document is too big to search precisely and too big to feed the model. |
| **Embed + store** | So we can find the right pieces by *meaning* rather than by keyword. |

### Stage 1 — Connector: put everything in the same box

A **connector** is a small piece of code that knows where to get documents from and how to turn them
into the standard shape. Downstream code never needs to know whether the source was an API, a file or a
database.

That standard shape is `RagDocument`:

```text
id            : NEWS-AAPL-001
ticker        : AAPL
companyName   : Apple Inc.
documentType  : NEWS
title         : Apple expands enterprise AI features
source        : Mock Financial News
url           : https://example.com/news-aapl-001
publishedAt   : 2026-09-15T12:00:00Z
rawText       : "Apple announced new enterprise AI features ..."
metadata      : { visibility: PUBLIC, tenantId: "", sector: Technology }
```

Think of a mailroom. Letters arrive in all sorts of formats. The connector opens each one and puts the
contents into an identical company envelope. After that, nobody downstream cares where it came from.

This project has four connectors: market data, client private research, premium vendor reports, and
policy files.

### Stage 2 — Filter: throw away the junk before storing it

The filter asks a series of yes/no questions, and **one "no" means the document is dropped.**

| Check | Example | Result |
| --- | --- | --- |
| Is it at least 300 characters? | `"AAPL moved slightly in pre-market trading."` (40 chars) | ❌ dropped — too short |
| Is the ticker one we track? | a document about `ZZZZ` | ❌ dropped |
| Is the source trusted? | a document from `"Random Blog"` | ❌ dropped |
| **Do we know who may see it?** | a document with no `visibility` field | ❌ **dropped** |
| Is it recent enough? | a market document from 200 days ago | ❌ dropped |
| Does it mention anything financial? | none of revenue, profit, guidance, earnings, margin, dividend… | ❌ dropped |

A good document passes all six: long enough, known ticker, trusted source, has access metadata, recent,
and contains financial signal.

**Why the access-metadata check matters most.** If a document arrives with no `visibility` and no
`tenantId`, we genuinely do not know whether it is public or belongs to one client. A document we cannot
classify is a document we cannot safely serve, so it never enters the index.

**Why filter at all?** Retrieval can only return what is in the index. Store junk at ingestion and the
search will confidently hand junk to the model, which will then write a confident answer based on it.

### Stage 3 — Chunker: cut big documents into small pieces

One long document causes two problems. It is *about* many things, so embedding it as one lump produces an
averaged-out meaning that matches nothing well. And you cannot feed a whole document into the model's
context for every question.

So we cut it into paragraphs:

```text
Before (one document):
  "Apple announced new enterprise AI features for business customers...
   Analysts said the announcement could support services revenue...
   The news is relevant because investors are watching Apple's services margin..."

After (three chunks):
  Chunk 0: "Apple announced new enterprise AI features for business customers..."
  Chunk 1: "Analysts said the announcement could support services revenue..."
  Chunk 2: "The news is relevant because investors are watching Apple's services margin..."
```

Each chunk becomes its own searchable unit and **inherits a copy of the parent's metadata**, which is why
access control still works after chunking:

```text
chunkId    : NEWS-AAPL-001-chunk-1
documentId : NEWS-AAPL-001
ticker     : AAPL
visibility : PUBLIC          <-- copied from the parent
text       : "Analysts said the announcement could support services revenue..."
```

**Why paragraphs rather than fixed-size slices?** A paragraph usually holds one idea. Cutting at a fixed
character count would slice sentences in half and lose meaning. This is also why tables break — a table is
not a paragraph, so it gets shredded. That is a known weakness in this project.

**Why the chunk id format matters:** it is built from the document id plus a number
(`NEWS-AAPL-001-chunk-1`), so re-ingesting produces the *same* ids and overwrites rather than duplicating.
That makes ingestion safe to re-run.

### Stage 4 — Embed and store: turn meaning into numbers

An **embedding** is a long list of numbers representing the *meaning* of a piece of text. Texts with
similar meaning end up with similar numbers **even when they share no words**:

```text
"Our revenue beat expectations"      -> [0.21, -0.44, 0.87, ...]
"The company posted stronger sales"  -> [0.19, -0.41, 0.85, ...]   <-- close
"The weather in Mumbai is humid"     -> [-0.73, 0.12, -0.02, ...]  <-- far away
```

Keyword search finds nothing in common between the first two. Embeddings know they mean nearly the same
thing.

Each chunk is stored as three things joined together:

| Stored | Purpose |
| --- | --- |
| **numbers** — the embedding | for finding it |
| **text** | what the model eventually reads |
| **metadata** | for filtering relevance and permission |

### Now, query time

A user asks: **"What is happening with Apple's services business?"**

```text
1. Embed the question                          -> [0.20, -0.40, 0.86, ...]

2. Compare those numbers against every stored chunk
   -> chunk NEWS-AAPL-001-chunk-1 scores 0.89
   -> chunk TRANSCRIPT-NVDA-001-chunk-0 scores 0.31

3. At the same time, check the metadata:
      ticker == 'AAPL'                            is it about the right thing?
      visibility == PUBLIC, or tenantId == mine?  am I allowed to see it?
      publishedAtEpochMs recent enough?           is it stale?
      entitlementGroup matches mine?              have I paid for it?

4. Keep the best 5 that pass all of it

5. Put those 5 chunks of TEXT into the model's context and let it answer
```

**The important part: steps 2 and 3 happen together, in one search.** We do not fetch the best matches and
then discard the ones the user may not see. We filter *while* searching.

**Why that matters.** If you fetch the top 5 first and filter afterwards, and all 5 happen to be private
documents belonging to another client, you end up with nothing — even though good public documents sit
further down the list. Filtering inside the search means the 5 slots are spent on documents that are both
relevant *and* permitted.

This is also why access control lives here rather than later: a document the user may not see is never
even a candidate.

### The whole pipeline in one picture

```text
INGESTION  (ahead of time, on a schedule)

  market API ──┐
  client docs ─┼─> Connector ─> Filter ─> Chunker ─> Embed ─> Vector store
  vendor feed ─┤   same box     drop      split into  numbers   numbers
  policies ────┘                junk      paragraphs  per chunk + text + metadata

QUERY  (per question)

  "What is happening with Apple's services business?"
        |
        +--> embed the question
        |
        +--> ONE search doing both at once:
                 find similar numbers
                 AND check ticker / permission / freshness
        |
        +--> top 5 chunks of text  -->  model  -->  answer
```

### If you only remember three sentences

1. **Connectors** put every source into the same box.
2. **The filter and chunker** make sure only good, small, correctly-labelled pieces get stored.
3. **Retrieval** finds pieces that are both *similar in meaning* and *allowed for this user*, in a single
   search.

---

**11. Walk me through the RAG pipeline.**
Four stages. **Ingest** — connectors normalise market data, client research, vendor reports and
policies into one common document shape. **Filter** — reject anything too short, from an unknown
source, stale beyond 180 days, without a financial signal, or missing access metadata. **Chunk** —
split on paragraph boundaries. **Embed and store** — each chunk becomes a vector whose id *is* the
chunk id, so re-ingestion overwrites rather than duplicating.
Retrieval embeds the question, searches by similarity, and applies the access predicate, ticker and
freshness filters *inside* the query rather than afterwards.

**12. How do you chunk, and why that way?**
Split on blank lines into paragraphs, discarding anything under 80 characters. Simple, and good enough
for news and filings. It is the weakest part of the pipeline — it would break a table apart.

**13. What happens when retrieval returns nothing relevant?**
It falls back to the live secured API, returns the real articles, and writes them back into the vector
store so the next similar question is a RAG hit.

**14. How do you decide a retrieved chunk is good enough?**
Cosine similarity of at least **0.75**. If the best result does not clear that, we fall back.

**15. Why 0.75?**
It is configurable. Low enough to catch paraphrases, high enough to reject weak matches.

**16. Why store the timestamp as a number?**
Spring AI rewrites date-looking strings into date literals, and `Instant.toString()` sometimes includes
milliseconds and sometimes does not. Comparing those as text would occasionally sort them wrongly. A
number compares correctly every time.

**17. What about documents that are updated and re-ingested?**
The vector id **is** the chunk id, so re-ingesting the same source overwrites the old vectors.
Ingestion is idempotent.

**18. What stops duplicate vectors accumulating?**
The same mechanism — chunk id as vector id.

**19. How would you measure retrieval quality?**
**I don't currently, and that's the honest gap.** I'd label a set of questions with the chunks that
*should* be retrieved, then measure recall@k and MRR. Right now I measure routing and tool selection,
not retrieval quality.

**20. What is recall@k?**
See [Part 2](#part-2--retrieval-and-generation-metrics).

**21. What about a question spanning several documents?**
The search returns the top 5 chunks, so several documents can be represented in a single answer.

**22. Tables or images?**
Not handled. Paragraph splitting would shred a table. Production needs structure-aware parsing that
keeps tables intact.

**23. Very long documents?**
Chunked into paragraphs, each stored separately.

**24. Would you add reranking?**
Yes. Retrieve roughly 20 candidates by vector similarity, then reorder them with a cross-encoder. That
is usually a bigger quality win than a bigger index.

**25. Hybrid keyword plus vector search?**
Not implemented. Today it is exact metadata matching plus vector similarity. Hybrid would add BM25 for
literal terms such as ticker symbols, which embeddings handle poorly.

**26. What if the embedding model changes?**
**The entire index must be rebuilt.** Different models produce vectors in different spaces, so old and
new vectors cannot be compared. The two models also produce different vector lengths (768 vs 1024), and
Spring AI throws on a length mismatch — but `RagSearchTool` deliberately catches retrieval exceptions
so that an embedding outage degrades to the live API rather than failing the tool. So the exception is
swallowed, logged as one WARN, and the tool quietly answers from the live API instead.

That makes the symptom *"RAG stopped contributing"* rather than a crash — every tool trace reports
`source: "API"`, and the only clue is a single warning line. This is the key risk when moving to
Bedrock / Titan embeddings.

**27. How do you stop stale content being retrieved?**
At ingestion, market documents older than 180 days are rejected. At query time, a 30-day freshness
window is part of the search filter.

**28. Why push the freshness filter into the query?**
If you fetch the top 5 and *then* filter, stale documents can fill all 5 slots and you return nothing
useful. Filtering inside the search means the 5 slots go to eligible documents.

**29. What is in your metadata and why each field?**
`ticker`, `companyName`, `documentType`, `title`, `source`, `url`, `publishedAt`,
`publishedAtEpochMs`, plus `visibility`, `tenantId`, `entitlementGroup`. The first group supports
relevance and citation; the second enforces access control.

**30. Multi-lingual support?**
Not handled. Everything assumes English, including the keyword sets used for routing.

---

# Part 2 — Retrieval and Generation Metrics

Every metric here measures a different half of the system. **Retrieval** metrics ask *"did we find the
right context?"*. **Generation** metrics ask *"did the model use it honestly?"*.

## Retrieval metrics

| Term | Plain meaning | Example |
| --- | --- | --- |
| **Recall@k** | Of all documents that *should* have been found, how many appeared in the top k? | 4 of 5 correct documents in the top 5 → recall@5 = 0.8 |
| **Precision@k** | Of the k results returned, how many were actually relevant? | 3 of 5 relevant → precision@5 = 0.6 |
| **Hit rate** | Did at least *one* relevant document appear at all? The simplest metric. | Yes/No, averaged across queries |
| **MRR** (Mean Reciprocal Rank) | How high up was the *first* correct result? Position 1 → 1.0, position 3 → 0.33. Averaged across queries. | Rewards putting the right chunk first |
| **NDCG** | Like precision, but rewards putting the *most* relevant items highest, and supports graded relevance rather than just relevant/irrelevant | Used when some chunks are "very relevant" and others only "somewhat" |

**Recall versus precision, intuitively:** recall is *"did we miss anything?"*; precision is *"did we
include junk?"*. They trade off directly — raising `top-k` increases recall and decreases precision.

## RAG-specific metrics

| Term | Plain meaning |
| --- | --- |
| **Context recall** | Did we retrieve *everything needed* to answer the question? |
| **Context precision** | Is what we retrieved actually relevant, and well ranked? |
| **Faithfulness / groundedness** | Does the generated answer stick to the retrieved context, or invent things it never said? |
| **Answer relevance** | Does the answer actually address the question that was asked? |

## Why the split matters

If an answer is wrong, the cause is one of two things, and each needs a different fix:

- **Retrieval failed** — the right chunk was never found (`recall@k` is low). Fix chunking, embeddings,
  or add reranking.
- **Generation failed** — the chunk *was* there but the model ignored or embellished it
  (`faithfulness` is low). Fix the prompt, or lower the temperature.

Measuring only one is how teams spend weeks tuning the wrong thing. RAGAS is the common library for the
generation-side metrics.

**Where this project stands:** routing and tool-selection evaluation exists, but there are **no
retrieval metrics at all**. That is the most important gap, because every other RAG improvement is
guesswork without it.

---

# Part 3 — Complete Request Flow (31–40)

**31. Trace a request from the client to the final answer.**
Client gets a JWT from the broker → `POST /chat` with `Bearer` → Spring Security validates signature,
expiry and the `assistant:chat` scope → the controller reads tenant and entitlements from the verified
claims → the selector picks agents → agents run in parallel → each agent runs its own tool-calling loop
→ tools mint narrowly scoped service tokens and call the secured API, or search the vector store →
results are joined and composed → the response carries the answer, the tool trace and the routing
decision.

**32. Where exactly does the agent loop happen?**
Inside Spring AI's `ChatClient.call()`. `agent.answer()` is a single line —
`agent.prompt().user(message).call().content()`. Spring AI performs the model → tool → model
iterations. That is exactly why the response field is `agentCount` and not `iterations`.

**33. Where is the LLM called, and how many times?**

| Call | Count |
| --- | --- |
| Routing | **0** if the fast path fires, **1** if the LLM supervisor is used |
| Each selected agent | 1, plus **1 more per tool round-trip** inside its loop |

A two-agent question is therefore at least 2 model calls, and commonly 4–6 once the agents call tools.

**34. What is synchronous and what is asynchronous?**
The HTTP request is synchronous — the caller waits. The fan-out is *internally* asynchronous (parallel
futures) but the request still blocks until every agent finishes. Ingestion is genuinely asynchronous
and off the chat path.

**35. What does the caller get besides the answer?**
`toolTrace`, `agentSelections`, `agentAnswers`, `agentCount`, `needsClarification`, `mode`.

**36. Why return the tool trace to the client?**
Auditability. You can see which tools ran, with which arguments, and how long each took. It turns
"that answer looks wrong" into something diagnosable without having to reproduce the request.

**37. How many network hops for a cross-asset question?**
Client → ALB → assistant (3), then assistant → broker per service token (2), assistant → market API per
agent (2), plus the vector store if RAG is involved. Roughly **5–7 hops**.

**38. What is on the critical path for latency?**
**Model calls dominate** — the routing call if used, plus each agent's call and its tool round-trips.
Vector search and API calls are an order of magnitude faster.

**39. What happens between the HTTP request and the first model call?**
JWT validation, populating the request-scoped tenant context, and tokenising plus scoring the message
for routing. All of that is microseconds to low milliseconds — it is not where the latency lives.

**40. How does the caller's identity reach the retrieval layer?**
JWT claims → `TenantContext` (request-scoped) → copied into each worker thread by the `TaskDecorator`
→ `RagAccessFilter` reads it → builds the filter used in the vector search. Without the decorator the
worker threads would see a blank tenant and quietly return public documents only.

---

# Part 4 — Multi-Agent and Routing (41–60)

**41. How do you decide which agent handles a question?**
A four-tier cascade. First a **deterministic fast path** scores the message against domain keywords —
equity +3, ticker +2, index +4, commodity +4 — and routes without any model call when there is a single
clear winner, or fans out when a comparison term ("vs", "compare") is present alongside more than one
match. When the fast path is unsure it **defers**, and the **LLM supervisor** decides using each agent's
declared capability. If that fails, a permissive **heuristic fallback** runs; failing that, a configured
**default** agent.
The key choice is that the fast path is deliberately conservative: every ambiguous shape defers, because
deferring costs latency while guessing costs a confidently wrong answer.

**42. What is scoring, exactly?**
A plain number per agent produced by keyword matching — **no model involved**. An equity keyword adds 3,
an all-caps ticker adds 2, an index term adds 4, a commodity term adds 4. Agents scoring zero are
dropped entirely.

**43. Difference between the fast path and the fallback?**
The same scoring, **opposite policy**. The fast path requires a score of at least 3 and is conservative —
it defers when unsure. The fallback accepts any score above 0 and runs only *after* the LLM has already
failed, so being permissive beats returning nothing.

**44. Why is the fast path checked before the LLM router?**
It is free. It saves a model round trip on obvious questions. Because it *pre-empts* the smarter router,
it is written to defer whenever it is not certain.

**45. What happens when the fast path is not confident?**
It returns an empty list, which means "ask the LLM" rather than "no answer".

**46. Why does "Apple vs gold" not route on keywords?**
"Apple" is not written in capitals, so it is not recognised as a ticker, and it is not in the equity
keyword list. Equity scores 0. Only commodity matches, via "gold".

**47. Why does a comparison with only one matched domain defer to the LLM?**
A comparison implies at least two subjects. If only one matched, the keywords probably **missed** the
other. Answering with the single agent would silently drop half the question — so it defers to the LLM,
which knows that Apple is a company.

**48. What does the fan-out score gap do, and can it ever fire?**
It measures the leading agent's margin over the runner-up: a clear leader wins, a close race defers.
**With the default configuration it can never fire.** Qualifying scores are equity 3 or 5, index 4,
commodity 4 — so the largest possible gap between two qualifying agents is 1, while the threshold is 3.
Every multi-domain, non-comparison query therefore defers. Setting `fanout-score-gap: 1` makes the branch
reachable again.

**49. How do you stop the LLM inventing an agent name?**
Returned names are filtered against the real agent set, so a hallucinated name is discarded instead of
causing a lookup failure.

**50. What happens if the LLM router is down?**
The exception is caught and **logged** — never silently swallowed — then the heuristic fallback runs,
then the configured default.

**51. What happens if the router picks the wrong agent?**
The answer is less relevant, but **security is unaffected**: every tool rebuilds its access filter from
verified claims, and the secured API checks its scopes independently. Routing is relevance, not
authorisation.

**52. How do you handle a question spanning two domains?**
It fans out. Both agents run in parallel and the answers are combined with `[agent-name]` section
headers so the caller sees which specialist said what.

**53. What if the question is genuinely unclear?**
The supervisor can set `needsClarification`, and the orchestrator returns a clarifying question with
`agentCount: 0` instead of guessing. That signal used to be swallowed and answered by the default agent —
a bug that is now fixed.

**54. How do you cap the number of agents?**
`max-agents` (default 3), applied in both the fast path and the fallback.

**55. How would you add a fourth agent?**
Implement `MarketAgent`, annotate it `@Component` so it joins the injected list, add a scoring branch if
you want it reachable from the fast path, and describe it in `AgentCapability`. The LLM router picks it up
automatically from the capability catalogue.

**56. Why do policies live on all three agents instead of their own agent?**
Policies are cross-cutting enterprise knowledge, not a market domain. A fourth agent would add routing
surface and another way to misroute, for very little gain.

**57. Do policy questions route correctly today?**
**No.** No capability description mentions policies, so `"What is the remote work policy?"` matches no
keywords and falls through to the LLM or the default agent. It reaches `searchPolicies` only because all
three agents happen to carry that tool. Known gap; the fix is one line in the capability text.

**58. How would you evaluate routing accuracy?**
Label a set of questions with their expected agents, run them through the harness, and measure the match
rate. The harness already asserts expected agents per case.

**59. Could you use a cheaper model just for routing?**
Yes — routing is a short structured JSON classification, which is exactly what a small, fast model is for.
Claude 3 Haiku is already the production default for that reason.

**60. Why not LangGraph / CrewAI / AutoGen?**
Control and auditability. The logic is roughly 200 lines of plain Java, I can explain every routing
decision, and I do not inherit a framework's failure modes. I would revisit that if we needed dozens of
long-running, stateful agents.

---

# Part 5 — Onboarding a New Client Tenant (161)

**No code changes are required**, which is the point of the design:

1. **Register the tenant** in the identity provider — a Keycloak client, or a tenant record in Cognito.
2. **Give their users a `tenant_id` claim**, and an `entitlement_groups` claim if they have bought
   premium content.
3. **Ingest their private documents** with `visibility = TENANT_PRIVATE` and their `tenantId`, through a
   connector like the existing `ClientDocumentConnector`.
4. **Done.** Retrieval filters on the claim automatically, so their users see public documents plus their
   own private content — and nothing else.

Licensed content already carries `entitlementGroup`, so granting access is just adding the group to the
user's claim.

**In production** this becomes one connector per tenant, running on its own schedule through SQS, with the
vector store partitioned on `tenantId`.

---

# Part 6 — The Eight Most Likely Questions

### 11. Walk me through your RAG pipeline.

Four stages. **Ingest** — connectors normalise market data, client research, vendor reports and policies
into one `RagDocument` shape. **Filter** — reject anything too short, from an unknown source, stale beyond
180 days, without a financial signal, or missing access metadata. **Chunk** — split on paragraph
boundaries. **Embed and store** — each chunk becomes a vector whose id is the chunk id, so re-ingestion
overwrites rather than duplicating.

Retrieval embeds the question, searches by similarity, and applies the access predicate, ticker and
freshness filters *inside* the query rather than afterwards. If nothing clears the 0.75 threshold, it falls
back to the live API and writes the result back for next time.

### 41. How do you decide which agent handles a question?

A four-tier cascade. First a **deterministic fast path** scores the message against domain keywords —
equity +3, ticker +2, index +4, commodity +4 — and routes without any model call when there is a single
clear winner, or fans out when a comparison term is present alongside more than one match. When it is
unsure it **defers**, and the **LLM supervisor** decides using each agent's declared capability. If that
fails, a permissive **heuristic fallback** runs; failing that, a configured **default** agent.

The key design choice is that the fast path is deliberately conservative: every ambiguous shape defers,
because deferring costs latency while guessing costs a confidently wrong answer.

### 61. How do you stop one client seeing another client's data?

Every chunk carries `visibility`, `tenantId` and `entitlementGroup`, assigned by the connector at
ingestion. Every retrieval ANDs in a predicate built from the caller's **verified JWT claims** — never
from the request body.

Two details are load-bearing. **First**, each branch is scoped by its own visibility value, because
entitlement documents carry an *empty* `tenantId`; a flattened `PUBLIC OR tenantId == ''` would hand them
to an anonymous caller. **Second**, the whole predicate is bracketed before the ticker and date clauses
are attached, because SpEL binds `and` tighter than `or` — ungrouped, the access check would silently stop
applying to the public branch.

Both properties are covered by tests that *execute* the predicate rather than string-match it.

### 81. What performance work have you done?

Five things. **Parallel fan-out** — cross-asset questions cost the slower agent, not the sum of both.
**Deterministic routing** — obvious queries skip a model round trip entirely. **Tenant-scoped caching**
with TTLs matched to volatility (price 30 s, index/commodity 45 s, news 2 min, RAG 5 min). **Idempotent
ingestion** by chunk id, which protects retrieval quality as well as storage. And **filters pushed into
the query**, so `top-k` is spent on eligible documents.

Plus bounded thread pools, per-agent timeouts, and connect/read timeouts on every downstream call.

### 91. Why four separate modules?

Independent deployability and blast radius. The token broker is security-critical and has a completely
different change cadence from the assistant. The mock API stands in for a real vendor. And it enforces the
boundary honestly — the assistant can only reach market data through an authenticated HTTP call. In a
single process, someone would eventually call the service directly and quietly bypass the token path.

### 110. Which AWS services would you use?

| Component | AWS service |
| --- | --- |
| assistant, broker, market API, worker | **ECS Fargate** behind an **ALB** |
| models | **Bedrock** — Claude 3 Haiku (Converse) + Titan Text Embeddings v2 |
| vector store | **OpenSearch Serverless**, **Aurora + pgvector**, or **Qdrant on ECS** |
| cache | **ElastiCache for Redis** |
| ingestion | **EventBridge → SQS → worker**, with a dead-letter queue |
| identity | **Keycloak** on ECS + **Aurora**, replacing the mock broker |
| secrets, images, logs | **Secrets Manager**, **ECR**, **CloudWatch** (+ X-Ray for tracing) |

Reasoning: Bedrock removes the need to run a model ourselves; Fargate suits long model calls and steady
traffic, unlike Lambda; SQS provides retries and a DLQ with no cluster to operate; and the vector store
sits behind the `VectorStore` interface, so the choice can change without touching application code.

### 125. How many tests do you have, and what do they cover?

**66** — 52 in the assistant, 6 in the broker, 8 in the harness. They cover access-control predicates
(executed, not string-matched), ingestion filtering and metadata mapping, routing thresholds and deferral,
tool contracts, token claims, and a full **context-load** test that starts the whole application.

That context test is the most valuable one, because it caught three startup-blocking defects that
compilation did not — a bean-name collision, and Spring AI silently discarding every `@Tool` method that
returned `Object`.

### 150. Is this production-ready?

**No, and it is not meant to be** — it is an architecture reference that runs locally. The *design* is
production-shaped: separate services, scoped short-lived tokens, claims-based tenant isolation, bounded
concurrency, idempotent ingestion, graceful degradation. The *implementations* of the vector store and
cache are in-memory; there is no infrastructure-as-code, no circuit breakers, no distributed tracing, and
no health endpoint for a load balancer to poll.

Being precise about that boundary is more credible than claiming otherwise.

---

## Where to focus

**Strongest material:** **61** (tenant isolation, and the two predicate subtleties) and **125** (the
context test catching three bugs compilation missed).

**Name these before you are caught by them:** **19** (no retrieval metrics) and **48** (a config value
that can never fire).
