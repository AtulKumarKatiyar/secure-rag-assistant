# Agent Routing — How the Right Agent (or Agents) Is Chosen

This document explains **every piece of logic** that decides which specialist agent handles a user
message, and how the system decides that more than one agent is needed.

Source files:

| File | Role |
| --- | --- |
| `orchestration/AgentSelector.java` | Entry point. Runs the 4-tier cascade. |
| `orchestration/HeuristicAgentSelector.java` | Deterministic scoring, fast path and fallback. |
| `orchestration/LlmAgentSelector.java` | LLM supervisor router. |
| `orchestration/AgentRoutingProperties.java` | Configurable thresholds. |
| `orchestration/AgentCapability.java` | What each agent declares about itself. |
| `orchestration/RoutingDecision.java` | Return type — selections **or** a clarification request. |
| `orchestration/AgentOrchestrator.java` | Executes the decision (fan-out and composition). |
| `AppConfig.java` | The supervisor `ChatClient` and its system prompt. |

---

## 1. Mental model

There is **one model**, instantiated four times with different system prompts and different tool sets.
"Agents" here means a prompt-and-tool-scoping pattern, not separate models or processes.

| Persona | Tools | Domain |
| --- | --- | --- |
| supervisor | none | Routing only |
| `equity-agent` | `searchStockNews`, `getLiveStockNews`, `getLiveStockPrice` | Companies, prices, filings, earnings |
| `index-agent` | `getIndexData` | Index levels, constituents, sector weights |
| `commodity-agent` | `getCommodityData` | Oil, gold, silver, gas |

**Routing decides relevance, never authorisation.** A wrong routing decision produces a less relevant
answer. It cannot leak data: every tool independently rebuilds its access predicate from verified JWT
claims, and the secured API independently checks its scope. This separation is what makes it safe to
let a probabilistic component (the LLM) participate in routing at all.

---

## 2. Where routing sits

```
POST /chat  (Bearer JWT)
   |
   |-- Spring Security: signature, expiry, SCOPE_assistant:chat
   |-- ChatController: tenantContext.setFromToken(jwt)
   |
   v
AgentOrchestrator.chat(message)
   |
   |-- AgentSelector.select(message, agents)  --------------->  ROUTING (this document)
   |        |
   |        +--> RoutingDecision(selections, needsClarification)
   |
   |-- if needsClarification -> short-circuit, ask a question, no agent runs
   |
   |-- CompletableFuture.supplyAsync(agent::answer, agentExecutor)   <-- parallel fan-out
   |        .orTimeout(8000ms)
   |
   |-- compose(agentAnswers) -> single answer, or [agent-name]-sectioned multi-agent answer
   v
```

---

## 3. The entry point — `AgentSelector.select`

`AgentSelector` is a `@Service` and the single place routing is decided. It runs a **4-tier cascade
with early returns**:

```java
public RoutingDecision select(String message, List<MarketAgent> agents) {
    var fastPath = heuristicSelector.fastPath(message, agents);          // TIER 1
    if (!fastPath.isEmpty()) {
        return RoutingDecision.routed(fastPath);
    }

    var llmDecision = llmAgentSelector.select(message, agents);          // TIER 2
    if (llmDecision.needsClarification()) {
        return llmDecision;
    }
    if (!llmDecision.selections().isEmpty()) {
        return llmDecision;
    }

    var fallback = heuristicSelector.fallback(message, agents);          // TIER 3
    if (!fallback.isEmpty()) {
        return RoutingDecision.routed(fallback);
    }

    return RoutingDecision.routed(List.of(new AgentSelection(           // TIER 4
            properties.defaultAgent(),
            0.0,
            "No selector produced a route; using configured default agent."
    )));
}
```

| Tier | Component | Costs a model call? | Fires when |
| --- | --- | --- | --- |
| 1 | `fastPath` | No | The query is unambiguous |
| 2 | `LlmAgentSelector.select` | Yes | Tier 1 declined |
| 3 | `fallback` | No | LLM failed **and** some agent scored > 0 |
| 4 | configured default | No | Everything else |

