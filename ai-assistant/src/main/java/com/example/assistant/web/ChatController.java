package com.example.assistant.web;

import com.example.assistant.rag.PolicyRetriever;
import com.example.assistant.rag.RetrievedChunk;
import com.example.assistant.tool.EmployeeTools;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
class ChatController {
    private final PolicyRetriever policyRetriever;
    private final EmployeeTools employeeTools;
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    ChatController(PolicyRetriever policyRetriever, EmployeeTools employeeTools) {
        this.policyRetriever = policyRetriever;
        this.employeeTools = employeeTools;
    }

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request) {
        var question = request.message();
        var lower = question.toLowerCase(Locale.ROOT);
        var futures = new ArrayList<CompletableFuture<ToolResult>>();

        if (mentionsLeaveBalance(lower)) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> new ToolResult("getLeaveBalance", employeeTools.getLeaveBalance(request.employeeId())),
                    toolExecutor));
        }
        if (mentionsProfile(lower)) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> new ToolResult("getProfile", employeeTools.getProfile(request.employeeId())),
                    toolExecutor));
        }

        var chunks = shouldRetrievePolicy(lower) ? policyRetriever.retrieve(question, 3) : List.<RetrievedChunk>of();
        var toolResults = futures.stream().map(CompletableFuture::join).toList();

        return new ChatResponse(composeAnswer(question, chunks, toolResults), citations(chunks), toolResults);
    }

    private static boolean mentionsLeaveBalance(String lower) {
        return lower.contains("leave") && (lower.contains("balance") || lower.contains("left") || lower.contains("days"));
    }

    private static boolean mentionsProfile(String lower) {
        return lower.contains("profile") || lower.contains("department") || lower.contains("title");
    }

    private static boolean shouldRetrievePolicy(String lower) {
        return lower.contains("policy") || lower.contains("leave") || lower.contains("benefit")
                || lower.contains("expense") || lower.contains("remote") || lower.contains("travel")
                || lower.contains("security") || lower.contains("parental");
    }

    private static String composeAnswer(String question, List<RetrievedChunk> chunks, List<ToolResult> toolResults) {
        var answer = new StringBuilder();
        if (!chunks.isEmpty()) {
            answer.append("Policy context: ");
            chunks.forEach(chunk -> answer.append(chunk.text()).append(" "));
        }
        toolResults.forEach(tool -> answer.append("Backend result from ")
                .append(tool.name())
                .append(": ")
                .append(tool.result())
                .append(" "));
        if (answer.isEmpty()) {
            answer.append("I do not have enough policy or employee-system context to answer: ").append(question);
        }
        return answer.toString().trim();
    }

    private static List<Citation> citations(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new Citation(chunk.documentId(), chunk.title(), chunk.score()))
                .toList();
    }

    record ChatRequest(String employeeId, String message) {
    }

    record ChatResponse(String answer, List<Citation> citations, List<ToolResult> toolResults) {
    }

    record Citation(String documentId, String title, double score) {
    }

    record ToolResult(String name, Object result) {
    }
}
