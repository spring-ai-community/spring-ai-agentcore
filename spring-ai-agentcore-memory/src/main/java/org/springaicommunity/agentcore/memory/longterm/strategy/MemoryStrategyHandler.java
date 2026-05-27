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

package org.springaicommunity.agentcore.memory.longterm.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryRetriever;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreLongTermMemoryRetriever.MemoryRecord;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Encapsulates the fetch / format / inject behaviour of one long-term memory strategy.
 *
 * <p>
 * Implementations are stateless per-call. The advisor holds exactly one handler and
 * delegates each chat turn to it. The four built-in implementations in this package
 * (semantic / user preference / summary / episodic) cover the standard AWS AgentCore
 * memory strategy types; the same interface will be reused by custom strategies in a
 * future release.
 *
 * @author Maximilian Schellhorn
 */
public interface MemoryStrategyHandler {

	/**
	 * Returns {@code true} when this strategy needs a non-empty user prompt to run. If
	 * {@code false} (e.g. USER_PREFERENCE) the advisor will invoke this handler even on
	 * empty prompts.
	 * @return {@code true} if a non-empty user prompt is required
	 */
	default boolean requiresUserPrompt() {
		return true;
	}

	/**
	 * Returns the AgentCore Memory strategy id this handler reads from.
	 * @return the AgentCore Memory strategy id this handler reads from.
	 */
	String strategyId();

	/**
	 * Fetch the memory records that should enrich this turn.
	 * @param context per-call inputs (retriever, userId, sessionId, userPrompt)
	 * @return primary and optional secondary record sets; return
	 * {@link MemoryFetchResult#empty()} when nothing is available
	 */
	MemoryFetchResult fetch(MemoryFetchContext context);

	/**
	 * Render the fetched records into a string suitable for injection.
	 * @param context the same per-call inputs that drove the fetch, provided again so
	 * implementations can interpolate the user prompt into the output if needed
	 * @param fetched the non-empty result of {@link #fetch(MemoryFetchContext)}
	 * @return the rendered memory section
	 */
	String format(MemoryFetchContext context, MemoryFetchResult fetched);

	/**
	 * Where the formatted context is placed on the outgoing request.
	 * @return the injection target
	 */
	InjectionTarget target();

	/**
	 * Default injection helper. Attaches {@code context} to the system message (creating
	 * one if missing) or replaces the user message, per {@link #target()}.
	 * Implementations normally don't need to override this.
	 * @param request the chat client request being processed
	 * @param context the rendered memory context
	 * @return the request with the memory context injected
	 */
	default ChatClientRequest inject(ChatClientRequest request, String context) {
		return switch (this.target()) {
			case SYSTEM -> mergeIntoSystemMessage(request, context);
			case USER -> replaceUserMessage(request, context);
		};
	}

	// ------------------------------------------------------------------
	// Shared formatting helper used by the built-in handlers
	// ------------------------------------------------------------------

	/**
	 * Renders a labelled bullet list of memory records, e.g. <pre>
	 * Known facts:
	 * - User likes coffee
	 * - User is from Seattle
	 * </pre>
	 *
	 * Built-in handlers use this to produce consistent prompt output; custom handlers may
	 * reuse or replace it.
	 * @param header the section header
	 * @param records the records to render
	 * @return the rendered memory section
	 */
	static String formatMemorySection(String header, List<MemoryRecord> records) {
		StringBuilder sb = new StringBuilder();
		sb.append(header).append(":\n");
		for (MemoryRecord record : records) {
			sb.append("- ").append(record.content()).append("\n");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// Private helpers for the default inject(...)
	// ------------------------------------------------------------------

	private static ChatClientRequest mergeIntoSystemMessage(ChatClientRequest request, String context) {
		List<Message> messages = new ArrayList<>();
		boolean merged = false;
		for (Message msg : request.prompt().getInstructions()) {
			if (msg instanceof SystemMessage && !merged) {
				messages.add(new SystemMessage(msg.getText() + "\n\n" + context));
				merged = true;
			}
			else {
				messages.add(msg);
			}
		}
		if (!merged) {
			messages.add(0, new SystemMessage(context));
		}
		return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
	}

	private static ChatClientRequest replaceUserMessage(ChatClientRequest request, String newUserText) {
		List<Message> messages = new ArrayList<>();
		for (Message msg : request.prompt().getInstructions()) {
			if (msg instanceof UserMessage) {
				messages.add(new UserMessage(newUserText));
			}
			else {
				messages.add(msg);
			}
		}
		return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
	}

	/** Where the formatted memory context is attached to the outgoing prompt. */
	enum InjectionTarget {

		/** Prepend to (or merge into) the system message. */
		SYSTEM,

		/** Replace the current user message with a context-augmented version. */
		USER

	}

	// ------------------------------------------------------------------
	// Per-turn inputs (shared record; lives here so implementations in
	// other packages can pass it without extra imports).
	// ------------------------------------------------------------------

	/**
	 * Inputs the advisor computes once per turn and hands to the handler's
	 * {@link #fetch(MemoryFetchContext)} and
	 * {@link #format(MemoryFetchContext, MemoryFetchResult)}.
	 *
	 * @param retriever retriever used to query AgentCore Memory
	 * @param userId resolved user (actor) id for the current turn
	 * @param sessionId resolved session id for the current turn
	 * @param userPrompt latest user prompt (may be empty for handlers that do not require
	 * one)
	 */
	record MemoryFetchContext(AgentCoreLongTermMemoryRetriever retriever, String userId, String sessionId,
			String userPrompt) {
	}

	/**
	 * Handler output: the records to render. {@code primary} is the main record set;
	 * {@code secondary} is only used by strategies that retrieve two sets under one turn
	 * (e.g. EPISODIC's reflections). Use {@link #primaryOnly(List)} for single-set
	 * handlers.
	 *
	 * @param primary primary record set
	 * @param secondary optional secondary record set (e.g. EPISODIC reflections)
	 */
	record MemoryFetchResult(List<MemoryRecord> primary, List<MemoryRecord> secondary) {

		public static MemoryFetchResult empty() {
			return new MemoryFetchResult(List.of(), List.of());
		}

		public static MemoryFetchResult primaryOnly(List<MemoryRecord> primary) {
			return new MemoryFetchResult((primary != null) ? primary : List.of(), List.of());
		}

		public boolean isEmpty() {
			return this.primary.isEmpty() && this.secondary.isEmpty();
		}

		public int totalCount() {
			return this.primary.size() + this.secondary.size();
		}
	}

}
