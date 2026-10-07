# System Diagrams

This page contains interview-friendly diagrams for the secure multi-agent RAG assistant.

## 1. High-Level Design

```mermaid
flowchart LR
    U[Client / User] -->|POST /chat with Keycloak JWT| A[AI Assistant Service :8080]

    A -->|Validate caller JWT| KC[Keycloak]
    A -->|Retrieve relevant chunks| VS[(Vector Store<br/>Qdrant / pgvector / OpenSearch)]
    A -->|Route query| R[Supervisor / Agent Router]

    R --> EA[Equity Agent]
    R --> IA[Index Agent]
    R --> CA[Commodity Agent]

    EA -->|Needs live/private data| TB[Token Broker :8082]
    IA --> TB
    CA --> TB

    TB -->|Client credentials / token exchange| KC
    TB -->|Short-lived scoped JWT| EA
    TB --> IA
    TB --> CA

    EA -->|Bearer token| API[Secured Market API :8083]
    IA -->|Bearer token| API
    CA -->|Bearer token| API

    S[Scheduler / EventBridge] --> Q[SQS Ingestion Queue]
    Q --> W[Ingestion Worker]
    W -->|Fetch news, filings, reports, transcripts| EXT[External / Internal Data Sources]
    W -->|Filter, chunk, embed, add metadata| VS

    A -->|Answer + citations + tool trace| U
```

## 2. Chat Request LLD

```mermaid
sequenceDiagram
    participant User
    participant ChatController
    participant Security as Spring Security
    participant Orchestrator as AgentOrchestrator
    participant Selector as LlmAgentSelector
    participant RAG as RAG Retrieval
    participant Agent as Selected Market Agent
    participant Broker as Token Broker
    participant API as Secured Market API
    participant LLM as Bedrock Converse

    User->>ChatController: POST /chat with bearer token
    ChatController->>Security: Validate Keycloak JWT
    Security-->>ChatController: userId, tenantId, scopes, entitlements

    ChatController->>Orchestrator: chat(message, caller context)
    Orchestrator->>Selector: select(message, agent capabilities)
    Selector->>LLM: routing prompt, low temperature
    LLM-->>Selector: selected agents + confidence
    Selector-->>Orchestrator: RoutingDecision

    par Parallel work
        Orchestrator->>RAG: retrieve chunks with tenant-aware filters
        Orchestrator->>Agent: execute selected domain agent
        Agent->>Broker: request scoped service token
        Broker-->>Agent: short-lived JWT
        Agent->>API: call secured API with bearer token
        API-->>Agent: live market data
    end

    Orchestrator->>LLM: compose answer from chunks + tool results
    LLM-->>Orchestrator: final answer
    Orchestrator-->>ChatController: answer + citations + tool trace
    ChatController-->>User: response
```

## 3. RAG Ingestion Flow

```mermaid
flowchart TD
    TR[EventBridge schedule or POST /rag/ingest] --> C[DataSourceConnector]

    C --> D1[DemoMarketDataConnector<br/>public market data]
    C --> D2[ClientDocumentConnector<br/>tenant-private reports]
    C --> D3[PremiumVendorConnector<br/>entitlement-restricted research]

    D1 --> F[DocumentFilter]
    D2 --> F
    D3 --> F

    F -->|relevant, trusted, valid metadata| CH[DocumentChunker]
    F -->|reject noisy/stale/invalid data| DROP[Drop document]

    CH --> M[ChunkDocumentMapper]
    M --> E[Embedding Model<br/>Bedrock Titan in prod]
    E --> VS[(Vector Store)]

    VS --> R[Runtime RAG Retrieval]
    R --> P[Market Agents]
```

## 4. Tenant-Aware Retrieval

```mermaid
flowchart TD
    Q[User question] --> JWT[Verified JWT claims]
    JWT --> T[tenantId]
    JWT --> G[entitlement groups]

    Q --> Search[Vector similarity search]
    T --> Filter[Access filter]
    G --> Filter
    Search --> Filter

    Filter --> Public[visibility = PUBLIC]
    Filter --> Private[visibility = TENANT_PRIVATE<br/>tenantId = caller tenant]
    Filter --> Entitled[visibility = ENTITLEMENT_RESTRICTED<br/>group in caller entitlements]

    Public --> Allowed[Allowed chunks]
    Private --> Allowed
    Entitled --> Allowed
    Allowed --> LLM[LLM receives only authorized context]
```

## 5. Multi-Agent Fan-Out

```mermaid
flowchart LR
    Q[User query] --> S[Supervisor Router]

    S -->|company / stock| E[Equity Agent]
    S -->|index| I[Index Agent]
    S -->|gold / oil / commodity| C[Commodity Agent]

    E --> EX[CompletableFuture]
    I --> EX
    C --> EX

    EX --> Join[Join successful agent answers]
    Join --> Compose[Final synthesis]
    Compose --> Resp[Answer + citations + selected agents + tool trace]
```

## 6. Production AWS View

```mermaid
flowchart TB
    ALB[Application Load Balancer] --> AS[ECS Fargate<br/>ai-assistant]
    ALB --> TB[ECS Fargate<br/>token-broker]
    AS --> MS[ECS Fargate<br/>market-api]

    AS --> BR[Amazon Bedrock<br/>Converse chat]
    AS --> BE[Amazon Bedrock<br/>Titan embeddings]
    AS --> VS[(Qdrant / OpenSearch Serverless / pgvector)]

    TB --> KC[Keycloak]
    KC --> AUR[(Aurora PostgreSQL<br/>Keycloak DB)]

    EB[EventBridge Scheduler] --> SQS[SQS ingestion queue]
    SQS --> IW[ECS ingestion worker]
    IW --> SRC[Market data / client docs / vendor research]
    IW --> BE
    IW --> VS

    AS --> CW[CloudWatch logs + metrics]
    TB --> CW
    MS --> CW
    IW --> CW
```