### Two ordering subtleties

**Tier 1 runs first on purpose.** It is pure computation, so an obviously-scoped query never pays for
an LLM round trip. This is the main latency optimisation on the routing path. The consequence is that
the fast path *pre-empts* the smarter router — which is exactly why it is written to be conservative
(see §4.4).

**`needsClarification` is checked before the empty-selection check.** A clarification decision carries
an *empty* selection list, so if `isEmpty()` were tested first, "the model wants clarification" would
be indistinguishable from "the model could not route" and both would fall through to the heuristic —
silently answering with the default agent. That was a real defect. `RoutingDecision` exists as a
two-field record precisely to keep those cases apart:

```java
public record RoutingDecision(List<AgentSelection> selections, boolean needsClarification) {
    public static RoutingDecision routed(List<AgentSelection> selections) { ... }
    public static RoutingDecision clarify() { return new RoutingDecision(List.of(), true); }
}
```

---

## 4. Tier 1 — `HeuristicAgentSelector.fastPath`

The complete method:

```java
public List<AgentSelection> fastPath(String message, List<MarketAgent> agents) {
    var tokens = tokens(message);
    var confident = score(message, tokens, agents).stream()
            .filter(selection -> selection.score() >= properties.minScore())
            .sorted(Comparator.comparingDouble(AgentSelection::score).reversed())
            .toList();

    if (confident.isEmpty()) return List.of();                       // EXIT A

    if (containsAny(tokens, FANOUT_TERMS)) {                         // EXIT B
        return confident.size() > 1
                ? confident.stream().limit(properties.maxAgents()).toList()
                : List.of();
    }

    if (confident.size() == 1) return List.of(confident.get(0));      // EXIT C

    var gap = confident.get(0).score() - confident.get(1).score();    // EXIT D
    return gap >= properties.fanoutScoreGap() ? List.of(confident.get(0)) : List.of();
}
```

### 4.1 Tokenisation

```java
private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9+.&]+");

private static Set<String> tokens(String input) {
    var tokens = new LinkedHashSet<String>();
    if (input == null) return tokens;
    for (String token : WORD_SPLIT.split(input.toLowerCase(Locale.ROOT))) {
        if (!token.isBlank() && !ROUTING_STOP_WORDS.contains(token)) {
            tokens.add(token);
        }
    }
    return tokens;
}
```

* **`WORD_SPLIT` is a separator pattern**, not a detector. `[^a-z0-9+.&]+` means "one or more
  characters that are not a lowercase letter, digit, `+`, `.` or `&`".
* **`+ . &` are intentionally kept inside tokens** so ticker-like symbols such as `BRK.B` and `S&P`
  survive as single tokens rather than splitting into fragments.
* **`toLowerCase(Locale.ROOT)` normalises case** so message tokens match the lowercase keyword sets.
  `Locale.ROOT` avoids the Turkish-I problem, where `"I".toLowerCase()` yields a dotless `ı` under a
  `tr_TR` default locale and `"INDEX".toLowerCase()` would become `ındex`, silently breaking index
  matching on that host only.
* **Order matters.** The letter range is `a-z` only, which is sufficient *because* lowercasing already
  happened. Reversing the order would treat every uppercase letter as a separator and destroy the
  tokens routing depends on:

  | Input | Correct (`lowercase → split`) | Reversed (`split` only) |
  | --- | --- | --- |
  | `"What is the current price of AAPL?"` | `[current, price, aapl]` | `[hat, is, the, current, price, of]` |
  | `"NIFTY 50 is up"` | `[nifty, 50, up]` | `[50, is, up]` |
  | `"BRK.B earnings"` | `[brk.b, earnings]` | `[., earnings]` |

* **`ROUTING_STOP_WORDS`** (24 entries: `a`, `an`, `and`, `are`, `as`, `at`, `be`, `by`, `for`, `from`,
  `how`, `i`, `in`, `is`, `it`, `me`, `of`, `on`, `please`, `show`, `tell`, `the`, `to`, `what`, `with`)
  removes function words so they cannot match a keyword set.
