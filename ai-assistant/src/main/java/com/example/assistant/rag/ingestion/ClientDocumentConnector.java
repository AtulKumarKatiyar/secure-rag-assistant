package com.example.assistant.rag.ingestion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.example.assistant.rag.ingestion.AccessMetadataKeys.TENANT_ID;
import static com.example.assistant.rag.ingestion.AccessMetadataKeys.VISIBILITY;

@Component
public class ClientDocumentConnector implements DataSourceConnector {
    @Override
    public List<RagDocument> fetch() {
        return List.of(
                privateDocument(
                        "CLIENTA-AAPL-RESEARCH-001",
                        "clientA",
                        "AAPL",
                        "Apple Inc.",
                        "Client A internal Apple investment note",
                        """
                                Client A's internal research team expects Apple services revenue to remain resilient over the next two quarters.
                                
                                The private analyst commentary highlights margin expansion, enterprise AI adoption, and buyback support as key upside drivers. It also flags regulatory filing risk, foreign exchange pressure, and weaker consumer hardware demand as downside factors.
                                
                                This tenant-private report should only be retrieved for Client A because it contains internal research assumptions and portfolio-specific commentary.
                                """,
                        Map.of("portfolio", "clientA-growth", "analystDesk", "Client A Research")),
                privateDocument(
                        "CLIENTB-NVDA-RESEARCH-001",
                        "clientB",
                        "NVDA",
                        "NVIDIA Corporation",
                        "Client B private NVIDIA data-center memo",
                        """
                                Client B's private technology desk expects NVIDIA data-center revenue growth to remain supported by hyperscaler capex and strong order book visibility.
                                
                                The memo discusses earnings momentum, gross margin sensitivity, supply constraints, and customer concentration risk. It also includes internal management commentary assumptions used by Client B's portfolio team.
                                
                                This document is tenant-private and must never be visible to Client A or other tenants.
                                """,
                        Map.of("portfolio", "clientB-tech", "analystDesk", "Client B Technology Research")));
    }

    private RagDocument privateDocument(String id,
                                        String tenantId,
                                        String ticker,
                                        String companyName,
                                        String title,
                                        String rawText,
                                        Map<String, Object> extraMetadata) {
        var metadata = new java.util.HashMap<String, Object>(extraMetadata);
        metadata.put(VISIBILITY, AccessVisibility.TENANT_PRIVATE.name());
        metadata.put(TENANT_ID, tenantId);
        metadata.put("accessSource", "client-document-portal");

        return new RagDocument(
                id,
                ticker,
                companyName,
                DocumentType.ANALYST_COMMENTARY,
                title,
                "Client Research Portal",
                "internal://" + tenantId + "/research/" + id,
                Instant.now(),
                rawText,
                Map.copyOf(metadata));
    }
}
