package com.example.assistant.orchestration;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class HeuristicAgentSelector {
    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9+.&]+");
    private static final Pattern TICKER_LIKE_SYMBOL = Pattern.compile("\\b[A-Z]{1,6}([.:][A-Z]{1,4})?\\b");
    private static final Set<String> ROUTING_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how",
            "i", "in", "is", "it", "me", "of", "on", "please", "show", "tell", "the",
            "to", "what", "with"
    );
    private static final Set<String> EQUITY_TERMS = Set.of(
            "stock", "stocks", "share", "shares", "equity", "ticker", "price", "quote", "earnings"
    );
    private static final Set<String> INDEX_TERMS = Set.of(
            "index", "indices", "nifty", "nifty50", "sensex", "nasdaq", "sp500", "constituents"
    );
    private static final Set<String> COMMODITY_TERMS = Set.of(
            "commodity", "commodities", "gold", "oil", "crude", "brent", "wti", "silver", "copper"
    );
    private static final Set<String> FANOUT_TERMS = Set.of("compare", "comparison", "versus", "vs", "between");

    private final AgentRoutingProperties properties;

    public HeuristicAgentSelector(AgentRoutingProperties properties) {
        this.properties = properties;
    }

    /**
     * Deterministic fast path for unambiguous queries only.
     *
     * <p>It is deliberately conservative because it runs before the LLM router. A query takes the
     * fast path when exactly one domain clears {@code min-score}, when an explicit comparison term
     * is present and more than one domain matched, or when the leading domain is ahead of the
     * runner-up by at least {@code fanout-score-gap}. Anything genuinely ambiguous falls through to
     * the LLM supervisor.
     */
    public List<AgentSelection> fastPath(String message, List<MarketAgent> agents) {
        var tokens = tokens(message);
        var confident = score(message, tokens, agents).stream()
                .filter(selection -> selection.score() >= properties.minScore())
                .sorted(Comparator.comparingDouble(AgentSelection::score).reversed())
                .toList();

        if (confident.isEmpty()) {
            return List.of();
        }

        if (containsAny(tokens, FANOUT_TERMS)) {
            return confident.size() > 1
                    ? confident.stream().limit(properties.maxAgents()).toList()
                    : List.of();
        }

        if (confident.size() == 1) {
            return List.of(confident.get(0));
        }

        var gap = confident.get(0).score() - confident.get(1).score();
        return gap >= properties.fanoutScoreGap()
                ? List.of(confident.get(0))
                : List.of();
    }

    public List<AgentSelection> fallback(String message, List<MarketAgent> agents) {
        var tokens = tokens(message);
        var selections = score(message, tokens, agents).stream()
                .filter(selection -> selection.score() > 0.0)
                .sorted(Comparator.comparingDouble(AgentSelection::score).reversed())
                .limit(properties.maxAgents())
                .toList();
        if (!selections.isEmpty()) {
            return selections;
        }
        return findAgent(properties.defaultAgent(), agents) == null
                ? List.of()
                : List.of(new AgentSelection(properties.defaultAgent(), 0.0, "heuristic-fallback-default"));
    }

    private static List<AgentSelection> score(String message, Set<String> tokens, List<MarketAgent> agents) {
        return agents.stream()
                .map(agent -> selectionFor(agent, message, tokens))
                .filter(Objects::nonNull)
                .toList();
    }

    private static AgentSelection selectionFor(MarketAgent agent, String message, Set<String> tokens) {
        double score = 0.0;
        var signals = new LinkedHashSet<String>();

        if (Objects.equals(agent.name(), "equity-agent")) {
            if (containsAny(tokens, EQUITY_TERMS)) {
                score += 3.0;
                signals.add("equity-term");
            }
            if (hasTickerLikeSymbol(message)) {
                score += 2.0;
                signals.add("ticker-like-symbol");
            }
        }

        if (Objects.equals(agent.name(), "index-agent") && containsAny(tokens, INDEX_TERMS)) {
            score += 4.0;
            signals.add("index-term");
        }

        if (Objects.equals(agent.name(), "commodity-agent") && containsAny(tokens, COMMODITY_TERMS)) {
            score += 4.0;
            signals.add("commodity-term");
        }

        if (score == 0.0) {
            return null;
        }
        return new AgentSelection(agent.name(), score, "heuristic: " + String.join(", ", signals));
    }

    private static MarketAgent findAgent(String agentName, List<MarketAgent> agents) {
        return agents.stream()
                .filter(agent -> Objects.equals(agent.name(), agentName))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasTickerLikeSymbol(String message) {
        return message != null && TICKER_LIKE_SYMBOL.matcher(message).find();
    }

    private static boolean containsAny(Set<String> tokens, Set<String> candidates) {
        return tokens.stream().anyMatch(candidates::contains);
    }

    private static Set<String> tokens(String input) {
        var tokens = new LinkedHashSet<String>();
        if (input == null) {
            return tokens;
        }
        for (String token : WORD_SPLIT.split(input.toLowerCase(Locale.ROOT))) {
            if (!token.isBlank() && !ROUTING_STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