* **`LinkedHashSet`** gives deduplication *and* deterministic insertion order.

### 4.2 Ticker detection — the one place casing matters

```java
private static final Pattern TICKER_LIKE_SYMBOL = Pattern.compile("\\b[A-Z]{1,6}([.:][A-Z]{1,4})?\\b");

private static boolean hasTickerLikeSymbol(String message) {
    return message != null && TICKER_LIKE_SYMBOL.matcher(message).find();
}
```

This runs against the **raw `message`**, never the token set, because lowercasing would erase the exact
signal it needs. Hence the two parallel normalisations:

```
raw message ──┬─> toLowerCase(ROOT) ─> WORD_SPLIT.split ─> drop stop words ─> Set<String> tokens
              │                                                                     │
              │                                                       keyword matching (lowercase)
              └─> TICKER_LIKE_SYMBOL.matcher(raw).find() ──> hasTickerLikeSymbol
                                                             (needs ORIGINAL casing)
```

`WORD_SPLIT` answers *"which words are present?"* (case-insensitive).
`TICKER_LIKE_SYMBOL` answers *"is there an ALL-CAPS symbol?"* (case-sensitive).

The pattern matches a standalone uppercase run of 1–6 letters, optionally with a `:`/`.` suffix
(`BRK.B`, `RDS:A`). It is intentionally loose: it will also match `IPO`, `GDP`, `CEO`. In practice this
only adds +2 to equity, which cannot route on its own (see the threshold note in §4.3), so the
false-positive cost is low.

### 4.3 Scoring

```java
private static AgentSelection selectionFor(MarketAgent agent, String message, Set<String> tokens) {
    double score = 0.0;
    var signals = new LinkedHashSet<String>();

    if (Objects.equals(agent.name(), "equity-agent")) {
        if (containsAny(tokens, EQUITY_TERMS)) { score += 3.0; signals.add("equity-term"); }
        if (hasTickerLikeSymbol(message))      { score += 2.0; signals.add("ticker-like-symbol"); }
    }
    if (Objects.equals(agent.name(), "index-agent")     && containsAny(tokens, INDEX_TERMS))     { score += 4.0; signals.add("index-term"); }
    if (Objects.equals(agent.name(), "commodity-agent") && containsAny(tokens, COMMODITY_TERMS)) { score += 4.0; signals.add("commodity-term"); }

    if (score == 0.0) return null;                     // signal-free agents are dropped entirely
    return new AgentSelection(agent.name(), score, "heuristic: " + String.join(", ", signals));
}
```

| Agent | Signal | Points | Maximum |
| --- | --- | ---: | ---: |
| equity | equity term: `stock`, `stocks`, `share`, `shares`, `equity`, `ticker`, `price`, `quote`, `earnings` | +3 | **5** |
| equity | ticker-like ALL-CAPS symbol | +2 | |
| index | index term: `index`, `indices`, `nifty`, `nifty50`, `sensex`, `nasdaq`, `sp500`, `constituents` | +4 | **4** |
| commodity | commodity term: `commodity`, `commodities`, `gold`, `oil`, `crude`, `brent`, `wti`, `silver`, `copper` | +4 | **4** |

Fan-out terms: `compare`, `comparison`, `versus`, `vs`, `between`.

Notes that matter:

* **Maximum scores differ deliberately.** Equity can reach 5 (keyword + ticker); index and commodity
  top out at 4. So a query with both an equity keyword and a ticker outranks a lone commodity term.
* **`min-score` is 3.** A ticker-like symbol alone (+2) is therefore *below* the bar — `"How is HSBC
  doing?"` cannot fast-path, and correctly goes to the LLM, which knows HSBC is a bank.
* **The `signals` set becomes the `reason`** surfaced in the API response, giving a human-readable
  audit trail: `"heuristic: equity-term, ticker-like-symbol"`.
* **`containsAny` is a set intersection**, so keyword matching is O(1) per term rather than rescanning
  the message per keyword.

