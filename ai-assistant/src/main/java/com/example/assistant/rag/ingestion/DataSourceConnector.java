package com.example.assistant.rag.ingestion;

import java.util.List;

public interface DataSourceConnector {
    List<RagDocument> fetch();
}
