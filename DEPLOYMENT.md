# Deployment and AWS Architecture

How the assistant is deployed today, how it would be deployed on AWS, and why each service was
chosen.

**Status:** the AWS deployment is a **target architecture, not an implemented one**. There are no
Dockerfiles and no infrastructure-as-code in this repository. This document is explicit about the
line between what is built and what is planned.

Related: [ARCHITECTURE.md](ARCHITECTURE.md), [FLOW_WALKTHROUGH.md](FLOW_WALKTHROUGH.md),
[DIAGRAMS.md](DIAGRAMS.md).

---

## 1. What exists today

Four Spring Boot modules, each producing an executable jar, run locally against Ollama and three ports:

| Module | Port | Notes |
| --- | ---: | --- |
| `ai-assistant` | 8080 | `/chat`, `/rag/ingest` |
| `token-broker` | 8082 | Mock HS256 token minting |
| `mock-stock-api` | 8083 | Secured market-data backend |
| `evaluation-harness` | — | Runs against a live stack |

**Not present:** Dockerfiles, Terraform, CDK, ECS task definitions, CI/CD pipelines.

**Not production-shaped:** the vector store and the cache are in-memory, so all state is lost on
restart and neither can be shared across instances. This is the single biggest blocker to running more
than one task.

---

## 2. What is already prepared for AWS

The `prod` profile already switches the model provider rather than merely promising to. The Bedrock
starters (`spring-ai-starter-model-bedrock-converse`, `spring-ai-starter-model-bedrock`) are on the
classpath.

```yaml
spring:
  ai:
    model:
      chat: bedrock-converse
      embedding: bedrock-titan
    bedrock:
      aws:
        region: ap-south-1
        timeout: 5m
        connection-timeout: 5s
      converse:
        chat:
          options:
            model: anthropic.claude-3-haiku-20240307-v1:0
            temperature: 0.1
            max-tokens: 1200
      titan:
        embedding:
          model: amazon.titan-embed-text-v2:0
```

Also already in place:

- Every setting is environment-variable driven, so nothing needs rebuilding per environment.
- `/chat` validates tokens against a real `issuer-uri` under `prod` — there is **no shared-secret
  fallback**.
- Service credentials have **no default values** under `prod`, so a missing secret fails startup
  loudly instead of silently falling back to the demo password.
- Retrieval and caching sit behind interfaces (`VectorStore`, `ProductionCache`), so the in-memory
  implementations can be swapped without touching application logic.

---

## 3. Target AWS architecture

| Today | AWS service | Why this one |
| --- | --- | --- |
| `ai-assistant` jar | **ECS Fargate** service behind an **ALB** | Long model calls (8 s per-agent timeout, parallel fan-out) and a warm connection pool to the vector store. Steady traffic, so Lambda's cold starts are pure downside. |
| `token-broker` (mock) | **Keycloak on ECS Fargate** + **Aurora PostgreSQL** | Realms, clients, and custom claims (`tenant_id`, `entitlement_groups`). Cognito is simpler but resists fine-grained custom scopes. |
| `mock-stock-api` | ECS service behind an **internal ALB** | Stands in for the real market-data vendor. Internal only, never public. |
| Ollama | **Amazon Bedrock** — Claude 3 Haiku via Converse, Titan Text Embeddings v2 | Removes the GPU/ops burden, pay per token, and IAM auth instead of API keys to rotate. |
| `SimpleVectorStore` | **Aurora + pgvector**, **OpenSearch Serverless**, or **Qdrant on ECS** | See §4.3. |
| `InMemoryProductionCache` | **ElastiCache for Redis** | A shared cache across tasks; per-process caching breaks with more than one replica. |
| In-process ingestion scheduler | **EventBridge Scheduler → SQS → ECS worker** | Retries, dead-letter queue, burst buffer, and scaling independent of the chat path. |
| SLF4J logs | **CloudWatch Logs** | |
| Tool trace | **CloudWatch metrics + X-Ray** (Micrometer / OpenTelemetry) | **Not yet wired** — a gap, not a claim. |
| Environment variables | **Secrets Manager** + **SSM Parameter Store** | Injected into the task at start; nothing sensitive in the image. |
| Container images | **Amazon ECR** | |
| Infrastructure | **AWS CDK** (or Terraform) | Not written yet. |