### 4.4 The four exits

**EXIT A — nothing cleared the threshold.**
Return empty → defer to the LLM. Keyword lists cannot know that HSBC is a bank or that "Tata Motors" is
a company.

**EXIT B — a comparison term is present.** Two sub-cases, and the second is the important one:

```java
return confident.size() > 1
        ? confident.stream().limit(properties.maxAgents()).toList()   // fan out
        : List.of();                                                   // DEFER
```

If the user asked to *compare* and the heuristic found **only one** domain, it does **not** confidently
route to that one. It defers. The reasoning: a comparison implies at least two domains, so if only one
matched, the heuristic probably **missed the other** — and answering with a single agent would silently
drop half the question.

`"Apple vs gold"` is the canonical case. `vs` is a fan-out term, but only commodity matches, because
`Apple` is not ALL-CAPS (no ticker signal) and `apple` is not an equity keyword. Rather than answering
with commodities only, the router defers to the LLM, which understands that Apple is a company. This is
the single most important design decision in the class.

**EXIT C — exactly one clear domain, no comparison term.**
Route to it. Note `and` is a stop word, so `"Give me the latest gold and crude oil data"` contains no
comparison signal and fast-paths cleanly to commodity.

**EXIT D — several domains matched, no comparison term.** This is what `fanout-score-gap` exists for:

```java
var gap = confident.get(0).score() - confident.get(1).score();
return gap >= properties.fanoutScoreGap() ? List.of(confident.get(0)) : List.of();
```

A clear leader wins; a close race defers. `"NIFTY and gold"` scores index 4.0 and commodity 4.0 — gap
0 — so it defers rather than arbitrarily picking index.

---

## 5. Tier 2 — `LlmAgentSelector` (the supervisor)

### 5.1 Capability catalogue

The supervisor is not given keyword lists. It is given what each agent declares about itself:

```java
var catalogue = availableAgents.stream()
        .map(agent -> Map.of(
                "name", agent.capability().name(),
                "description", agent.capability().description(),
                "exampleQueries", agent.capability().exampleQueries()))
        .toList();
```

The actual catalogue content:

| Agent | Description (abridged) | Example queries |
| --- | --- | --- |
| `equity-agent` | listed-company and equity-market questions; outlook, price, news, filings, earnings, transcripts, analyst views, promoter activity, dividends, buybacks, margins, valuation. "Can reason over company names, tickers and common company aliases." | "How is HSBC doing?", "Is Tata Motors a buy?", "What is HSBC current price?", "Summarize recent news for Apple" |
| `index-agent` | index levels, performance, constituents, rebalancing, sector weights, benchmark comparisons; NIFTY, SENSEX, Nasdaq, S&P 500, Dow Jones | "What are the top constituents of NIFTY50?", "Compare S&P 500 sector weights with Nasdaq" |
| `commodity-agent` | oil, crude, Brent, WTI, gold, silver, natural gas, copper, energy, metals, OPEC, inventories, macro drivers | "What is the latest Brent crude view?", "How is gold reacting to rate expectations?", "Apple vs gold" |

This is why `"Is Tata Motors a buy?"` routes correctly despite scoring zero on keywords — that exact
question is in the equity agent's example list, so the supervisor has an explicit precedent.

### 5.2 The supervisor prompt

Defined in `AppConfig.supervisorClient`. Key instructions: choose the **smallest set** of agents that
can fully answer; select multiple for cross-domain, comparison, "vs", impact, correlation or portfolio
questions; use only the supplied catalogue; and if the query is truly unclear return an empty list with
`needsClarification=true`. It must return JSON only:

```json
{
  "agents": ["equity-agent"],
  "needsClarification": false,
  "confidence": 0.92,
  "reason": "short routing reason"
}
```

### 5.3 Parsing and defensive defaults

