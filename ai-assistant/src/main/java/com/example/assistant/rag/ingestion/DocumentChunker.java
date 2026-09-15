package com.example.assistant.rag.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

@Component
public class DocumentChunker {
    private static final int MIN_CHUNK_LENGTH = 80;

    public List<RagChunk> chunk(RagDocument document) {
        var paragraphs = Arrays.stream(document.rawText().split("\\R\\R+"))
                .map(String::trim)
                .filter(paragraph -> paragraph.length() > MIN_CHUNK_LENGTH)
                .toList();

        var chunks = new ArrayList<RagChunk>();
        for (int index = 0; index < paragraphs.size(); index++) {
            var metadata = new HashMap<>(document.metadata());
            metadata.put("chunkIndex", index);
            metadata.put("ticker", document.ticker());
            metadata.put("companyName", document.companyName());
            metadata.put("documentType", document.documentType().name());
            metadata.put("source", document.source());
            metadata.put("publishedAt", document.publishedAt().toString());
            metadata.put("title", document.title());
            metadata.put("url", document.url());

            chunks.add(new RagChunk(
                    document.id() + "-chunk-" + index,
                    document.id(),
                    document.ticker(),
                    document.companyName(),
                    document.documentType(),
                    document.title(),
                    document.source(),
                    document.url(),
                    document.publishedAt(),
                    paragraphs.get(index),
                    metadata));
        }
        return chunks;
    }
}