---

## 4. The decisions worth defending

### 4.1 Why Bedrock instead of self-hosted Ollama

Running `llama3.2` at any real volume means GPU instances (or CPU instances that are painfully slow),
and you own patching, scaling and model upgrades. Bedrock turns that into an API call with IAM auth.

It also gets a materially better model. The supervisor router is the one place where model quality
directly changes answer relevance, and it only needs to emit a short structured JSON classification —
so a small, fast, cheap model is exactly right. `max-tokens: 1200` reflects that: routing output is
tiny.

### 4.2 ⚠️ Changing the embedding model invalidates the entire index

This is the most important operational consequence of moving to AWS, and the easiest to miss.

Ingestion currently uses `nomic-embed-text`. Production uses Titan Text Embeddings v2. Those produce
vectors in **different spaces**, so they cannot be mixed: a query embedded with Titan will never match
documents embedded with nomic.

The failure is easy to miss because **it does not look like a failure**. The two models also produce
different vector lengths (768 vs 1024), and Spring AI's `EmbeddingMath.cosineSimilarity` throws
`IllegalArgumentException("Vectors lengths must be equal")` on a mismatch — but `RagSearchTool`
deliberately catches retrieval exceptions so an embedding outage degrades to the live API. So the
exception is swallowed, logged as a single WARN, and the tool quietly answers from the live API
instead.

The visible symptom is therefore *"RAG stopped contributing"*, not a crash: every tool trace reports
`source: "API"`, no ingested document is ever retrieved, and the only clue is one warning line at
startup or first query. Worth knowing before you go looking for a bug in the retrieval logic.

Moving to Bedrock therefore requires **re-ingesting the whole corpus**. Two existing design choices
make that safe rather than messy:

- the vector id is the **chunk id**, so re-ingestion overwrites rather than duplicating;
- `publishedAtEpochMs` is stored numerically, so the freshness filter stays correct across the rebuild.

### 4.3 Vector store selection

The deciding factor is that this application filters aggressively on metadata **before** similarity:
`visibility`, `tenantId`, `entitlementGroup`, `ticker`, `publishedAtEpochMs`, `documentType`.
Filtered approximate-nearest-neighbour search behaves very differently from unfiltered search, so the
real question is how well each engine indexes payload fields.

| Option | Strength | Weakness |
| --- | --- | --- |
| **Aurora + pgvector** | One fewer system; transactional; easy joins to relational data | Filtered ANN degrades as the corpus grows; manual index tuning |
| **OpenSearch Serverless (vector)** | Fully managed; excellent metadata filtering; hybrid keyword + vector search | Cost floor; opinionated Serverless limits |
| **Qdrant on ECS** | Best filtering performance per rupee | You operate it |

Recommendation: start with **Aurora + pgvector** to keep the footprint small, and move to
**OpenSearch Serverless** once query volume or filtered-ANN latency justifies it. All three sit behind
the existing `VectorStore` interface, so switching is a configuration and ingestion change rather than
an application rewrite.

### 4.4 Why SQS for ingestion and not Kafka

The volume is modest and the workload is a simple fan-out of "fetch, embed, store". SQS provides
retries, a dead-letter queue and back-pressure with no cluster to operate. Kafka would add operational
burden for no benefit at this scale.

The important property is not the queue technology — it is that ingestion is **off the synchronous chat
path**, so a slow or failing embedding job can never slow down a user's question.

### 4.5 Why Fargate for the assistant but possibly Lambda for the worker

The chat path holds a request open across multiple model and tool calls, needs a warm connection pool,
and has steady traffic. Lambda's advantages do not apply and its 15-minute ceiling and cold starts are
pure downside.

The **ingestion worker** is the opposite: event-driven, bursty, and stateless per message. That makes it
a legitimate Lambda candidate if you want to avoid a second always-on service.

### 4.6 Model tiering