```java
private RouterResponse parse(String raw) throws Exception {
    if (raw == null || raw.isBlank()) {
        return new RouterResponse(List.of(), true, 0.0, "empty-router-response");
    }
    var json = raw.replaceAll("(?s)```json|```", "").trim();     // tolerate markdown fences
    var node = mapper.readTree(json);
    var agents = mapper.convertValue(node.path("agents"), new TypeReference<List<String>>() {});
    return new RouterResponse(
            agents == null ? List.of() : agents,
            node.path("needsClarification").asBoolean(false),
            node.path("confidence").asDouble(0.8),
            node.path("reason").asText("selected-by-llm-router"));
}
```

Defensive choices worth noting:

* Markdown code fences are stripped, because models add them despite instructions.
* Missing fields fall back to safe defaults (`confidence` 0.8, a generic reason, no clarification).
* An empty response is treated as **clarification**, not as "route nothing" — the conservative default.

### 5.4 Guard rails

```java
return RoutingDecision.routed(response.agents().stream()
        .filter(availableAgentNames::contains)   // the model cannot invent an agent
        .distinct()                              // no duplicate fan-out
        .map(agentName -> new AgentSelection(agentName, response.confidence(), "llm-router: " + response.reason()))
        .toList());
```

Agent names returned by the model are filtered against the real agent set, so a hallucinated name is
dropped rather than causing a lookup failure. `distinct()` prevents duplicate fan-out.

### 5.5 Clarification

```java
if (response.needsClarification()) {
    log.info("Supervisor requested clarification for query '{}': {}", query, response.reason());
    return RoutingDecision.clarify();
}
```

`AgentOrchestrator` then short-circuits and returns a fixed clarifying question with
`needsClarification: true`, `agentCount: 0` and no agent answers — instead of guessing.

### 5.6 Failure handling

```java
} catch (Exception e) {
    log.warn("LLM supervisor routing failed; falling back to heuristics: {}", e.toString());
    return RoutingDecision.routed(List.of());
}
```

An empty decision lets `AgentSelector` fall through to Tier 3. The failure is **logged**, because silent
degradation would hide a broken model endpoint.

---

## 6. Tier 3 — `HeuristicAgentSelector.fallback`

Same scoring, far more permissive: any score **> 0** (rather than ≥ `min-score`), capped at
`max-agents`, sorted descending.

```java
var selections = score(message, tokens, agents).stream()
        .filter(selection -> selection.score() > 0.0)
        .sorted(Comparator.comparingDouble(AgentSelection::score).reversed())
        .limit(properties.maxAgents())
        .toList();
if (!selections.isEmpty()) return selections;
```

If nothing scores at all, it returns the configured default agent (if that agent exists):

```java
return findAgent(properties.defaultAgent(), agents) == null
        ? List.of()
        : List.of(new AgentSelection(properties.defaultAgent(), 0.0, "heuristic-fallback-default"));
```

The deliberate difference from `fastPath`: `fastPath` must be **confident** because it pre-empts the LLM;
`fallback` runs only *after* the LLM has failed, so being permissive is better than returning nothing.

**Consequence worth knowing:** because the filter is `> 0` rather than `>= min-score`, the fallback can
fan out to agents the fast path rejected as too weak. `"NIFTY and gold"` produces
`[index-agent, commodity-agent, equity-agent]` — equity is included on its 2.0 ticker signal alone,
below the fast-path threshold of 3. That is intentional (a degenerate three-way fan-out still beats no
answer) but it does mean the fallback path is looser than the fast path by design.

---

## 7. Tier 4 — configured default

Guarantees a request always gets an answer:

```java
return RoutingDecision.routed(List.of(new AgentSelection(
        properties.defaultAgent(), 0.0,
        "No selector produced a route; using configured default agent.")));
```

---

## 8. Configuration

`AgentRoutingProperties`, bound from `assistant.agent-routing.*`:

| Property | Default | Effect |
| --- | ---: | --- |
| `min-score` | 3 | Score required for the fast path to consider an agent |
| `fanout-score-gap` | 3 | Lead the top agent must hold over the runner-up before EXIT D picks it alone |
| `max-agents` | 3 | Cap on agents per request (applied in EXIT B and in `fallback`) |
| `default-agent` | `equity-agent` | Tier 4 route |

