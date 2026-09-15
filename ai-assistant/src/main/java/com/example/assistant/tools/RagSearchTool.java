package com.example.assistant.tools;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagSearchTool {

    private final VectorStore vectorStore;

    public RagSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(description = "Search already-ingested stock news, filings, and summaries in the RAG store. Use for historical or 'what happened' questions.")
    public List<String> searchStockNews(String query) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(5).build())
            .stream()
            .map(Document::getText)
            .toList();
    }
}