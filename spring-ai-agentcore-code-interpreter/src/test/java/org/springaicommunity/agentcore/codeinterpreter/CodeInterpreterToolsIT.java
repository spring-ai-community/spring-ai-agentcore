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

package org.springaicommunity.agentcore.codeinterpreter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.agentcore.artifacts.ArtifactStore;
import org.springaicommunity.agentcore.artifacts.CaffeineArtifactStore;
import org.springaicommunity.agentcore.artifacts.GeneratedFile;
import org.springaicommunity.agentcore.artifacts.SessionConstants;
import reactor.util.context.Context;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreAsyncClient;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for CodeInterpreterTools.
 *
 * @author Yuriy Bezsonov
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AGENTCORE_IT", matches = "true")
@SpringBootTest(classes = CodeInterpreterToolsIT.TestApp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CodeInterpreterTools Integration Tests")
class CodeInterpreterToolsIT {

	@Autowired
	private AgentCoreCodeInterpreterClient client;

	private ArtifactStore<GeneratedFile> artifactStore;

	private CodeInterpreterTools tools;

	@BeforeEach
	void setUp() {
		this.artifactStore = new CaffeineArtifactStore<>(300, "CodeInterpreterArtifactStore");
		this.tools = new CodeInterpreterTools(this.client, this.artifactStore);
	}

	@AfterEach
	void tearDown() {
		ToolCallReactiveContextHolder.clearContext();
	}

	private void setSessionId(String sessionId) {
		Context ctx = (sessionId != null) ? Context.of(SessionConstants.SESSION_ID_KEY, sessionId) : Context.empty();
		ToolCallReactiveContextHolder.setContext(ctx);
	}

	// ========== Language execution tests ==========

	@Test
	@Order(1)
	@DisplayName("Should execute Python code")
	void shouldExecutePythonCode() {
		this.setSessionId("python-test");

		String result = this.tools.executeCode("python", "print(2 + 2)");

		assertThat(result).contains("4");
	}

	@Test
	@Order(2)
	@DisplayName("Should execute JavaScript code")
	void shouldExecuteJavaScriptCode() {
		this.setSessionId("js-test");

		String result = this.tools.executeCode("javascript", "console.log(5 * 5)");

		assertThat(result).contains("25");
	}

	@Test
	@Order(3)
	@DisplayName("Should execute TypeScript code")
	void shouldExecuteTypeScriptCode() {
		this.setSessionId("ts-test");

		String result = this.tools.executeCode("typescript", "const x: number = 10; console.log(x * 3);");

		assertThat(result).contains("30");
	}

	// ========== File generation tests ==========

	@Test
	@Order(4)
	@DisplayName("Should store image file by session ID")
	void shouldStoreImageFileBySessionId() {
		this.setSessionId("image-session");

		String code = """
				import matplotlib.pyplot as plt
				plt.figure(figsize=(4, 3))
				plt.bar(['A', 'B'], [10, 20])
				plt.savefig('chart.png')
				print('done')
				""";

		String result = this.tools.executeCode("python", code);

		assertThat(result).contains("done");
		assertThat(this.artifactStore.hasArtifacts("image-session")).isTrue();

		List<GeneratedFile> files = this.artifactStore.retrieve("image-session");
		assertThat(files).isNotEmpty();

		GeneratedFile imageFile = files.stream().filter(GeneratedFile::isImage).findFirst().orElse(null);
		assertThat(imageFile).isNotNull();
		assertThat(imageFile.mimeType()).startsWith("image/");
	}

	@Test
	@Order(5)
	@DisplayName("Should store CSV file by session ID")
	void shouldStoreCsvFileBySessionId() {
		this.setSessionId("csv-session");

		String code = """
				import csv
				with open('data.csv', 'w', newline='') as f:
				    writer = csv.writer(f)
				    writer.writerow(['name', 'value'])
				    writer.writerow(['test', 123])
				print('csv created')
				""";

		String result = this.tools.executeCode("python", code);

		assertThat(result).contains("csv created");
		assertThat(this.artifactStore.hasArtifacts("csv-session")).isTrue();

		List<GeneratedFile> files = this.artifactStore.retrieve("csv-session");
		assertThat(files).isNotEmpty();
	}

	// ========== Session handling tests ==========

	@Test
	@Order(6)
	@DisplayName("Should use default session when null")
	void shouldUseDefaultSessionWhenNull() {
		this.setSessionId(null);

		String code = """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1, 2])
				plt.savefig('default.png')
				print('ok')
				""";

		this.tools.executeCode("python", code);

		assertThat(this.artifactStore.hasArtifacts(SessionConstants.DEFAULT_SESSION_ID)).isTrue();
	}

	@Test
	@Order(7)
	@DisplayName("Should retrieve files only from own session")
	void shouldRetrieveFilesOnlyFromOwnSession() {
		this.setSessionId("session-1");
		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1])
				plt.savefig('s1.png')
				print('s1')
				""");

		this.setSessionId("session-2");
		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([2])
				plt.savefig('s2.png')
				print('s2')
				""");

		List<GeneratedFile> files1 = this.artifactStore.retrieve("session-1");
		List<GeneratedFile> files2 = this.artifactStore.retrieve("session-2");

		assertThat(files1).isNotEmpty();
		assertThat(files2).isNotEmpty();
		assertThat(this.artifactStore.hasArtifacts("session-1")).isFalse();
		assertThat(this.artifactStore.hasArtifacts("session-2")).isFalse();
	}

	@Test
	@Order(8)
	@DisplayName("Should clear files after retrieve")
	void shouldClearFilesAfterRetrieve() {
		this.setSessionId("clear-session");

		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1])
				plt.savefig('clear.png')
				print('ok')
				""");

		assertThat(this.artifactStore.hasArtifacts("clear-session")).isTrue();
		this.artifactStore.retrieve("clear-session");
		assertThat(this.artifactStore.hasArtifacts("clear-session")).isFalse();
		assertThat(this.artifactStore.retrieve("clear-session")).isNull();
	}

	@Test
	@Order(9)
	@DisplayName("Should accumulate files in same session")
	void shouldAccumulateFilesInSameSession() {
		this.setSessionId("accumulate-session");

		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1])
				plt.savefig('first.png')
				print('first')
				""");

		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([2])
				plt.savefig('second.png')
				print('second')
				""");

		List<GeneratedFile> files = this.artifactStore.retrieve("accumulate-session");
		assertThat(files.size()).isGreaterThanOrEqualTo(2);
	}

	@Test
	@Order(10)
	@DisplayName("Should hasArtifacts return correctly")
	void shouldHasArtifactsReturnCorrectly() {
		assertThat(this.artifactStore.hasArtifacts("nonexistent-session")).isFalse();

		this.setSessionId("has-files-session");
		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1])
				plt.savefig('has.png')
				print('ok')
				""");

		assertThat(this.artifactStore.hasArtifacts("has-files-session")).isTrue();
	}

	// ========== GeneratedFile helper tests ==========

	@Test
	@Order(11)
	@DisplayName("Should GeneratedFile toDataUrl return valid format")
	void shouldGeneratedFileToDataUrlReturnValidFormat() {
		this.setSessionId("dataurl-session");

		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1, 2, 3])
				plt.savefig('dataurl.png')
				print('ok')
				""");

		List<GeneratedFile> files = this.artifactStore.retrieve("dataurl-session");
		GeneratedFile imageFile = files.stream().filter(GeneratedFile::isImage).findFirst().orElse(null);

		assertThat(imageFile).isNotNull();
		String dataUrl = imageFile.toDataUrl();
		assertThat(dataUrl).startsWith("data:");
		assertThat(dataUrl).contains(";base64,");
	}

	@Test
	@Order(12)
	@DisplayName("Should GeneratedFile size return correct value")
	void shouldGeneratedFileSizeReturnCorrectValue() {
		this.setSessionId("size-session");

		this.tools.executeCode("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1, 2])
				plt.savefig('size.png')
				print('ok')
				""");

		List<GeneratedFile> files = this.artifactStore.retrieve("size-session");
		GeneratedFile file = files.stream().filter(GeneratedFile::isImage).findFirst().orElse(null);

		assertThat(file).isNotNull();
		assertThat(file.size()).isGreaterThan(0);
	}

	@Test
	@Order(13)
	@DisplayName("Should CodeExecutionResult hasFiles work")
	void shouldCodeExecutionResultHasFilesWork() {
		CodeExecutionResult resultWithFiles = this.client.executeInEphemeralSession("python", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.plot([1])
				plt.savefig('hasfiles.png')
				print('ok')
				""");

		CodeExecutionResult resultWithoutFiles = this.client.executeInEphemeralSession("python", "print('no files')");

		assertThat(resultWithFiles.hasFiles()).isTrue();
		assertThat(resultWithoutFiles.hasFiles()).isFalse();
	}

	// ========== Input validation tests ==========

	@Test
	@Order(14)
	@DisplayName("Should reject null language")
	void shouldRejectNullLanguage() {
		String result = this.tools.executeCode(null, "print('test')");

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("language");
	}

	@Test
	@Order(15)
	@DisplayName("Should reject blank language")
	void shouldRejectBlankLanguage() {
		String result = this.tools.executeCode("  ", "print('test')");

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("language");
	}

	@Test
	@Order(16)
	@DisplayName("Should reject unsupported language")
	void shouldRejectUnsupportedLanguage() {
		String result = this.tools.executeCode("ruby", "puts 'test'");

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("unsupported");
	}

	@Test
	@Order(17)
	@DisplayName("Should reject null code")
	void shouldRejectNullCode() {
		String result = this.tools.executeCode("python", null);

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("code");
	}

	@Test
	@Order(18)
	@DisplayName("Should reject blank code")
	void shouldRejectBlankCode() {
		String result = this.tools.executeCode("python", "   ");

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("code");
	}

	// ========== Error handling tests ==========

	@Test
	@Order(19)
	@DisplayName("Should handle execution error")
	void shouldHandleExecutionError() {
		this.setSessionId("error-session");

		String result = this.tools.executeCode("python", "print(undefined_variable)");

		assertThat(result).containsAnyOf("Error", "NameError", "undefined");
	}

	@Test
	@Order(20)
	@DisplayName("Should format error output correctly")
	void shouldFormatErrorOutputCorrectly() {
		this.setSessionId("format-error-session");

		String result = this.tools.executeCode("python", "raise ValueError('test error')");

		assertThat(result).contains("Error executing code:");
	}

	// ========== Parallel session isolation test ==========

	@Test
	@Order(21)
	@DisplayName("Should isolate files between parallel sessions")
	void shouldIsolateFilesBetweenParallelSessions() throws Exception {
		ArtifactStore<GeneratedFile> sharedStore = new CaffeineArtifactStore<>(300,
				"SharedCodeInterpreterArtifactStore");
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);

		AtomicReference<List<GeneratedFile>> session1Files = new AtomicReference<>();
		AtomicReference<List<GeneratedFile>> session2Files = new AtomicReference<>();

		ExecutorService executor = Executors.newFixedThreadPool(2);

		// Session 1: generates chart1.png
		executor.submit(() -> this.runSessionFixture(sharedStore, "parallel-session-1", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.bar(['A'], [10])
				plt.title('Session 1')
				plt.savefig('chart1.png')
				print('session1')
				""", session1Files, startLatch, doneLatch));

		// Session 2: generates chart2.png
		executor.submit(() -> this.runSessionFixture(sharedStore, "parallel-session-2", """
				import matplotlib.pyplot as plt
				plt.figure()
				plt.bar(['B'], [20])
				plt.title('Session 2')
				plt.savefig('chart2.png')
				print('session2')
				""", session2Files, startLatch, doneLatch));

		// Start both threads simultaneously
		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		// Verify each session got only its own file
		assertThat(session1Files.get()).isNotEmpty();
		assertThat(session1Files.get().stream().anyMatch((f) -> f.name().contains("chart1"))).isTrue();

		assertThat(session2Files.get()).isNotEmpty();
		assertThat(session2Files.get().stream().anyMatch((f) -> f.name().contains("chart2"))).isTrue();

		// Verify store is empty for both sessions
		assertThat(sharedStore.hasArtifacts("parallel-session-1")).isFalse();
		assertThat(sharedStore.hasArtifacts("parallel-session-2")).isFalse();
	}

	private void runSessionFixture(ArtifactStore<GeneratedFile> sharedStore, String sessionId, String code,
			AtomicReference<List<GeneratedFile>> capturedFiles, CountDownLatch startLatch, CountDownLatch doneLatch) {
		try {
			ToolCallReactiveContextHolder.setContext(Context.of(SessionConstants.SESSION_ID_KEY, sessionId));
			CodeInterpreterTools tools = new CodeInterpreterTools(this.client, sharedStore);

			startLatch.await();
			tools.executeCode("python", code);
			capturedFiles.set(sharedStore.retrieve(sessionId));
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
		finally {
			ToolCallReactiveContextHolder.clearContext();
			doneLatch.countDown();
		}
	}

	@SpringBootApplication(exclude = { AgentCoreCodeInterpreterAutoConfiguration.class })
	static class TestApp {

		@Bean
		BedrockAgentCoreClient bedrockAgentCoreClient() {
			return BedrockAgentCoreClient.create();
		}

		@Bean
		BedrockAgentCoreAsyncClient bedrockAgentCoreAsyncClient() {
			return BedrockAgentCoreAsyncClient.create();
		}

		@Bean
		AgentCoreCodeInterpreterConfiguration codeInterpreterConfiguration() {
			return new AgentCoreCodeInterpreterConfiguration(null, null, null, null, null, null);
		}

		@Bean
		AgentCoreCodeInterpreterClient agentCoreCodeInterpreterClient(BedrockAgentCoreClient syncClient,
				BedrockAgentCoreAsyncClient asyncClient, AgentCoreCodeInterpreterConfiguration config) {
			return new AgentCoreCodeInterpreterClient(syncClient, asyncClient, config);
		}

	}

}
