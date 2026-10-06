package com.example.assistant.rag.ingestion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.example.assistant.rag.ingestion.AccessMetadataKeys.ENTITLEMENT_GROUP;
import static com.example.assistant.rag.ingestion.AccessMetadataKeys.TENANT_ID;
import static com.example.assistant.rag.ingestion.AccessMetadataKeys.VISIBILITY;

@Component
public class PremiumVendorConnector implements DataSourceConnector {
    @Override
    public List<RagDocument> fetch() {
        return List.of(
                licensedDocument(
                        "PREMIUM-GOLD-OUTLOOK-001",
                        "GOLD",
                        "Gold Spot",
                        DocumentType.REPORT,
                        "Premium vendor gold macro outlook",
                        """
                                The premium vendor report says gold remains supported by central-bank demand, real-rate expectations, and geopolitical risk.
                                
                                The analyst commentary highlights price momentum, ETF flows, dollar sensitivity, and inflation hedging demand. It also compares gold with crude oil and broader commodity allocation trends.
                                
                                This licensed commentary is entitlement restricted and should only be retrieved for clients subscribed to the premium-research entitlement group.
                                """,
                        Map.of("assetClass", "commodity", "region", "global")),
                licensedDocument(
                        "PREMIUM-NIFTY-SECTOR-001",
                        "NIFTY50",
                        "NIFTY 50",
                        DocumentType.REPORT,
                        "Premium vendor NIFTY sector rotation report",
                        """
                                The premium research vendor report says NIFTY 50 sector rotation is being led by financial services, energy, and information technology.
                                
                                The report discusses index earnings breadth, sector weights, valuation risk, foreign institutional flows, and management commentary from leading constituents.
                                
                                This report is not public data. Retrieval must require the premium-research entitlement group even though it is not tied to a single tenant.
                                """,
                        Map.of("assetClass", "index", "region", "IN")));
    }

    private RagDocument licensedDocument(String id,
                                         String ticker,
                                         String companyName,
                                         DocumentType documentType,
                                         String title,
                                         String rawText,
                                         Map<String, Object> extraMetadata) {
        var metadata = new java.util.HashMap<String, Object>(extraMetadata);
        metadata.put(VISIBILITY, AccessVisibility.ENTITLEMENT_RESTRICTED.name());
        metadata.put(TENANT_ID, "");
        metadata.put(ENTITLEMENT_GROUP, "premium-research");
        metadata.put("accessSource", "premium-vendor-feed");

        return new RagDocument(
                id,
                ticker,
                companyName,
                documentType,
                title,
                "Premium Vendor Research",
                "vendor://premium-research/" + id,
                Instant.now(),
                rawText,
                Map.copyOf(metadata));
    }
}
