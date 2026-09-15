package com.example.assistant.rag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RagIngestionScheduler {
    private static final Logger log = LoggerFactory.getLogger(RagIngestionScheduler.class);

    private final RagIngestionService ingestionService;

    public RagIngestionScheduler(RagIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedMarketRagOnStartup() {
        run();
    }

    @Scheduled(
            initialDelayString = "${rag.ingestion.initial-delay-ms:60000}",
            fixedDelayString = "${rag.ingestion.delay-ms:600000}")
    public void run() {
        var report = ingestionService.ingest();
        log.info("Market RAG ingestion completed: {}", report);
    }
}