Not every step needs the same model. Routing is a classification task — small and fast. Specialist
agent answers and final synthesis can use a larger model if quality demands it. `spring.ai.model.chat`
being configurable means this can be tuned per environment without code changes.

---

## 5. Networking and security

```text
Internet
   |
  ALB        public subnets, ACM certificate, AWS WAF
   |
   +-- ai-assistant       ECS Fargate   private subnets
   +-- token-broker       ECS Fargate   private  ->  Aurora (private)
   +-- market-api         ECS Fargate   private, internal ALB only
   +-- ingestion worker   ECS Fargate   private
         |
         +-- Bedrock            via VPC endpoint (PrivateLink)
         +-- OpenSearch / Aurora / ElastiCache   private subnets, security-group scoped
         +-- Secrets Manager    via VPC endpoint
```

Design points:

- **Only the ALB is internet-facing.** Every service runs in private subnets.
- **Bedrock and Secrets Manager are reached over VPC endpoints**, so prompts and credentials never
  traverse the public internet.
- **No long-lived credentials.** ECS task roles grant Bedrock, S3 and Secrets access.
- **Tenant isolation is enforced in the application**, not by network segmentation, because tenants
  share the same vector store. This is why the access predicate is built from verified JWT claims on
  every retrieval.

---

## 6. What is still missing before this can be deployed

| # | Gap | Impact |
| --- | --- | --- |
| 1 | **No Dockerfile per module** | Nothing to push to ECR |
| 2 | **No health endpoint** — `spring-boot-starter-actuator` is not a dependency, so there is no `/actuator/health` | An ALB target group has nothing to poll. **Blocker for load balancing.** |
| 3 | **No infrastructure-as-code** | VPC, subnets, ECS services, Aurora, SQS, EventBridge, IAM roles all undefined |
| 4 | **No tracing export** | No p95 latency by agent, no tool error rate |
| 5 | **No circuit breakers or retries** on Bedrock and market-data calls | A slow dependency is absorbed by timeouts rather than failing fast |
| 6 | **No rate limiting** | One `/chat` request can fan out to several model calls, so abuse is amplified |
| 7 | **Model availability in `ap-south-1`** | Claude models are not in every region; may need cross-region inference or a different region — a **data-residency** conversation, not just a config value |
| 8 | **No CI/CD pipeline** | Build, test, image push and deploy are manual |

Gaps 1 and 2 are the smallest changes with the largest credibility gain, because together they turn
"it runs on my machine" into "it can run behind a load balancer".

---

## 7. Migration path

| Phase | Work | Outcome |
| --- | --- | --- |
| **0 — today** | Local, Ollama, in-memory stores | Runs; 66 tests pass |
| **1** | Bedrock + Dockerfile + ECR + ECS Fargate + ALB + Secrets Manager + CloudWatch + actuator health | Stateless and deployable; no GPU to operate |
| **2** | ElastiCache Redis + managed vector store; **re-ingest with Titan embeddings** | Horizontally scalable; shared state |
| **3** | EventBridge scheduling → SQS → worker service with DLQ | Ingestion decoupled, retryable and independently scalable |
| **4** | Keycloak replacing the mock broker; X-Ray tracing; WAF; autoscaling; CI/CD | Production-grade identity and operations |

Phase 1 delivers the most change for the least effort, because it removes the hardest piece of
infrastructure (running a model yourself) and makes every service container-deployable.

---

## 8. Cost considerations to raise with the client

- **Bedrock is per token.** Routing adds a model call for ambiguous queries, so fast-path routing is a
  direct cost control as well as a latency one. The `fanout-score-gap` setting matters here too.
- **OpenSearch Serverless has an hourly floor** even when idle, which is significant for a
  low-traffic pilot. Aurora + pgvector is usually cheaper at low volume.
- **NAT Gateway and interface VPC endpoints are billed hourly.** For a small deployment these can
  exceed the compute cost, so the network design is a real cost decision, not just a security one.
- **Redis and Aurora are stateful services** — they dominate the bill at low traffic, which argues for
  starting with pgvector on Aurora rather than running both Aurora and OpenSearch.
- **Multi-AZ doubles stateful cost.** Required for production, worth confirming with the client before
  the pilot.
