package com.example.assistant.rag;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PolicyRetriever {
    private final List<IndexedChunk> chunks;

    public PolicyRetriever() throws IOException {
        this.chunks = loadPolicies().stream()
                .flatMap(document -> chunk(document).stream())
                .toList();
    }

    public List<RetrievedChunk> retrieve(String query, int topK) {
        var queryVector = termFrequency(query);
        return chunks.stream()
                .map(chunk -> new RetrievedChunk(chunk.documentId(), chunk.title(), chunk.text(), cosine(queryVector, chunk.vector())))
                .filter(chunk -> chunk.score() > 0.0)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private List<PolicyDocument> loadPolicies() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:/policies/*.txt");
        return Arrays.stream(resources)
                .map(this::read)
                .toList();
    }

    private PolicyDocument read(Resource resource) {
        try {
            var filename = resource.getFilename();
            var id = filename == null ? "unknown" : filename.replace(".txt", "");
            var text = resource.getContentAsString(StandardCharsets.UTF_8);
            var firstLine = text.lines().findFirst().orElse(id);
            return new PolicyDocument(id, firstLine.replace("# ", ""), text);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read policy resource " + resource, ex);
        }
    }

    private List<IndexedChunk> chunk(PolicyDocument document) {
        var paragraphs = Arrays.stream(document.text().split("\\R\\R+"))
                .map(String::trim)
                .filter(paragraph -> !paragraph.isBlank() && !paragraph.startsWith("# "))
                .toList();

        return paragraphs.stream()
                .map(text -> new IndexedChunk(document.id(), document.title(), text, termFrequency(text)))
                .toList();
    }

    private static Map<String, Double> termFrequency(String text) {
        var words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9:]+"))
                .filter(word -> word.length() > 2)
                .toList();
        return words.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.summingDouble(word -> 1.0 / words.size())));
    }

    private static double cosine(Map<String, Double> left, Map<String, Double> right) {
        var dot = left.entrySet().stream()
                .mapToDouble(entry -> entry.getValue() * right.getOrDefault(entry.getKey(), 0.0))
                .sum();
        var leftNorm = Math.sqrt(left.values().stream().mapToDouble(value -> value * value).sum());
        var rightNorm = Math.sqrt(right.values().stream().mapToDouble(value -> value * value).sum());
        return leftNorm == 0.0 || rightNorm == 0.0 ? 0.0 : dot / (leftNorm * rightNorm);
    }

    private record IndexedChunk(String documentId, String title, String text, Map<String, Double> vector) {
    }
}