These were previously **dead configuration** — the selector hardcoded `4.0` while the property existed
but was never read. They are now genuinely applied, and a test asserts that changing `min-score`
changes the routing outcome.

---

## 9. Complete decision flow

```
message
  |
  +-- tokens(message)
  |     toLowerCase(ROOT) -> WORD_SPLIT.split -> drop blanks+stop words -> LinkedHashSet
  |
  +-- score every agent (selectionFor; null when 0)
  |     equity  : +3 equity term, +2 ticker-like (raw message)
  |     index   : +4 index term
  |     commodity: +4 commodity term
  |
  +-- keep score >= minScore (3), sort desc  ==>  "confident"
  |
  +-- TIER 1 fastPath
  |     |
  |     +-- confident empty .............................. EXIT A -> LLM router
  |     |
  |     +-- fanout term present?
  |     |     +-- >1 confident ........................... EXIT B -> FAN OUT (limit maxAgents)
  |     |     +-- ==1 confident .......................... EXIT B -> LLM router (heuristic missed a domain)
  |     |
  |     +-- ==1 confident ................................ EXIT C -> that agent
  |     |
  |     +-- >1 confident, no fanout term
  |           +-- top - second >= fanoutScoreGap ......... EXIT D -> top agent alone
  |           +-- otherwise .............................. EXIT D -> LLM router (ambiguous)
  |
  +-- TIER 2 LlmAgentSelector
  |     |
  |     +-- needsClarification=true ...................... -> ask the user (short-circuit)
  |     +-- >=1 valid agent name ......................... -> those agents
  |     +-- exception / empty ............................ -> TIER 3
  |
  +-- TIER 3 fallback: score > 0, limit maxAgents ........ -> those agents
  |     +-- nothing ...................................... -> default agent
  |
  +-- TIER 4 default agent
```

---

## 10. Worked examples

Every example below was **verified by executing the real `HeuristicAgentSelector`** with
`min-score=3`, `fanout-score-gap=3`, `max-agents=3` against stub agents — these are observed outputs,
not hand-traced predictions.

Each example shows the full trace.

### 10.1 Fast path, single agent

**`"What is the current price of AAPL?"`**

```
tokens    : {current, price, aapl}          (what, is, the, of are stop words)
scores    : equity 5.0 (price +3, AAPL +2) | index 0 | commodity 0
confident : [equity 5.0]
fanout?   : no
exit      : C  ->  [equity-agent]
reason    : "heuristic: equity-term, ticker-like-symbol"
```

### 10.2 Fast path, fan-out

**`"Compare NIFTY 50 with gold and Brent crude today"`**

```
tokens    : {compare, nifty, 50, gold, brent, crude, today}
scores    : index 4.0 (nifty) | commodity 4.0 (gold/brent/crude) | equity 2.0 (NIFTY ticker-like) -> filtered (< 3)
confident : [index 4.0, commodity 4.0]
fanout?   : YES ("compare")
exit      : B  ->  [index-agent, commodity-agent]   run in PARALLEL
```

### 10.3 Fast path, single agent without a comparison signal

**`"Give me the latest gold and crude oil data"`**

```
tokens    : {give, latest, gold, crude, oil, data}     ("and" is a stop word, not a fanout term)
scores    : commodity 4.0 | others 0 (null)
confident : [commodity 4.0]
fanout?   : no
exit      : C  ->  [commodity-agent]
```

### 10.4 The key case — comparison with one matched domain

**`"Apple vs gold"`**

```
tokens    : {apple, vs, gold}
scores    : commodity 4.0 (gold) | equity 0 ("Apple" is not ALL-CAPS, not an equity keyword) -> null
confident : [commodity 4.0]
fanout?   : YES ("vs") but only ONE domain matched
exit      : B  ->  List.of()  ->  LLM ROUTER
llm       : equity-agent + commodity-agent  (equity's description says it handles company names;
                                             commodity's examples literally include "Apple vs gold")
```

