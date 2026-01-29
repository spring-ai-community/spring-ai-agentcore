package com.unicorn.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {

    private final ChatClient chatClient;
    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

    public McpController(
            ChatClient.Builder chatClientBuilder, 
            AwsDocsMcpTools awsDocsMcpTools,
            @Value("${agent.system.prompt}") String systemPrompt) {
        this.chatClient = chatClientBuilder
                .defaultTools(awsDocsMcpTools)
                .defaultSystem(systemPrompt)
                .build();
    }

    @AgentCoreInvocation
    public String handleAwsQuery(PromptRequest promptRequest, AgentCoreContext agentCoreContext) {
        String sessionId = agentCoreContext.getHeader(AgentCoreHeaders.SESSION_ID);
        logger.info("Processing AWS documentation query for session: {}", sessionId);
        logger.info("User prompt: {}", promptRequest.prompt());
        
        String response = chatClient.prompt()
            .user(promptRequest.prompt())
            .call()
            .content();
        
        logger.info("Response generated successfully");
        return response;
    }
}
