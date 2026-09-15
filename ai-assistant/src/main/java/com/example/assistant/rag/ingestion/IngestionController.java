package com.example.assistant.rag.ingestion;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IngestionController {
    private final RagIngestionService ingestionService;

    IngestionController(RagIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/rag/ingest")
    RagIngestionService.IngestionReport ingestNow() {
        return ingestionService.ingest();
    }
}