Had EXIT B returned the single commodity agent, the Apple half of the question would have been
silently dropped.

### 10.5 Ambiguity deferral via the score gap

**`"NIFTY and gold"`**

```
tokens    : {nifty, gold}                 ("and" is a stop word)
scores    : index 4.0 | commodity 4.0 | equity 2.0 (NIFTY) -> filtered
confident : [index 4.0, commodity 4.0]
fanout?   : no ("and" is not a fanout term)
exit      : D  ->  gap = 0.0 < fanoutScoreGap(3)  ->  List.of()  ->  LLM ROUTER
```

### 10.6 Below threshold

**`"How is HSBC doing?"`**

```
tokens    : {hsbc, doing}
scores    : equity 2.0 (ticker-like only) -> below min-score 3
confident : []
exit      : A  ->  LLM ROUTER  ->  equity-agent
```

### 10.7 No signal at all

**`"Is Tata Motors a buy?"`**

```
tokens    : {tata, motors, buy}           (is, a are stop words)
scores    : all 0 -> null
confident : []
exit      : A  ->  LLM ROUTER
llm       : equity-agent  (this exact question is in the agent's example queries)
```

### 10.8 A case that defers when it arguably need not

**`"What is the price of gold?"`**

```
tokens    : {price, gold}
scores    : commodity 4.0 (gold) | equity 3.0 (price)
confident : [commodity 4.0, equity 3.0]
fanout?   : no
exit      : D  ->  gap = 1.0 < fanoutScoreGap(3)  ->  LLM ROUTER
```

The user clearly wants gold, yet the router defers. This is the **cost of conservatism**: the gap rule
cannot distinguish "gold price" (one domain, two keywords) from a genuine cross-domain question. The
outcome is still correct — the LLM picks commodity — but an extra model call was spent.

---

## 11. What the caller sees

The decision is returned in `agentSelections` so a route is auditable after the fact:

```json
"agentSelections": [
  { "agentName": "commodity-agent", "score": 4.0, "reason": "heuristic: commodity-term" }
]
```

or, from the LLM router:

```json
"agentSelections": [
  { "agentName": "equity-agent",    "score": 0.92, "reason": "llm-router: query compares a company with a commodity" },
  { "agentName": "commodity-agent", "score": 0.92, "reason": "llm-router: query compares a company with a commodity" }
]
```

`score` means different things per tier — a keyword score from the heuristics, a model-reported
confidence from the LLM router. The `reason` prefix (`heuristic:` vs `llm-router:`) tells you which.

---

## 12. After routing

For each selected agent, `AgentOrchestrator` submits
`CompletableFuture.supplyAsync(agent::answer, agentExecutor).orTimeout(8000ms)` and converts a failure
into `"Agent failed: <cause>"` text for that agent only. One slow or failing specialist degrades the
answer; it does not fail the request.

The fan-out runs on a bounded pool (core 4 / max 8 / queue 50) with a `TaskDecorator` that propagates
`RequestContextHolder`, because `TenantContext` and `ToolTraceRecorder` are `@RequestScope`. Without
that decorator, tools in worker threads would read a blank tenant — and since the design is
fail-closed, that means public documents only, i.e. silently degraded answers rather than an exception.

`compose()` returns a single agent's answer verbatim, or wraps multiple:

```
Multi-agent answer:

[equity-agent]
Apple is trading at 231.45 USD…

[commodity-agent]
Gold is at 3,875.20/oz…
```

---

## 13. Design rationale

1. **Deterministic first, probabilistic second.** The cheap, explainable path handles the obvious
   majority; the model is reserved for what keywords genuinely cannot do.
2. **The fast path is conservative by construction.** Every ambiguous shape (comparison with one
   matched domain, close race between domains, below-threshold score) *defers* rather than guesses. The
   cost of deferring is latency; the cost of guessing is a confidently wrong answer.
3. **Missing a domain is worse than being slow.** This is the principle behind EXIT B's single-domain
   deferral — a comparison answered with half the picture is a correctness failure, not a latency one.
