package org.springaicommunity.example.test;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Scanner;

import static org.awaitility.Awaitility.await;

@SpringBootApplication
public class SetupTeardown {

    @Bean
    CommandLineRunner commandLineRunner() {
        return args -> {

            String existingMemoryId = System.getenv("AGENTCORE_MEMORY_ID");
            if (existingMemoryId != null && !existingMemoryId.isEmpty()) {
                System.out.println("Found existing AGENTCORE_MEMORY_ID: " + existingMemoryId);
                System.out.print("Do you want to delete this memory? (yes/no): ");

                try (Scanner scanner = new Scanner(System.in)) {
                    String confirmation = scanner.nextLine().trim().toLowerCase();

                    if (confirmation.equals("yes")) {
                        try (var client = BedrockAgentCoreControlClient.create()) {
                            System.out.println("Deleting memory: " + existingMemoryId);
                            client.deleteMemory(DeleteMemoryRequest.builder()
                                    .memoryId(existingMemoryId)
                                    .build());
                            System.out.println("Memory deleted successfully!");
                        }
                    } else {
                        System.out.println("Memory deletion cancelled.");
                    }
                }
            }
            else {

                System.out.println("Creating AgentCore Memory");

                try (var client = BedrockAgentCoreControlClient.create()) {

                    var createMemoryRequest = CreateMemoryRequest.builder()
                            .name("test_memory_" + System.currentTimeMillis())
                            .eventExpiryDuration(100)
                            .build();
                    var createMemoryResponse = client.createMemory(createMemoryRequest);
                    var memoryId = createMemoryResponse.memory().id();

                    await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(15)).until(() -> {
                        System.out.println("Waiting for memory to be ACTIVE...");
                        var getMemoryRequest = GetMemoryRequest.builder().memoryId(memoryId).build();
                        var getMemoryResponse = client.getMemory(getMemoryRequest);
                        return getMemoryResponse.memory().status() == MemoryStatus.ACTIVE;
                    });

                    System.out.println("Memory created successfully!");

                    client.updateMemory(UpdateMemoryRequest.builder()
                            .memoryId(memoryId)
                            .memoryStrategies(ModifyMemoryStrategies.builder()
                                    .addMemoryStrategies(
                                            MemoryStrategyInput.builder()
                                                    .summaryMemoryStrategy(SummaryMemoryStrategyInput.builder()
                                                            .name("test_memory_stategy_" + System.currentTimeMillis())
                                                            .namespaces(
                                                                    List.of("/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}"))
                                                            .build())
                                                    .build())
                                    .build())
                            .build());

                    await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).until(() -> {
                        var memory = client.getMemory(GetMemoryRequest.builder().memoryId(memoryId).build()).memory();
                        if (memory.strategies().isEmpty()) {
                            System.out.println("Strategies status: NONE");
                            return false;
                        }

                        var allActive = memory.strategies().stream().allMatch(s -> s.status().toString().equals("ACTIVE"));

                        if (allActive) {
                            System.out.println("Strategies created");
                            System.out.println("AGENTCORE_MEMORY_ID=" + memoryId);
                            memory.strategies().forEach(s -> System.out.println("SUMMARY_STRATEGY_ID=" + s.strategyId()));
                        }

                        return allActive;
                    });
                }
            }
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(SetupTeardown.class, args);
    }

}
