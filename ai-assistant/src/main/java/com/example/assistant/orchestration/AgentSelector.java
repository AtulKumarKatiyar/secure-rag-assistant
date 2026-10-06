package com.example.assistant.orchestration;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AgentSelector {
    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9+.&]+");
    private static final Pattern TICKER_LIKE_SYMBOL = Pattern.compile("\\b[A-Z]{1,6}([.:][A-Z]{1,4})?\\b");
    private static final Set<String> ROUTING_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "for", "from", "give", "how",
            "i", "in", "is", "it", "latest", "me", "of", "on", "please", "show", "tell", "the",
            "to", "today", "what", "with"
    );
    private static final Set<String> FANOUT_TERMS = Set.of(
            "compare", "comparison", "versus", "vs", "correlate", "correlation", "impact", "affect",
            "relationship", "between", "hedge", "portfolio"
    );
    private static final Set<String> MARKET_INTENT_TERMS = Set.of(
            "price", "quote", "news", "filing", "filings", "earnings", "transcript", "result", "results",
            "dividend", "buyback", "promoter", "analyst", "commentary", "target", "revenue", "margin"
    );

    private final AgentRoutingProperties properties;

    public AgentSelector(AgentRoutingProperties properties) {
        this.properties = properties;
    }

    public List<AgentSelection> select(String message, List<MarketAgent> agents) {
        var normalizedMessage = normalize(message);
        var messageTokens = tokens(normalizedMessage);

        var ranked = agents.stream()
                .map(agent -> score(agent.capability(), message, normalizedMessage, messageTokens))
                .filter(selection -> selection.score() >= properties.minScore())
                .sorted(Comparator.comparingInt(AgentSelection::score).reversed()
                        .thenComparing(AgentSelection::agentName))
                .toList();

        if (ranked.isEmpty()) {
            return List.of(new AgentSelection(
                    properties.defaultAgent(),
                    0,
                    "No capability crossed the routing threshold; using configured fallback agent."
            ));
        }

        int bestScore = ranked.get(0).score();
        boolean fanoutQuery = containsAny(messageTokens, FANOUT_TERMS);
        int allowedGap = fanoutQuery ? properties.fanoutScoreGap() : 0;

        return ranked.stream()
                .filter(selection -> selection.score() >= bestScore - allowedGap)
                .limit(properties.maxAgents())
                .toList();
    }

    private static AgentSelection score(AgentCapability capability,
                                        String rawMessage,
                                        String normalizedMessage,
                                        Set<String> messageTokens) {
        int score = 0;
        var matches = new ArrayList<String>();

        for (String keyword : capability.keywords()) {
            var normalizedKeyword = normalize(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            if (normalizedKeyword.contains(" ")) {
                if (normalizedMessage.contains(normalizedKeyword)) {
                    score += 4;
                    matches.add(keyword);
                }
            } else if (messageTokens.contains(normalizedKeyword)) {
                score += 3;
                matches.add(keyword);
            }
        }

        for (String domain : capability.domains()) {
            var normalizedDomain = normalize(domain);
            if (messageTokens.contains(normalizedDomain) || normalizedMessage.contains(normalizedDomain)) {
                score += 2;
                matches.add(domain);
            }
        }

        int exampleOverlap = capability.examples().stream()
                .map(AgentSelector::normalize)
                .map(AgentSelector::tokens)
                .mapToInt(exampleTokens -> overlapCount(messageTokens, exampleTokens))
                .max()
                .orElse(0);
        if (exampleOverlap > 0) {
            score += Math.min(2, exampleOverlap);
            matches.add("example-overlap");
        }

        if (isEquityCapability(capability) && hasTickerLikeEquityIntent(rawMessage, messageTokens)) {
            score += 4;
            matches.add("ticker-like-symbol-with-market-intent");
        }

        var reason = matches.isEmpty()
                ? "No capability terms matched."
                : "Matched capability signals: " + String.join(", ", distinct(matches));
        return new AgentSelection(capability.agentName(), score, reason);
    }

    private static boolean isEquityCapability(AgentCapability capability) {
        return capability.domains().stream()
                .map(AgentSelector::normalize)
                .anyMatch(domain -> domain.equals("equity") || domain.equals("stock"));
    }

    private static boolean hasTickerLikeEquityIntent(String rawMessage, Set<String> messageTokens) {
        return rawMessage != null
                && TICKER_LIKE_SYMBOL.matcher(rawMessage).find()
                && containsAny(messageTokens, MARKET_INTENT_TERMS);
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static Set<String> tokens(String input) {
        var tokens = new LinkedHashSet<String>();
        for (String token : WORD_SPLIT.split(input)) {
            if (!token.isBlank() && !ROUTING_STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean containsAny(Set<String> tokens, Set<String> candidates) {
        return tokens.stream().anyMatch(candidates::contains);
    }

    private static int overlapCount(Set<String> left, Set<String> right) {
        int count = 0;
        for (String token : left) {
            if (right.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}
