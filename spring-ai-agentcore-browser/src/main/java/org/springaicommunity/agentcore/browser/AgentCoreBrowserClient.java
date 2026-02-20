/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springaicommunity.agentcore.browser;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.StartBrowserSessionRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.StartBrowserSessionResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.StopBrowserSessionRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.ViewPort;

/**
 * Client for AgentCore Browser. Manages browser sessions and provides Playwright-based
 * page interaction.
 *
 * @author Yuriy Bezsonov
 */
public class AgentCoreBrowserClient implements BrowserClient {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreBrowserClient.class);

	private final BedrockAgentCoreClient client;

	private final AgentCoreBrowserConfiguration config;

	private final Region region;

	private final AwsCredentialsProvider credentialsProvider;

	private final AwsV4HttpSigner signer;

	private final Playwright playwright;

	public AgentCoreBrowserClient(BedrockAgentCoreClient client, AgentCoreBrowserConfiguration config,
			AwsCredentialsProvider credentialsProvider, Playwright playwright) {
		this.client = client;
		this.config = config;
		this.region = client.serviceClientConfiguration().region();
		this.credentialsProvider = credentialsProvider;
		this.signer = AwsV4HttpSigner.create();
		this.playwright = playwright;
		logger.info("AgentCoreBrowserClient initialized: timeout={}s, viewport={}x{}, maxContent={}",
				config.sessionTimeoutSeconds(), config.viewportWidth(), config.viewportHeight(),
				config.maxContentLength());
	}

	/**
	 * Browse a URL and extract page content.
	 * @param url the URL to navigate to
	 * @return extracted page content
	 */
	public String browseAndExtract(String url) {
		return executeInSession(url, page -> {
			String title = page.title();
			String textContent = page.locator("body").innerText();

			logger.info("Page loaded: {} ({} chars)", title, textContent.length());

			return PageContentFormatter.format(title, textContent, config.maxContentLength());
		});
	}

	/**
	 * Take a screenshot of a web page and return raw bytes.
	 * @param url the URL to navigate to
	 * @return PNG bytes
	 * @throws BrowserOperationException if screenshot capture fails
	 */
	public byte[] screenshotBytes(String url) {
		return executeInSession(url, page -> {
			byte[] bytes = page.screenshot();
			logger.info("Screenshot taken: {} bytes", bytes.length);
			return bytes;
		});
	}

	/**
	 * Click an element on a web page.
	 * @param url the URL to navigate to
	 * @param selector CSS selector for the element to click
	 * @return result message
	 */
	public String click(String url, String selector) {
		return executeInSession(url, page -> {
			page.click(selector);
			page.waitForLoadState();
			String newUrl = page.url();
			String title = page.title();
			logger.info("Clicked '{}', now at: {}", selector, newUrl);
			return String.format("Clicked element. Page: %s (%s)", title, newUrl);
		});
	}

	/**
	 * Fill a form field on a web page.
	 * @param url the URL to navigate to
	 * @param selector CSS selector for the input field
	 * @param value value to fill
	 * @return result message
	 */
	public String fill(String url, String selector, String value) {
		return executeInSession(url, page -> {
			page.fill(selector, value);
			logger.info("Filled '{}' with value", selector);
			return String.format("Filled element '%s' with value.", selector);
		});
	}

	/**
	 * Execute JavaScript on a web page.
	 * @param url the URL to navigate to
	 * @param script JavaScript code to execute
	 * @return script result as string
	 */
	public String evaluate(String url, String script) {
		return executeInSession(url, page -> {
			Object result = page.evaluate(script);
			String resultStr = result != null ? result.toString() : "null";
			logger.info("Script executed, result: {}", resultStr);
			return resultStr;
		});
	}

	/**
	 * Execute an operation in an ephemeral browser session.
	 * @param <T> the return type
	 * @param url the URL to navigate to
	 * @param operation function to execute on the page
	 * @return operation result
	 * @throws BrowserOperationException if the operation fails
	 */
	private <T> T executeInSession(String url, Function<Page, T> operation) {
		String sessionName = "browser-" + java.util.UUID.randomUUID().toString().substring(0, 8);
		String sessionId = null;

		try {
			logger.info("Starting browser session: {}", sessionName);
			StartBrowserSessionResponse response = client.startBrowserSession(StartBrowserSessionRequest.builder()
				.browserIdentifier(config.browserIdentifier())
				.name(sessionName)
				.sessionTimeoutSeconds(config.sessionTimeoutSeconds())
				.viewPort(ViewPort.builder().width(config.viewportWidth()).height(config.viewportHeight()).build())
				.build());

			sessionId = response.sessionId();
			String wsEndpoint = response.streams().automationStream().streamEndpoint();
			logger.info("Browser session started: {} -> {}", sessionName, sessionId);

			WsConnection wsConn = generateWsHeaders(wsEndpoint);
			return executeOnPage(wsConn, url, operation);

		}
		catch (BrowserOperationException e) {
			throw e;
		}
		catch (Exception e) {
			logger.error("Browser operation failed", e);
			throw new BrowserOperationException("Browser operation failed: " + e.getMessage(), e);
		}
		finally {
			if (sessionId != null) {
				stopSession(sessionId);
			}
		}
	}

	private <T> T executeOnPage(WsConnection wsConn, String targetUrl, Function<Page, T> operation) {
		Browser browser = null;

		try {
			BrowserType chromium = playwright.chromium();
			browser = chromium.connectOverCDP(wsConn.url(),
					new BrowserType.ConnectOverCDPOptions().setHeaders(wsConn.headers()));

			BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
			Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);

			logger.info("Navigating to: {}", targetUrl);
			page.navigate(targetUrl);
			page.waitForLoadState();

			return operation.apply(page);

		}
		catch (Exception e) {
			logger.error("Page operation failed", e);
			throw new BrowserOperationException("Page operation failed: " + e.getMessage(), e);
		}
		finally {
			if (browser != null) {
				try {
					browser.close();
				}
				catch (Exception e) {
					logger.warn("Failed to close browser: {}", e.getMessage());
				}
			}
		}
	}

	/**
	 * WebSocket connection info with URL and SigV4 headers.
	 */
	private record WsConnection(String url, Map<String, String> headers) {
	}

	private WsConnection generateWsHeaders(String wsEndpoint) {
		String httpsUrl = wsEndpoint.replace("wss://", "https://");
		URI uri = URI.create(httpsUrl);

		SdkHttpRequest request = SdkHttpRequest.builder()
			.uri(uri)
			.method(SdkHttpMethod.GET)
			.putHeader("Host", uri.getHost())
			.build();

		AwsCredentialsIdentity credentials = credentialsProvider.resolveCredentials();

		SignRequest<AwsCredentialsIdentity> signRequest = SignRequest.builder(credentials)
			.request(request)
			.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "bedrock-agentcore")
			.putProperty(AwsV4HttpSigner.REGION_NAME, region.id())
			.payload(() -> new java.io.ByteArrayInputStream(new byte[0]))
			.build();

		SignedRequest signedRequest = signer.sign(signRequest);

		Map<String, String> headers = new HashMap<>();
		SdkHttpRequest signedHttpRequest = signedRequest.request();
		for (Map.Entry<String, List<String>> entry : signedHttpRequest.headers().entrySet()) {
			if (!entry.getValue().isEmpty()) {
				headers.put(entry.getKey(), entry.getValue().get(0));
			}
		}

		logger.debug("Generated WebSocket headers: {}", headers.keySet());
		return new WsConnection(wsEndpoint, headers);
	}

	private void stopSession(String sessionId) {
		try {
			logger.debug("Stopping browser session: {}", sessionId);
			client.stopBrowserSession(StopBrowserSessionRequest.builder()
				.browserIdentifier(config.browserIdentifier())
				.sessionId(sessionId)
				.build());
			logger.info("Browser session stopped: {}", sessionId);
		}
		catch (Exception e) {
			logger.warn("Failed to stop browser session {}: {}", sessionId, e.getMessage());
		}
	}

}
