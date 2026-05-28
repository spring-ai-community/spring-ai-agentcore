/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.agent;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.ping.AgentCoreTaskTracker;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ChatAgentService {

    private AgentCoreTaskTracker agentCoreTaskTracker;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final Logger logger = LoggerFactory.getLogger(ChatAgentService.class);

    public ChatAgentService(AgentCoreTaskTracker agentCoreTaskTracker){
        this.agentCoreTaskTracker = agentCoreTaskTracker;
    }

    @AgentCoreInvocation
    public String asyncTaskHandling(MySimpleRequest request, AgentCoreContext agentCoreContext) {
        agentCoreTaskTracker.increment();
        logger.info(agentCoreContext.getHeader(AgentCoreHeaders.SESSION_ID));
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(10000);
                logger.info(agentCoreContext.getHeader(AgentCoreHeaders.SESSION_ID));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).whenComplete((r, ex) -> agentCoreTaskTracker.decrement());
        return "Something immediate from async task trigger";
    }

    //**********************************************************************************************/
    // Alternative method signatures (Note: Only 1 method can be annotated with @AgentCoreInvocation)
    //**********************************************************************************************/

    // Map as input and output arguments
    public Map<String, Object> handleRequest(Map<String, Object> request) {
        logger.info("Received flexible request: {}", request);

        return Map.of(
                "input", request,
                "response", "Processed: " + request.get("message"),
                "timestamp", System.currentTimeMillis(),
                "type", "flexible"
        );
    }

    // String with text/plain
    public String handleUserPrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "Please provide a valid prompt.";
        }
        return "Hello! You said: \"" + prompt.trim() + "\". How can I help you today?";
    }

    // Customer Pojo in and out
    public MyResultPojo handleUserPromptWithPojoResult(MyCustomPojo myMap) {
        logger.info("Received invocation at annotated method...");
        return new MyResultPojo("Something New Version 2", 1, "you ");
    }

    // Custom Pojo in - String out
    public String handleUserPromptWithPojo(MyCustomPojo customPojo) {
        return "Hello! You said: \"" + customPojo.prompt.trim() + "\". How can I help you today (from Pojo)?";
    }

    // Blocking to simulate work
    public MyResultPojo handleBlockingPojo(MyCustomPojo myMap) {
        logger.info("Received invocation at blocking method...");
        try {
            Thread.sleep(5000);
            logger.info("Finished invocation at blocking method...");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return new MyResultPojo("Something New Version 3", 1, "you ");
    }

    public CompletableFuture<String> serverAsyncTaskHandling(MySimpleRequest request, AgentCoreContext agentCoreContext) {
        logger.info(agentCoreContext.getHeader(AgentCoreHeaders.SESSION_ID));
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(10000);
                logger.info(agentCoreContext.getHeader(AgentCoreHeaders.SESSION_ID));
                return "async server result";
            } catch (InterruptedException e) {
                logger.error("Thread was interrupted: " + e.getMessage(), e);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread was interrupted: " + e.getMessage(), e);
            }
        });
    }

    public SseEmitter handleSseStream(MySimpleRequest request) {
        SseEmitter emitter = new SseEmitter();
        executor.submit(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    emitter.send("Message " + i + ": " + request.prompt());
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // Return byte[] directly, requires to set "accept" parameter with value "application/octet-stream" in invoke-agent-runtime
    public byte[] generateBinaryDataWithByteArray(MySimpleRequest request) {
        logger.info("Generating binary data with ResponseEntity for request: {}", request.prompt());
        var response = "Binary response for: " + request.prompt();
        return response.getBytes();
    }

    // Return byte[] wrapped into ResponseEntity
    public ResponseEntity<byte[]> generateBinaryDataWithResponseEntity(MySimpleRequest request) {
        logger.info("Generating binary data with ResponseEntity for request: {}", request.prompt());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(("Binary response for: " + request.prompt()).getBytes());
    }

    // Return InputStreamResource directly, requires to set "accept" parameter with value "application/octet-stream" in invoke-agent-runtime
    public InputStreamResource generateByteStreamForText(String request) {
        logger.info("Generating byte stream for request: {}", request);
        String response = "Binary stream response for: " + request;
        java.io.InputStream stream = new ByteArrayInputStream(response.getBytes());
        return new InputStreamResource(stream);
    }

    // InputStream wrapped into ResponseEntity
    public ResponseEntity<InputStreamResource> generateByteStreamWithResponseEntityForText(String request) {
        logger.info("Generating byte stream for request: {}", request);
        String response = "Binary stream response for: " + request;
        java.io.InputStream stream = new ByteArrayInputStream(response.getBytes());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    // Using File with explicit ResponseEntity
    public ResponseEntity<InputStreamResource> generateStreamFromFile(String request) throws URISyntaxException, FileNotFoundException {
        var resourceUrl = this.getClass().getResource("/test-reponse.txt");
        if (resourceUrl == null) {
            throw new FileNotFoundException("Resource file not found: test-reponse.txt");
        }
        File file = new File(resourceUrl.toURI());
        InputStreamResource resource = new InputStreamResource(new FileInputStream(file));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"test-reponse.txt\"")
                .contentLength(file.length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public record MyCustomPojo(String something, Integer someNumber, String prompt){}
    public record MyResultPojo(String something, Integer someNumber, String prompt){}
    public record MySimpleRequest(String prompt){}
}
