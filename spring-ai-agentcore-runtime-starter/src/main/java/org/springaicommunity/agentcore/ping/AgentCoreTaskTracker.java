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

package org.springaicommunity.agentcore.ping;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * AgentCore Task Tracker to report HEALTHY_BUSY status to AgentCore Runtime during health
 * check See:
 * https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/runtime-long-run.html
 */
@Component
public class AgentCoreTaskTracker {

	private final AtomicLong activeTasks = new AtomicLong(0);

	public void increment() {
		activeTasks.incrementAndGet();
	}

	public void decrement() {
		if (activeTasks.get() > 0) {
			activeTasks.decrementAndGet();
		}
	}

	public long getCount() {
		return activeTasks.get();
	}

}
