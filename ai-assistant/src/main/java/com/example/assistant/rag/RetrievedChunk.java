package com.example.assistant.rag;

public record RetrievedChunk(String documentId, String title, String text, double score) {
}
