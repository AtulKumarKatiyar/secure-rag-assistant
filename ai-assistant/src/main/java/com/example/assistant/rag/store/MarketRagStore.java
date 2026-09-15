package com.example.assistant.rag.store;

import com.example.assistant.rag.ingestion.DocumentType;
import com.example.assistant.rag.ingestion.RagChunk;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MarketRagStore {
    private final Map<String, IndexedMarketChunk> chunksById = new ConcurrentHashMap<>();

    public void upsert(List<RagChunk> chunks) {
        chunks.forEach(chunk -> chunksById.put(chunk.chunkId(), IndexedMarketChunk.from(chunk)));
    }

    public List<MarketRetrievedChunk> retrieve(String query, MarketRagFilter filter, int topK) {
        var queryVector = termFrequency(query);
        return chunksById.values().stream()
                .filter(chunk -> filter.matches(chunk.ticker(), chunk.documentType(), chunk.publishedAt()))
                .map(chunk -> chunk.toRetrievedChunk(cosine(queryVector, chunk.vector())))
                .filter(chunk -> chunk.score() > 0.0)
                .sorted(Comparator.comparingDouble(MarketRetrievedChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    public int size() {
        return chunksById.size();
    }

    private static Map<String, Double> termFrequency(String text) {
        var words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9:]+"))
                .filter(word -> word.length() > 2)
                .toList();
        if (words.isEmpty()) {
            return Map.of();
        }
        return words.stream()
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.summingDouble(word -> 1.0 / words.size())));
    }

    private static double cosine(Map<String, Double> left, Map<String, Double> right) {
        var dot = left.entrySet().stream()
                .mapToDouble(entry -> entry.getValue() * right.getOrDefault(entry.getKey(), 0.0))
                .sum();
        var leftNorm = Math.sqrt(left.values().stream().mapToDouble(value -> value * value).sum());
        var rightNorm = Math.sqrt(right.values().stream().mapToDouble(value -> value * value).sum());
        return leftNorm == 0.0 || rightNorm == 0.0 ? 0.0 : dot / (leftNorm * rightNorm);
    }

    public record MarketRagFilter(String ticker, List<DocumentType> documentTypes, Instant publishedAfter) {
        public boolean matches(String chunkTicker, DocumentType documentType, Instant publishedAt) {
            if (ticker != null && !ticker.isBlank() && !ticker.equalsIgnoreCase(chunkTicker)) {
                return false;
            }
            if (documentTypes != null && !documentTypes.isEmpty() && !documentTypes.contains(documentType)) {
                return false;
            }
            return publishedAfter == null || !publishedAt.isBefore(publishedAfter);
        }

        public static MarketRagFilter any() {
            return new MarketRagFilter(null, List.of(), null);
        }
    }

    private record IndexedMarketChunk(
            String chunkId,
            String documentId,
            String ticker,
            String companyName,
            DocumentType documentType,
            String title,
            String source,
            String url,
            Instant publishedAt,
            String text,
            Map<String, Object> metadata,
            Map<String, Double> vector
    ) {
        static IndexedMarketChunk from(RagChunk chunk) {
            return new IndexedMarketChunk(
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.ticker(),
                    chunk.companyName(),
                    chunk.documentType(),
                    chunk.title(),
                    chunk.source(),
                    chunk.url(),
                    chunk.publishedAt(),
                    chunk.text(),
                    chunk.metadata(),
                    termFrequency(chunk.text()));
        }

        MarketRetrievedChunk toRetrievedChunk(double score) {
            return new MarketRetrievedChunk(
                    chunkId,
                    documentId,
                    ticker,
                    companyName,
                    documentType,
                    title,
                    source,
                    url,
                    publishedAt,
                    text,
                    metadata,
                    score);
        }
    }
}
