package com.example.assistant.rag.ingestion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class DemoMarketDataConnector implements DataSourceConnector {
    @Override
    public List<RagDocument> fetch() {
        return List.of(
                document(
                        "NEWS-AAPL-001",
                        "AAPL",
                        "Apple Inc.",
                        DocumentType.NEWS,
                        "Apple expands enterprise AI features",
                        "Mock Financial News",
                        """
                                Apple announced new enterprise AI features for business customers, expanding its device management and developer tooling.
                                
                                Analysts said the announcement could support services revenue and improve enterprise customer retention. Management commentary focused on privacy, on-device processing, and a larger addressable market for high-margin services.
                                
                                The news is relevant because investors are watching Apple's services margin, enterprise demand, and guidance for recurring revenue growth.
                                """,
                        Map.of("sector", "Technology", "sentiment", "positive")),
                document(
                        "FILING-AAPL-10Q-001",
                        "AAPL",
                        "Apple Inc.",
                        DocumentType.FILING,
                        "Apple quarterly filing summary",
                        "SEC",
                        """
                                Apple reported quarterly revenue growth led by services and resilient device sales. The regulatory filing noted gross margin expansion, higher research and development expenses, and continued investment in artificial intelligence capabilities.
                                
                                Management guidance highlighted foreign exchange pressure, supply chain risk, and demand trends across enterprise and consumer segments.
                                
                                The filing also discussed capital allocation, including dividend payments and share buyback authorization.
                                """,
                        Map.of("formType", "10-Q", "accessLevel", "PUBLIC")),
                document(
                        "TRANSCRIPT-NVDA-001",
                        "NVDA",
                        "NVIDIA Corporation",
                        DocumentType.EARNINGS_TRANSCRIPT,
                        "NVIDIA earnings call management commentary",
                        "Company Investor Relations",
                        """
                                NVIDIA management commentary emphasized demand for accelerated computing, data center growth, and supply visibility for the next several quarters.
                                
                                The earnings transcript discussed revenue growth, margin expansion, order book strength, and capex plans from major cloud customers.
                                
                                Management also noted risks related to export controls, customer concentration, and the timing of new product ramps.
                                """,
                        Map.of("speaker", "Management", "quarter", "Q2")),
                document(
                        "PROMOTER-RELIANCE-001",
                        "RELIANCE",
                        "Reliance Industries Ltd",
                        DocumentType.PROMOTER_ACTIVITY,
                        "Promoter shareholding activity for Reliance",
                        "Mock Exchange Disclosure",
                        """
                                On 2026-09-12, promoter group entities increased shareholding in Reliance Industries Ltd through open market purchases disclosed to the stock exchange.
                                
                                The promoter activity involved additional purchases and was interpreted by market participants as a signal of promoter confidence. The disclosure also referenced compliance with regulatory filing requirements and shareholding thresholds.
                                
                                Investors often track promoter activity because promoter buying, selling, pledge releases, and shareholding changes can affect confidence and governance perception.
                                """,
                        Map.of("promoterName", "Promoter Group", "activity", "Bought shares")),
                document(
                        "COMMENTARY-INFY-001",
                        "INFY",
                        "Infosys Ltd",
                        DocumentType.PROMOTER_COMMENTARY,
                        "Founder commentary on margin and growth",
                        "Internal Research",
                        """
                                Promoter commentary for Infosys highlighted the importance of disciplined margin management, client mining, and cautious hiring while demand normalizes.
                                
                                The commentary discussed revenue growth, operating margin, deal pipeline, and management guidance. It also referenced risks from discretionary technology spending and currency movement.
                                
                                This commentary is useful for understanding how promoter and founder perspectives align with earnings guidance and analyst expectations.
                                """,
                        Map.of("speaker", "Founder", "topic", "margin and growth")),
                document(
                        "SHORT-NOISE-001",
                        "AAPL",
                        "Apple Inc.",
                        DocumentType.NEWS,
                        "Tiny price alert",
                        "Mock Financial News",
                        "AAPL moved slightly in pre-market trading.",
                        Map.of("sentiment", "neutral")));
    }

    private RagDocument document(String id,
                                String ticker,
                                String companyName,
                                DocumentType documentType,
                                String title,
                                String source,
                                String rawText,
                                Map<String, Object> metadata) {
        return new RagDocument(
                id,
                ticker,
                companyName,
                documentType,
                title,
                source,
                "https://example.com/" + id.toLowerCase(),
                Instant.now(),
                rawText,
                metadata);
    }
}
