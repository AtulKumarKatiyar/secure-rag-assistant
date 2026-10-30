# Enterprise Code Auditor Agent: System Design & Implementation with MCP (Model Context Protocol)

An enterprise-grade, production-ready specification and implementation guide for deploying an AI Code Auditor Agent. This system bridges **IntelliJ IDEA (via GitHub Copilot Chat)**, **GitLab Enterprise**, and internal tools (**Jira, SonarQube, Semgrep**) using the **Model Context Protocol (MCP)** and the **GitHub Copilot Chat Cookbook** refactoring engine.

---

## Table of Contents
1. [Executive Summary & System Architecture](#1-executive-summary--system-architecture)
2. [The Copilot Cookbook Deep Dive Engine (11 Refactor Scenarios)](#2-the-copilot-cookbook-deep-dive-engine-11-refactor-scenarios)
3. [The Enterprise MCP Trio (Jira + GitLab + SonarQube)](#3-the-enterprise-mcp-trio-jira--gitlab--sonarqube)
4. [Complete End-to-End Developer Workflow (Local to MR Merge)](#4-complete-end-to-end-developer-workflow-local-to-mr-merge)
5. [Complete Production-Grade Implementation (TypeScript & MCP SDK)](#5-complete-production-grade-implementation-typescript--mcp-sdk)
   - [5.1 `package.json` & `tsconfig.json`](#51-packagejson--tsconfigjson)
   - [5.2 Configuration & `.env.example`](#52-configuration--envexample)
   - [5.3 Webhook HMAC Security Verification (`src/security/hmac.ts`)](#53-webhook-hmac-security-verification-srcsecurityhmacts)
   - [5.4 Standalone SonarQube MCP Server (`src/servers/sonar-mcp.ts`)](#54-standalone-sonarqube-mcp-server-srcserverssonar-mcpts)
   - [5.5 Full MCP Orchestrator & SSE Host (`src/server.ts`)](#55-full-mcp-orchestrator--sse-host-srcserverts)
   - [5.6 Production `Dockerfile`](#56-production-dockerfile)
6. [AWS ECS on Fargate Deployment Architecture](#6-aws-ecs-on-fargate-deployment-architecture)
7. [Step-by-Step Setup & Verification Guide](#7-step-by-step-setup--verification-guide)
8. [System Design Interview Cheatsheet (Staff / Principal Level)](#8-system-design-interview-cheatsheet-staff--principal-level)

---

## 1. Executive Summary & System Architecture

### The Problem
In enterprise software teams, source code is typically hosted on **GitLab**, issue tracking is in **Jira**, and static quality gates are enforced by **SonarQube** and **Semgrep**. Meanwhile, developers write code in **IntelliJ IDEA** with **GitHub Copilot**. 

Developers lose 20–30% of their day switching contexts between Jira tabs to check requirements, raw CI/CD job logs in GitLab to diagnose broken pipelines, and SonarQube dashboards to find code smell line numbers.

### The Solution
A unified, private **AI Code Auditor Agent (`@code-auditor`)** deployed on **AWS ECS on Fargate**. The agent functions as an **MCP Host**, connecting dynamically to **MCP Servers** for Jira, GitLab, and SonarQube. It audits local uncommitted code, checks acceptance criteria, diagnoses broken GitLab CI pipelines, and generates drop-in refactors that mathematically guarantee **behavior parity**.

### High-Level Architecture Diagram

```
+---------------------------------------------------------------------------------------+
|  DEVELOPER WORKSTATION                                                                |
|  IntelliJ IDEA (GitHub Copilot Chat Plugin)                                           |
|  Prompt: "@code-auditor Check my code against PAY-204 and fix CI pipeline failures"  |
+-------------------------------------------+-------------------------------------------+
                                            |
                         1. Prompts Agent   |   6. Real-Time Token Stream (SSE)
                                            v
+---------------------------------------------------------------------------------------+
|  GITHUB COPILOT CLOUD (Routing Gateway)                                               |
|  Matches @code-auditor -> Forwards webhook HTTP POST to registered AWS URL            |
+-------------------------------------------+-------------------------------------------+
                                            |
                   2. Webhook Forwarding    |   5. SSE Chunks ("data: { choices: ... }")
                      (HMAC-SHA256 Signed)  |
                                            v
+---------------------------------------------------------------------------------------+
|  AWS ECS ON FARGATE (Your Enterprise VPC Private Subnet)                              |
|                                                                                       |
|  +---------------------------------------------------------------------------------+  |
|  | AGENT ORCHESTRATOR SERVICE (Express.js + MCP Client Host)                       |  |
|  | - HMAC Signature Verification (Rejects unauthorized webhooks)                   |  |
|  | - Autonomous Multi-Turn Tool Loop (OpenAI GPT-4o / Claude 3.5 / Gemini)         |  |
|  | - Real-Time Server-Sent Events (SSE) Formatter                                  |  |
|  +----------------------------------------+----------------------------------------+  |
|                                           |                                           |
|                   3. MCP Protocol         |   3. Tool Responses                       |
|                   (JSON-RPC 2.0)          |   (Standardized Schema)                   |
|                                           v                                           |
|  +---------------------------------------------------------------------------------+  |
|  | ENTERPRISE MCP SERVERS (Standardized Tool Abstraction Layer)                    |  |
|  |                                                                                 |  |
|  |  [ Jira MCP Server ]      [ GitLab MCP Server ]     [ SonarQube MCP Server ]    |  |
|  |  - get_issue               - get_pipeline_jobs       - sonar_get_issues         |  |
|  |  - get_acceptance_criteria - get_job_log (Trace)     - sonar_get_quality_gate   |  |
|  |  - add_comment             - get_merge_request_diff  - sonar_get_measures       |  |
|  +-------------+--------------------------+--------------------------+-------------+  |
+----------------|--------------------------|--------------------------|----------------+
                 |                          |                          |
                 v                          v                          v
      [ Jira Cloud / DC ]          [ GitLab Enterprise ]       [ SonarQube Server ]
```

```mermaid
flowchart TD
    subgraph Station["1. Developer Machine"]
        IDEA["IntelliJ IDEA<br/>(Copilot Chat Panel)"]
    end

    subgraph GitHub["2. GitHub Cloud"]
        Gateway["Copilot Gateway<br/>(@code-auditor Webhook Router)"]
    end

    subgraph AWS["3. AWS Fargate (Private VPC)"]
        Orchestrator["Agent Orchestrator<br/>(MCP Host & SSE Adapter)"]
        JiraMCP["Jira MCP Server"]
        GitLabMCP["GitLab MCP Server"]
        SonarMCP["SonarQube MCP Server"]
    end

    subgraph External["4. Enterprise Systems"]
        Jira["Jira Cloud / DC"]
        GitLab["GitLab Enterprise"]
        Sonar["SonarQube Server"]
        LLM["LLM API (GPT-4o / Claude / Gemini)"]
    end

    IDEA -->|"1. User Prompt"| Gateway
    Gateway -->|"2. Webhook POST (HMAC Signed)"| Orchestrator
    Orchestrator <-->|"3. JSON-RPC (Stdio)"| JiraMCP
    Orchestrator <-->|"3. JSON-RPC (Stdio)"| GitLabMCP
    Orchestrator <-->|"3. JSON-RPC (Stdio)"| SonarMCP
    JiraMCP <--> Jira
    GitLabMCP <--> GitLab
    SonarMCP <--> Sonar
    Orchestrator <-->|"4. Multi-Turn Function Calling"| LLM
    Orchestrator -->|"5. SSE Stream"| Gateway
    Gateway -->|"6. Render Stream"| IDEA
```

---

## 2. The Copilot Cookbook Deep Dive Engine (11 Refactor Scenarios)

The core reasoning of `@code-auditor` is governed by the principles of the **Copilot Chat Cookbook Deep Dive: Refactor Code**. The agent evaluates code against an explicit **Refactor Decision Map** across 11 architectural scenarios:

```
                            [ Incoming Code Snippet ]
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
       [ Local Quality Smells ]                    [ Architectural Concerns ]
        - Readability / Naming                      - Cross-Cutting Concerns (AOP)
        - Lint / Checkstyle Errors                  - UI / Logic Coupling
        - Long Methods / Deep Nesting               - Complex Inheritance Hierarchies
        - Resource Leaks & Concurrency              - Data Access & Deadlock Risks
                 │                                             │
                 └──────────────────────┬──────────────────────┘
                                        ▼
                  [ Behavior Parity Invariance Verification ]
                   1. Are input/output signatures preserved?
                   2. Are side effects and exceptions identical?
                   3. Do regression edge-case unit tests pass?
                                        │
                                        ▼
                            [ Drop-In Refactored Code ]
```

### The 11 Core Scenarios & Agent Prompt Rules

1. **Improving Readability & Maintainability:**
   - *Target:* Long methods (>30 lines), high cyclomatic complexity (>10), obscure variable names, magic numbers.
   - *Rule:* Decompose methods into descriptive private helper functions. Replace primitives with domain value objects.

2. **Fixing Lint and Static Analysis Violations:**
   - *Target:* SonarQube, Checkstyle, ESLint, or SpotBugs violations.
   - *Rule:* Fix violations without altering any algorithmic contracts.

3. **Performance and Memory Optimization:**
   - *Target:* N+1 database queries, unbuffered I/O streams, unnecessary object allocations inside loops, inefficient data structures (e.g., `ArrayList.contains()` inside loops instead of `HashSet`).
   - *Rule:* Replace with $O(1)$ lookups, stream pipelines, and connection pooling.

4. **Environmental Sustainability (Green Coding):**
   - *Target:* Idle polling loops, unoptimized regex compilation, heavy thread creation.
   - *Rule:* Replace spin-locks with event-driven notifications (`CompletableFuture`, reactive streams). Cache compiled regex patterns.

5. **Design Pattern Implementation:**
   - *Target:* Sprawling `switch-case` blocks, tangled object creation.
   - *Rule:* Refactor into Strategy, Factory, or Decorator patterns while preserving public entry points.

6. **Data Access Layer Modernization:**
   - *Target:* Hardcoded SQL queries, unclosed `ResultSet`/`PreparedStatement`, direct database coupling in business beans.
   - *Rule:* Isolate into Repository / DAO interfaces using `try-with-resources`.

7. **Decoupling Business Logic from Framework / UI Components:**
   - *Target:* Spring Controller / UI classes containing business validation, transaction handling, or external API calls.
   - *Rule:* Extract clean, framework-agnostic domain service classes.

8. **Handling Cross-Cutting Concerns (AOP):**
   - *Target:* Scattered logging, authorization checks, execution timers, and auditing inside domain methods.
   - *Rule:* Extract into interceptors, decorators, or aspect-oriented annotations (e.g., `@Audited`, `@Timed`).

9. **Simplifying Complex Inheritance Hierarchies:**
   - *Target:* Deep class hierarchies (4+ levels), fragile base-class problems.
   - *Rule:* Refactor to **Composition over Inheritance**. Use interfaces with delegated strategy implementations.

10. **Database Deadlocks, Concurrency & Transaction Boundaries:**
    - *Target:* Inconsistent table lock orders, non-thread-safe singleton instance variables, race conditions, overly broad `@Transactional` annotations.
    - *Rule:* Enforce thread-safe primitives (`AtomicReference`, `ConcurrentHashMap`), shorten transaction scope, and ensure deterministic lock ordering.

11. **Safe Language / Framework Modernization:**
    - *Target:* Legacy Java 8 code to Java 21+ (records, pattern matching, virtual threads, `var`), or legacy Python to typed modern Python.
    - *Rule:* Guarantee 100% functional equivalence while adopting modern language idioms.

### Behavior Parity Guarantee Protocol
Before emitting any refactored code, the agent applies this checklist:
- **Signature Parity:** Public method names, parameter types, and return types must not break existing callers.
- **Exception Parity:** All checked and unchecked exceptions thrown under edge cases must remain unchanged.
- **Side-Effect Parity:** Cache mutations, database updates, message broker writes, and log outputs must trigger in the exact same sequence.
- **Automated Verification:** The agent provides a companion JUnit 5 / AssertJ test suite covering edge cases (null inputs, boundary values, exception paths).

---

## 3. The Enterprise MCP Trio (Jira + GitLab + SonarQube)

Model Context Protocol (MCP) replaces custom, fragile REST wrappers with standardized, self-describing tool servers:

```
+-----------------------------------------------------------------------------------+
|                            ENTERPRISE MCP TRIO                                    |
|                                                                                   |
|  +----------------------+   +----------------------+   +-----------------------+  |
|  |   JIRA MCP SERVER    |   |  GITLAB MCP SERVER   |   |  SONARQUBE MCP SERVER |  |
|  +----------+-----------+   +----------+-----------+   +-----------+-----------+  |
+-------------|--------------------------|---------------------------|--------------+
              |                          |                           |
              v                          v                           v
     "WHAT to build?"           "DID the build pass?        "IS the code clean,
      - User Story & specs       What failed in CI?"         secure & compliant?"
      - Acceptance Criteria      - Pipeline & Job statuses   - Quality Gate status
      - Boundary conditions      - Raw terminal logs/trace   - Open smells & bugs
      - Definition of Done       - MR diffs & comments       - Coverage percentages
```

### Why MCP Solves the $O(N \times M)$ Problem

| Dimension | Legacy Custom Point-to-Point REST | Standardized Model Context Protocol (MCP) |
| :--- | :--- | :--- |
| **Tool Addition** | Writing custom Axios code, regex parsers, and custom auth for every tool. | Plug-and-play: run `npx @atlassian/jira-mcp-server` or `@modelcontextprotocol/server-gitlab`. |
| **Portability** | Locked to one proprietary agent backend. | Any MCP client (Copilot, Claude, Cursor, Antigravity) connects immediately. |
| **Tool Selection** | Hardcoded procedural code (always calls Jira, always calls Sonar). | **Dynamic Agentic Tool Calling:** The LLM decides what to call, in what order, based on context. |
| **Maintenance** | Your team maintains enterprise API integrations when endpoints deprecate. | Maintained directly by tool vendors (Atlassian, GitLab, SonarSource). |

---

## 4. Complete End-to-End Developer Workflow (Local to MR Merge)

```
PHASE 1: LOCAL PRE-COMMIT AUDIT (IntelliJ)
  Developer prompts: "@code-auditor Review my local method against PAY-204"
        │
        ├──► Agent queries Jira MCP (Reads acceptance criteria & retry limits)
        ├──► Agent queries Semgrep MCP (Runs static check on snippet)
        └──► Streams bug diagnosis: Flags non-thread-safe state & provides fix
  Developer applies fix with verified behavior parity.

PHASE 2: COMMIT & MR CREATION
  Developer prompts: "@code-auditor Generate GitLab MR description"
        │
        └──► Agent outputs formatted description with test checklist
  Developer commits & pushes branch to GitLab: git push origin feature/PAY-204
  Developer creates GitLab Merge Request (MR).

PHASE 3: GITLAB CI PIPELINE RUNS (The Repo-Wide Safety Net)
  GitLab CI executes multi-stage pipeline:
  Stage 1: Compile & Unit Tests (mvn test / npm test)
  Stage 2: SonarQube Static Analysis & Quality Gate
  Stage 3: Integration Tests (Testcontainers / Database)
        │
        └──► ❌ CI Pipeline Fails (e.g., Build broken / Quality gate violated)

PHASE 4: POST-PUSH TRIAGE & REMEDIATION VIA GITLAB & SONAR MCP (IntelliJ)
  Developer prompts: "@code-auditor Why did my GitLab CI pipeline fail? Fix it."
        │
        ├──► Agent queries GitLab MCP:
        │     - Calls gitlab.get_pipeline_jobs() -> Identifies failed job: 'mvn-test'
        │     - Calls gitlab.get_job_log() -> Extracts terminal stacktrace & failed tests
        ├──► Agent queries SonarQube MCP:
        │     - Calls sonar_get_issues() -> Fetches code smells & missing coverage
        └──► Streams exact diagnosis & code fix directly into IntelliJ chat!
  Developer applies fix, commits & pushes: git commit -am "fix: resolve CI failure" && git push
  GitLab CI re-runs: ✅ All Stages Turn Green!

PHASE 5: AUTOMATED VERIFICATION & MR MERGE
  MR is approved with full audit trail and merged into main.
```

### Why Phase 3 Can Fail Even If Phase 1 Passed

Phase 1 is a **micro-level inspection** of active editor files. Phase 3 is a **macro-level system integration** across the entire repository:

| Failure Scenario in Phase 3 | Why Phase 1 Didn't Catch It | How Phase 4 (GitLab & Sonar MCP) Resolves It |
| :--- | :--- | :--- |
| **1. Missing Code Coverage** | Phase 1 checked logic correctness, but you forgot new unit tests. SonarQube requires $\ge 80\%$ coverage on new code. | Sonar MCP reports uncovered branches; Agent generates the missing JUnit 5 / Mockito tests. |
| **2. Cross-Module Breaking Change** | You changed a method signature in `PaymentService.java`. Phase 1 only inspected your file, but `OrderController.java` in another module now fails to compile. | GitLab MCP reads the `mvn compile` log, points to `OrderController.java:88`, and provides the updated caller code. |
| **3. Integration Test Failures** | Heavy integration tests (Postgres, Kafka, Docker) only run in CI. A foreign key constraint or DB migration script failed. | GitLab MCP reads the terminal stacktrace from `get_job_log()` and explains the exact database constraint violation. |
| **4. Third-Party Dependency CVEs** | You added an external library in `pom.xml`. Security scanners (Snyk/Semgrep) in CI block the pipeline due to a critical CVE. | GitLab/Semgrep MCP flags the vulnerable dependency and suggests an upgraded, non-vulnerable version. |
| **5. Branch Outdated vs. `main`** | Teammates merged code into `main` while you were working. GitLab CI tests your branch against the latest target branch. | GitLab MCP detects the contract conflict and guides conflict resolution. |

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer (IntelliJ)
    participant Agent as @code-auditor (AWS Fargate MCP Host)
    participant JiraMCP as Jira MCP Server
    participant GLMCP as GitLab MCP Server
    participant SonarMCP as SonarQube MCP Server
    participant GLCI as GitLab CI/CD Pipeline

    Note over Dev, JiraMCP: Phase 1: Local Pre-Commit Audit
    Dev->>Agent: @code-auditor Review method against PAY-204
    Agent->>JiraMCP: Call get_issue(PAY-204)
    JiraMCP-->>Agent: Returns acceptance criteria & retry rules
    Agent-->>Dev: Streams bug fix (concurrency race condition)
    Dev->>Dev: Applies refactored solution

    Note over Dev, GLCI: Phase 2 & 3: Push & CI Execution
    Dev->>GLCI: git push origin feature/PAY-204
    GLCI->>GLCI: Stage 1: Build & Tests (mvn test) -> ❌ FAILED!

    Note over Dev, GLMCP: Phase 4: Post-Push Triage via GitLab MCP
    Dev->>Agent: @code-auditor Why did my GitLab CI pipeline fail? Fix it.
    Agent->>GLMCP: Call get_pipeline_jobs(pipeline_id)
    GLMCP-->>Agent: Returns failed job: 'mvn-test' (job_id: 89412)
    Agent->>GLMCP: Call get_job_log(job_id: 89412)
    GLMCP-->>Agent: Returns stacktrace: "AssertionError: expected 3 retries got 0"
    Agent->>SonarMCP: Call sonar_get_quality_gate()
    SonarMCP-->>Agent: Returns coverage & smells
    Agent-->>Dev: Streams exact diagnosis & code fix for test mock
    Dev->>GLCI: git commit -am "fix: test mock" && git push
    GLCI->>GLCI: CI Re-runs -> ✅ All Stages Green!

    Note over Dev, GLCI: Phase 5: Merge
    GLCI-->>Dev: MR approved & merged into main.
```

---

## 5. Complete Production-Grade Implementation (TypeScript & MCP SDK)

### 5.1 `package.json` & `tsconfig.json`

#### `package.json`
```json
{
  "name": "enterprise-code-auditor-mcp",
  "version": "1.0.0",
  "description": "Production MCP Agent for GitHub Copilot in IntelliJ with GitLab, Jira, and SonarQube",
  "type": "module",
  "scripts": {
    "build": "tsc",
    "start": "node dist/server.js",
    "dev": "tsx watch src/server.ts"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^0.6.0",
    "axios": "^1.7.7",
    "dotenv": "^16.4.5",
    "express": "^4.21.0",
    "openai": "^4.63.0"
  },
  "devDependencies": {
    "@types/express": "^4.17.21",
    "@types/node": "^22.5.4",
    "tsx": "^4.19.0",
    "typescript": "^5.6.2"
  }
}
```

#### `tsconfig.json`
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "lib": ["ES2022"],
    "outDir": "./dist",
    "rootDir": "./src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true
  },
  "include": ["src/**/*"]
}
```

---

### 5.2 Configuration & `.env.example`

```env
# Server Configuration
PORT=3000
NODE_ENV=production
# Secret key configured in GitHub App Developer Settings
GITHUB_WEBHOOK_SECRET=your_github_webhook_hmac_secret_here

# LLM Core Provider
OPENAI_API_KEY=sk-proj-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
LLM_MODEL=gpt-4o

# Atlassian Jira Configuration
JIRA_HOST=your-company.atlassian.net
JIRA_EMAIL=copilot-bot@your-company.com
JIRA_API_TOKEN=your_jira_api_token_here

# GitLab Enterprise Configuration
GITLAB_API_URL=https://gitlab.your-company.com
GITLAB_PERSONAL_TOKEN=glpat-xxxxxxxxxxxxxxxxxxxx

# SonarQube Enterprise Configuration
SONARQUBE_URL=https://sonarqube.your-company.com
SONARQUBE_TOKEN=sqp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
SONARQUBE_DEFAULT_PROJECT=payment-service-backend
```

---

### 5.3 Webhook HMAC Security Verification (`src/security/hmac.ts`)

GitHub Copilot signs all incoming webhooks using HMAC-SHA256 in the `X-Hub-Signature-256` header. The agent **must verify** this signature to prevent unauthorized callers from spoofing queries or executing server tools.

```typescript
import crypto from 'crypto';
import { Request, Response, NextFunction } from 'express';

export function verifyGitHubWebhookSignature(req: Request, res: Response, next: NextFunction): void {
  // Allow bypassing verification only in local development when explicitly configured
  if (process.env.NODE_ENV === 'development' && !process.env.GITHUB_WEBHOOK_SECRET) {
    return next();
  }

  const signature = req.headers['x-hub-signature-256'] as string;
  const secret = process.env.GITHUB_WEBHOOK_SECRET;

  if (!secret) {
    console.error('[Security] GITHUB_WEBHOOK_SECRET is not configured.');
    res.status(500).json({ error: 'Server misconfiguration: Webhook secret missing' });
    return;
  }

  if (!signature) {
    res.status(401).json({ error: 'Unauthorized: Missing X-Hub-Signature-256 header' });
    return;
  }

  const rawBody = JSON.stringify(req.body);
  const computedSignature = `sha256=${crypto
    .createHmac('sha256', secret)
    .update(rawBody, 'utf8')
    .digest('hex')}`;

  const signatureBuffer = Buffer.from(signature, 'utf8');
  const computedBuffer = Buffer.from(computedSignature, 'utf8');

  if (signatureBuffer.length !== computedBuffer.length || !crypto.timingSafeEqual(signatureBuffer, computedBuffer)) {
    res.status(403).json({ error: 'Forbidden: Invalid webhook signature' });
    return;
  }

  next();
}
```

---

### 5.4 Standalone SonarQube MCP Server (`src/servers/sonar-mcp.ts`)

This standalone microservice implements the **Model Context Protocol (MCP)** server over standard input/output (`stdio`), exposing SonarQube Quality Gates, issues, and metrics directly to the LLM.

```typescript
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import axios from 'axios';

const sonarClient = axios.create({
  baseURL: `${process.env.SONARQUBE_URL || 'http://localhost:9000'}/api`,
  auth: {
    username: process.env.SONARQUBE_TOKEN || '',
    password: '',
  },
});

const server = new Server(
  { name: 'sonarqube-mcp-server', version: '1.0.0' },
  { capabilities: { tools: {} } }
);

// Register Available SonarQube Tools
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: 'sonar_get_issues',
        description: 'Fetch open code smells, bugs, and vulnerabilities from SonarQube for a component or project.',
        inputSchema: {
          type: 'object',
          properties: {
            projectKey: { type: 'string', description: 'The SonarQube project key' },
            componentKey: { type: 'string', description: 'Optional: specific file key (e.g. project:src/main/File.java)' },
            severities: { type: 'string', description: 'Comma-separated severities: BLOCKER,CRITICAL,MAJOR,MINOR' },
          },
          required: ['projectKey'],
        },
      },
      {
        name: 'sonar_get_quality_gate',
        description: 'Get the Quality Gate status (PASSED/FAILED) and failing condition details for a project.',
        inputSchema: {
          type: 'object',
          properties: {
            projectKey: { type: 'string', description: 'The SonarQube project key' },
          },
          required: ['projectKey'],
        },
      },
      {
        name: 'sonar_get_measures',
        description: 'Retrieve metric values such as code coverage, cognitive complexity, and duplicated lines.',
        inputSchema: {
          type: 'object',
          properties: {
            componentKey: { type: 'string', description: 'The SonarQube project or file key' },
            metricKeys: {
              type: 'string',
              description: 'Comma-separated metric keys: coverage,cognitive_complexity,duplicated_lines_density,bugs',
            },
          },
          required: ['componentKey', 'metricKeys'],
        },
      },
    ],
  };
});

// Handle Tool Execution
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    if (name === 'sonar_get_issues') {
      const { projectKey, componentKey, severities } = args as any;
      const response = await sonarClient.get('/issues/search', {
        params: {
          componentKeys: componentKey || projectKey,
          statuses: 'OPEN,CONFIRMED,REOPENED',
          severities: severities || 'BLOCKER,CRITICAL,MAJOR',
          ps: 10,
        },
      });

      const formatted = response.data.issues.map((i: any) => ({
        key: i.key,
        rule: i.rule,
        severity: i.severity,
        message: i.message,
        line: i.line,
        component: i.component,
        effort: i.effort,
      }));

      return {
        content: [{ type: 'text', text: JSON.stringify(formatted, null, 2) }],
      };
    }

    if (name === 'sonar_get_quality_gate') {
      const { projectKey } = args as any;
      const response = await sonarClient.get('/qualitygates/project_status', {
        params: { projectKey },
      });

      const status = response.data.projectStatus;
      const result = {
        status: status.status,
        conditions: status.conditions.map((c: any) => ({
          metric: c.metricKey,
          status: c.status,
          actualValue: c.actualValue,
          errorThreshold: c.errorThreshold,
        })),
      };

      return {
        content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
      };
    }

    if (name === 'sonar_get_measures') {
      const { componentKey, metricKeys } = args as any;
      const response = await sonarClient.get('/measures/component', {
        params: { component: componentKey, metricKeys },
      });

      const measures = response.data.component.measures.map((m: any) => ({
        metric: m.metric,
        value: m.value,
      }));

      return {
        content: [{ type: 'text', text: JSON.stringify(measures, null, 2) }],
      };
    }

    throw new Error(`Unknown SonarQube tool: ${name}`);
  } catch (error: any) {
    const errorMsg = error.response?.data?.errors?.[0]?.msg || error.message;
    return {
      content: [{ type: 'text', text: `SonarQube API Error: ${errorMsg}` }],
      isError: true,
    };
  }
});

// Connect to stdio transport
const transport = new StdioServerTransport();
await server.connect(transport);
console.error('[SonarQube MCP Server] Running and listening on stdio.');
```

---

### 5.5 Full MCP Orchestrator & SSE Host (`src/server.ts`)

```typescript
import express, { Request, Response } from 'express';
import dotenv from 'dotenv';
import OpenAI from 'openai';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { verifyGitHubWebhookSignature } from './security/hmac.js';

dotenv.config();

const app = express();
app.use(express.json({ limit: '10mb' }));

const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

// -----------------------------------------------------------------------------
// 1. MCP Client Manager: Connect to Standardized MCP Servers
// -----------------------------------------------------------------------------
interface RegisteredServer {
  name: string;
  client: Client;
  tools: any[];
}

const activeServers: Map<string, RegisteredServer> = new Map();

async function connectToMCPServer(
  name: string,
  command: string,
  args: string[],
  envVars: Record<string, string> = {}
): Promise<void> {
  try {
    const transport = new StdioClientTransport({
      command,
      args,
      env: { ...process.env, ...envVars },
    });

    const client = new Client(
      { name: `copilot-orchestrator-${name}`, version: '1.0.0' },
      { capabilities: {} }
    );

    await client.connect(transport);

    const toolsResult = await client.listTools();
    const mappedTools = toolsResult.tools.map((t) => ({
      type: 'function',
      function: {
        name: `${name}__${t.name}`,
        description: `[From ${name}] ${t.description}`,
        parameters: t.inputSchema,
      },
    }));

    activeServers.set(name, { name, client, tools: mappedTools });
    console.log(`[MCP] Successfully registered ${name} with ${mappedTools.length} tools.`);
  } catch (error: any) {
    console.error(`[MCP] Failed to connect to ${name} server:`, error.message);
  }
}

async function initMCPEcosystem(): Promise<void> {
  // 1. Jira MCP Server (Official Atlassian)
  await connectToMCPServer('jira', 'npx', ['-y', '@atlassian/jira-mcp-server'], {
    JIRA_HOST: process.env.JIRA_HOST || '',
    JIRA_EMAIL: process.env.JIRA_EMAIL || '',
    JIRA_API_TOKEN: process.env.JIRA_API_TOKEN || '',
  });

  // 2. GitLab MCP Server (Official Model Context Protocol)
  await connectToMCPServer('gitlab', 'npx', ['-y', '@modelcontextprotocol/server-gitlab'], {
    GITLAB_API_URL: process.env.GITLAB_API_URL || 'https://gitlab.com',
    GITLAB_PERSONAL_ACCESS_TOKEN: process.env.GITLAB_PERSONAL_TOKEN || '',
  });

  // 3. SonarQube MCP Server (Local Standalone Server)
  await connectToMCPServer('sonar', 'node', ['./dist/servers/sonar-mcp.js'], {
    SONARQUBE_URL: process.env.SONARQUBE_URL || '',
    SONARQUBE_TOKEN: process.env.SONARQUBE_TOKEN || '',
  });
}

// -----------------------------------------------------------------------------
// 2. Tool Execution Engine
// -----------------------------------------------------------------------------
async function executeMCPTool(namespacedToolName: string, args: any): Promise<string> {
  const [serverName, originalToolName] = namespacedToolName.split('__');
  const server = activeServers.get(serverName);

  if (!server) {
    throw new Error(`MCP Server '${serverName}' is not available.`);
  }

  const result = await server.client.callTool({
    name: originalToolName,
    arguments: args,
  });

  if (Array.isArray(result.content)) {
    return result.content.map((c: any) => c.text || JSON.stringify(c)).join('\n');
  }
  return JSON.stringify(result.content);
}

// -----------------------------------------------------------------------------
// 3. GitHub Copilot Webhook Endpoint with Streaming SSE
// -----------------------------------------------------------------------------
app.post('/agent', verifyGitHubWebhookSignature, async (req: Request, res: Response): Promise<void> => {
  const { messages } = req.body;

  // Initialize Server-Sent Events (SSE) Stream
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');

  const sendSSEChunk = (text: string) => {
    res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: text } }] })}\n\n`);
  };

  try {
    sendSSEChunk('🔍 *Connecting to Enterprise MCP Orchestrator...*\n\n');

    // Aggregate all tools across active MCP servers
    const allTools = Array.from(activeServers.values()).flatMap((s) => s.tools);

    const systemPrompt = `
You are the **Senior Enterprise Code Reviewer & Bug Auditor Agent**.
Your organization uses **GitLab** for source code, **Jira** for ticket requirements, and **SonarQube** for static quality gates.

You have access to tools via the **Model Context Protocol (MCP)**:
- **Jira MCP:** Call Jira tools to fetch ticket acceptance criteria, story requirements, and edge cases.
- **GitLab MCP:** Call GitLab tools to inspect Merge Requests, fetch pipeline statuses (\`get_pipeline_jobs\`), and pull raw terminal logs (\`get_job_log\` / \`get_job_trace\`) when a CI build fails.
- **SonarQube MCP:** Call Sonar tools to look up open bugs, cognitive complexity violations, security hotspots, and quality gate metrics.

### Decision Map & Core Rules:
1. **Local Pre-Commit Audits:** Cross-reference code against Jira acceptance criteria. Detect null pointers, race conditions, memory leaks, and SOLID violations.
2. **CI/CD Triage:** If the user asks about a failed GitLab pipeline or broken build, autonomously query GitLab MCP for the failed jobs, retrieve the terminal stacktrace, and diagnose the root cause.
3. **Strict Behavior Parity:** Any refactored code must preserve identical inputs, outputs, exceptions, and side effects. Always provide test scenarios proving parity.
4. **Output Structure:** Provide a clear Diagnosis, Root Cause, Refactored Code (drop-in ready), and Verification Test Cases.
`;

    let currentMessages: any[] = [
      { role: 'system', content: systemPrompt },
      ...messages,
    ];

    // Multi-Turn Autonomous Tool Loop (Max 5 turns to prevent infinite loops)
    let turns = 0;
    while (turns < 5) {
      turns++;

      const completion = await openai.chat.completions.create({
        model: process.env.LLM_MODEL || 'gpt-4o',
        messages: currentMessages,
        tools: allTools.length > 0 ? allTools : undefined,
        tool_choice: 'auto',
      });

      const responseMessage = completion.choices[0].message;

      // Handle Tool Calls if the LLM requests them
      if (responseMessage.tool_calls && responseMessage.tool_calls.length > 0) {
        currentMessages.push(responseMessage);

        for (const toolCall of responseMessage.tool_calls) {
          const toolName = toolCall.function.name;
          const toolArgs = JSON.parse(toolCall.function.arguments);

          sendSSEChunk(`⚙️ *Executing tool:* \`${toolName}\`...\n\n`);

          let toolOutput: string;
          try {
            toolOutput = await executeMCPTool(toolName, toolArgs);
          } catch (err: any) {
            toolOutput = `Error calling ${toolName}: ${err.message}`;
          }

          currentMessages.push({
            role: 'tool',
            tool_call_id: toolCall.id,
            content: toolOutput,
          });
        }
        continue;
      }

      // No more tools required. Stream final response tokens to IntelliJ
      const stream = await openai.chat.completions.create({
        model: process.env.LLM_MODEL || 'gpt-4o',
        messages: currentMessages,
        stream: true,
      });

      for await (const chunk of stream) {
        const content = chunk.choices[0]?.delta?.content || '';
        if (content) sendSSEChunk(content);
      }
      break;
    }

    res.write('data: [DONE]\n\n');
    res.end();
  } catch (error: any) {
    console.error('[Agent Error]:', error);
    sendSSEChunk(`\n\n❌ **Audit Processing Error:** ${error.message}`);
    res.write('data: [DONE]\n\n');
    res.end();
  }
});

// Health check endpoint for ALB
app.get('/health', (_req: Request, res: Response) => {
  res.status(200).json({ status: 'UP', activeMCPServers: Array.from(activeServers.keys()) });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, async () => {
  console.log(`[Server] Enterprise Code Auditor running on port ${PORT}`);
  await initMCPEcosystem();
});
```

---

### 5.6 Production `Dockerfile`

```dockerfile
# Multi-stage Docker build for lightweight, secure production image
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json tsconfig.json ./
RUN npm ci
COPY src/ ./src
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production

# Install NPX and Node dependencies needed for runtime MCP servers
RUN apk add --no-cache git python3 make g++

COPY package*.json ./
RUN npm ci --only=production
COPY --from=builder /app/dist ./dist

EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:3000/health || exit 1

CMD ["node", "dist/server.js"]
```

---

## 6. AWS ECS on Fargate Deployment Architecture

```
[ GitHub Cloud Gateway ]
           │
           │ HTTPS (Route 53: agent.company.com)
           ▼
[ Application Load Balancer (ALB) ]  <-- In AWS Public Subnet (Port 443 + ACM SSL)
           │
           │ Forward to Target Group (Port 3000)
           ▼
+----------------------------------------------------------------------------+
| AWS VPC PRIVATE SUBNETS                                                    |
|                                                                            |
|  [ ECS Fargate Container Tasks ]                                           |
|  - Runs Node.js / Express Server on Port 3000                              |
|  - Speaks MCP to Jira, GitLab, and SonarQube                               |
|                                                                            |
|         │                                          │                       |
|         ├── (Outbound via NAT Gateway)             ├── (Direct Private IPs)|
|         ▼                                          ▼                       |
|  [ Public LLM API ]                         [ Internal Enterprise Tools ]  |
|  (OpenAI GPT-4o / Claude / Gemini)          - Internal GitLab Server       |
|                                             - Internal SonarQube Server    |
|                                             - Jira Cloud / Data Center     |
+----------------------------------------------------------------------------+
```

```mermaid
flowchart LR
    GitHub["GitHub Cloud<br/>(@code-auditor)"] --> Route53["Route 53 DNS<br/>(agent.company.com)"]
    Route53 --> ALB["Application Load Balancer<br/>(HTTPS 443 + SSL)"]

    subgraph PublicNet["AWS Public Subnet"]
        ALB
        NAT["NAT Gateway"]
    end

    subgraph PrivateNet["AWS Private Subnet"]
        Fargate["ECS Fargate Tasks<br/>(Port 3000)"]
        InternalTools["Internal GitLab / SonarQube / Jira"]
    end

    ALB --> Fargate
    Fargate --> InternalTools
    Fargate --> NAT
    NAT --> LLM["LLM Cloud API<br/>(OpenAI / Claude / Gemini)"]
```

### Why AWS ECS on Fargate over AWS Lambda?

| Architectural Dimension | AWS ECS on Fargate | AWS Lambda + API Gateway |
| :--- | :--- | :--- |
| **HTTP Streaming (SSE)** | **Native:** Application Load Balancer supports long-lived chunked HTTP transfer encoding without buffering. | **Problematic:** Standard API Gateway buffers responses; streaming requires complex Lambda Function URL setups. |
| **Timeout Constraints** | **No Timeout:** Audits combining Jira lookups, Semgrep scans, and LLM reasoning (30–60s) complete smoothly. | **Hard 29-Second Limit:** API Gateway forcibly terminates connections at 29 seconds. |
| **Child Process Support** | **Full Support:** Can spawn stdio MCP servers via Node.js subprocesses (`npx`, Python). | **Restricted:** Ephemeral filesystem with execution constraints. |
| **Cold Starts** | **Zero:** Containers remain continuously warm; responses begin streaming in under 300ms. | **2–5 Second Delay:** Cold starts create noticeable lag in an interactive IDE chat. |

---

## 7. Step-by-Step Setup & Verification Guide

### Step 1: Local Development & Tunnel Setup
```bash
# 1. Clone repository & install dependencies
git clone <your-repo>
cd enterprise-code-auditor-mcp
npm install

# 2. Configure environment variables
cp .env.example .env
# Fill in your OPENAI_API_KEY, GITLAB_PERSONAL_TOKEN, JIRA_API_TOKEN, SONARQUBE_TOKEN

# 3. Compile TypeScript & run
npm run dev

# 4. In a separate terminal, expose via tunnel for testing
ngrok http 3000
# Note the HTTPS URL: https://xxxx-xx.ngrok-free.app
```

### Step 2: Register GitHub App for Copilot Chat
1. Go to your organization's GitHub Settings > **Developer Settings** > **GitHub Apps** > **New GitHub App**.
2. **Name:** `enterprise-code-auditor` (determines your `@code-auditor` handle).
3. **Webhook URL:** `https://xxxx-xx.ngrok-free.app/agent` (or your AWS domain `https://agent.company.com/agent`).
4. **Webhook Secret:** Enter the secret string matching your `GITHUB_WEBHOOK_SECRET` in `.env`.
5. Under **Copilot**, check **Copilot Chat Agent**.
6. Set **Agent URL** to `https://xxxx-xx.ngrok-free.app/agent`.
7. Click **Create GitHub App** and install it into your organization.

### Step 3: Verification in IntelliJ IDEA
1. Open IntelliJ IDEA with the **GitHub Copilot** plugin updated to the latest version.
2. Open the **GitHub Copilot Chat** tool window (right sidebar).
3. Type `@` in the chat input. Verify that `@code-auditor` appears in the autocomplete dropdown.
4. Run the verification test:
   ```text
   @code-auditor Check my currently open file against PAY-204 and find any concurrency bugs.
   ```
5. Observe the live stream in IntelliJ as the agent queries Jira via MCP, performs static analysis, and returns the structured refactor.

---

## 8. System Design Interview Cheatsheet (Staff / Principal Level)

### Q1: Why Model Context Protocol (MCP) instead of writing direct REST API clients?
> **Answer:** "Direct REST integrations create an $O(N \times M)$ integration bottleneck—every AI agent framework must write custom HTTP wrappers, error handling, authentication, and serialization logic for every domain service.
>
> By introducing **MCP**, we decouple the **Orchestrator (MCP Host)** from the **Domain Capabilities (MCP Servers)**. The LLM receives standard JSON Schema definitions and autonomously decides which tools to invoke through function calling. When a new enterprise tool is introduced (e.g., Datadog, Snyk, or PostgreSQL), we simply register its respective MCP server without changing a single line of orchestration logic in the agent."

### Q2: How does the agent handle streaming while executing multi-turn tools?
> **Answer:** "We use a hybrid multi-turn orchestration loop. When the model invokes a tool call, we immediately send an intermediate Server-Sent Event (SSE) progress indicator (`⚙️ Executing tool: gitlab__get_job_log...`) back to IntelliJ so the user has visual feedback. Once all tool executions resolve, the final synthesis is streamed token-by-token directly to the IDE using standard SSE framing (`data: { choices: [{ delta: { content: ... } }] }`)."

### Q3: How do you guarantee behavior parity during automated code refactoring?
> **Answer:** "The agent's system prompt strictly enforces behavioral invariance: method signatures, return types, thrown exceptions, and side effects (database writes, metrics, cache mutations) must remain functionally identical. Furthermore, the agent generates regression unit tests covering edge cases identified during static analysis to mathematically prove behavior parity before code is merged."

### Q4: How does the agent triage GitLab CI pipeline failures without the developer opening a browser?
> **Answer:** "The agent acts as an MCP Host connected to the official GitLab MCP Server. When a developer reports a broken pipeline, the LLM calls `gitlab__get_pipeline_jobs` to identify the failing job, followed by `gitlab__get_job_log` (or `get_job_trace`) to extract the terminal logs and stacktrace.
>
> The LLM correlates this CI error with the active code open in IntelliJ, isolates the root cause (such as an unmocked integration call or compilation failure), and streams a drop-in code fix directly into the IDE chat."
