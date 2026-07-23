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
package org.springaicommunity.example.test;

import java.time.Duration;
import java.util.Scanner;

import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DeleteMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetMemoryRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.MemoryStatus;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import static org.awaitility.Awaitility.await;

/**
 * Helper Spring Boot app that creates (or deletes) an AgentCore Memory resource for the
 * Session API example. Run with {@code mvn spring-boot:test-run}.
 *
 * <p>
 * This class is intentionally NOT a JUnit test: it is a runnable helper that talks to
 * AWS. Surefire ignores it during {@code mvn test} because it has no {@code @Test}
 * methods, which keeps the CI examples build passing without AWS credentials. The
 * Session API stack does not need any long-term memory strategies, so this setup only
 * creates (or deletes) a bare memory resource.
 */
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
							client.deleteMemory(DeleteMemoryRequest.builder().memoryId(existingMemoryId).build());
							System.out.println("Memory deleted successfully!");
						}
					}
					else {
						System.out.println("Memory deletion cancelled.");
					}
				}
			}
			else {

				System.out.println("Creating AgentCore Memory");

				try (var client = BedrockAgentCoreControlClient.create()) {

					var createMemoryRequest = CreateMemoryRequest.builder()
						.name("session_example_memory_" + System.currentTimeMillis())
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
					System.out.println("AGENTCORE_MEMORY_ID=" + memoryId);
				}
			}
		};
	}

	public static void main(String[] args) {
		SpringApplication.run(SetupTeardown.class, args);
	}

}
