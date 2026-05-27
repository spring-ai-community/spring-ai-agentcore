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
package com.unicorn.agents;

import org.springaicommunity.agentcore.controller.AgentCoreInvocationsHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class CustomAgentCoreInvocationsController implements AgentCoreInvocationsHandler {

    private final ChatClient chatClient;

    public CustomAgentCoreInvocationsController(ChatClient.Builder chatClient) {
        this.chatClient = chatClient
                .defaultTools(new DateTimeTools())
                .build();
    }

    @PostMapping(value = "/invocations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> handleJsonInvocation(@RequestBody Object request, @RequestHeader HttpHeaders headers) {
        return chatClient.prompt().user((String) request).stream().content();
    }
}