4. **Every tier degrades into another tier**, ending at a configured default, so routing cannot fail
   open into "no agent".
5. **The decision is data, not control flow.** Routing returns a `RoutingDecision` that is logged,
   returned to the caller and asserted in tests — which is what makes wrong routes fixable over time.

---

## 14. Known limitations

Be ready to discuss these; they are real, not hypothetical.

1. **`"S&P 500"` does not match the index terms.** Because `&` is preserved inside tokens it becomes
   `s&p`, while `INDEX_TERMS` contains `sp500`. An S&P 500 question scores zero on index and depends
   entirely on the LLM router. **Fix:** add `s&p` to the set, or normalise `&` during tokenisation.

2. **Scoring is coupled to agent names.** `selectionFor` compares literal strings
   (`"equity-agent"`, `"index-agent"`). Renaming an agent silently disables its scoring with no
   compile error. **Fix:** move scoring signals into `AgentCapability` so each agent declares its own.

4. **`"news"` is not an equity term.** `"Summarize recent news for AAPL"` scores only 2.0 from the
   ticker and defers to the LLM. Correct outcome, unnecessary model call.

5. **EXIT D can defer obvious queries** (see §10.8), because a single score gap cannot distinguish
   "one domain, two keywords" from a genuine cross-domain question.

6. **`TICKER_LIKE_SYMBOL` is loose** — `IPO`, `GDP`, `CEO` all add +2 to equity. Harmless at a
   threshold of 3, but it would matter if `min-score` were lowered.

7. **Keyword sets are hardcoded** in the class, so supporting a new asset class means a code change
   rather than configuration.

---

## 15. How to extend

**Add a keyword** — add it to the relevant `Set` in `HeuristicAgentSelector` (e.g. `s&p` to
`INDEX_TERMS`). No other change needed.

**Retune routing** — change `assistant.agent-routing.*`. No code change; the properties are read at
decision time.

**Add a new agent** — three steps: implement `MarketAgent` (name, `capability()`, `answer()`),
annotate `@Component` so it joins the injected `List<MarketAgent>`, and add a scoring branch in
`selectionFor` if you want it reachable from the fast path. The LLM router picks it up automatically
from the capability catalogue. Note that `AgentCapability` names must be unique and must match what
`selectionFor` checks.

**Test it** — see `HeuristicAgentSelectorTest` for fast path, fan-out, ambiguity deferral and
threshold behaviour, and `AgentOrchestratorTest` for clarification and fan-out execution.

---

## 16. Test coverage map

| Behaviour | Test |
| --- | --- |
| Unambiguous equity query fast-paths | `HeuristicAgentSelectorTest.fastPathsAnUnambiguousEquityQuery` |
| Unambiguous commodity query fast-paths | `fastPathsAnUnambiguousCommodityQuery` |
| Comparison across two domains fans out | `fastPathsAnExplicitComparisonAcrossTwoDomains` |
| Comparison with one domain defers | `leavesAComparisonWithOneMatchedDomainToTheLlmRouter` |
| No keyword match defers | `leavesUnrecognisedQueriesToTheLlmRouter` |
| `min-score` is actually applied | `honoursTheConfiguredMinimumScore` |
| `fanout-score-gap` is actually applied | `requiresAScoreGapBeforePickingOneOfSeveralMatchedDomains` |
| Fallback reaches the default agent | `fallsBackToTheDefaultAgentWhenNothingMatches` |
| Clarification is surfaced, not swallowed | `AgentOrchestratorTest.surfacesAClarificationRequestInsteadOfAnsweringWithTheWrongAgent` |
| Fan-out runs every selected agent | `AgentOrchestratorTest.runsEverySelectedAgentAndComposesTheirAnswers` |
| A failing agent does not fail the request | `reportsAnAgentFailureWithoutFailingTheWholeRequest` |
| Unknown agent names are ignored | `ignoresSelectionsThatDoNotMatchAKnownAgent` |
