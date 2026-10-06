package com.example.assistant.rag.ingestion;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.example.assistant.rag.ingestion.AccessMetadataKeys.TENANT_ID;
import static com.example.assistant.rag.ingestion.AccessMetadataKeys.VISIBILITY;

/**
 * Loads {@code classpath:/policies/*.txt} into the common ingestion pipeline.
 *
 * <p>This replaces the previous standalone {@code PolicyRetriever}, which ran its own tokenizer
 * and cosine ranking and was never reachable from {@code /chat}. Policies now live in the same
 * vector store as market documents, so a single retrieval path and a single access predicate
 * cover both.
 *
 * <p>Policies carry no ticker. {@link DocumentFilter} exempts {@link DocumentType#POLICY} from the
 * market-data ticker/source/signal checks.
 */
@Component
public class PolicyDocumentConnector implements DataSourceConnector {

    private static final String POLICY_SOURCE = "Policy Repository";
    private static final String LOCATION_PATTERN = "classpath:/policies/*.txt";

    @Override
    public List<RagDocument> fetch() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN);
            return Arrays.stream(resources)
                    .map(this::toDocument)
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not enumerate policy resources from " + LOCATION_PATTERN, ex);
        }
    }

    private RagDocument toDocument(Resource resource) {
        try {
            var filename = resource.getFilename();
            var id = filename == null ? "policy" : filename.replace(".txt", "");
            var text = resource.getContentAsString(StandardCharsets.UTF_8);
            var title = text.lines()
                    .findFirst()
                    .map(line -> line.replaceFirst("^#\\s*", "").trim())
                    .filter(line -> !line.isBlank())
                    .orElse(id);

            return new RagDocument(
                    id,
                    "",
                    "",
                    DocumentType.POLICY,
                    title,
                    POLICY_SOURCE,
                    "policy://" + id,
                    // Policies are curated rather than time-sensitive. Ingest time keeps the
                    // metadata schema uniform (publishedAtEpochMs always present).
                    Instant.now(),
                    text,
                    Map.of(
                            VISIBILITY, AccessVisibility.PUBLIC.name(),
                            TENANT_ID, ""));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read policy resource " + resource, ex);
        }
    }
}
