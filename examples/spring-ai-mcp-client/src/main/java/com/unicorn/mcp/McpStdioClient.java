package com.unicorn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP Client that connects to MCP Server over stdio using JSON-RPC.
 */
@Component
public class McpStdioClient {

    private static final Logger logger = LoggerFactory.getLogger(McpStdioClient.class);
    
    @Value("${mcp.server.command:uvx}")
    private String mcpCommand;
    
    @Value("${mcp.server.package}")
    private String mcpPackage;
    
    @Value("${mcp.server.env.FASTMCP_LOG_LEVEL:ERROR}")
    private String logLevel;
    
    @Value("${mcp.server.env.AWS_DOCUMENTATION_PARTITION:aws}")
    private String awsPartition;
    
    private Process mcpProcess;
    private BufferedWriter processInput;
    private BufferedReader processOutput;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(1);
    private List<Map<String, Object>> availableTools;

    @PostConstruct
    public void initialize() {
        try {
            logger.info("Initializing MCP client connection...");
            logger.info("MCP Server: {} {}", mcpCommand, mcpPackage);
            
            // Build environment variables
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("FASTMCP_LOG_LEVEL", logLevel);
            env.put("AWS_DOCUMENTATION_PARTITION", awsPartition);
            
            // Start the MCP server process
            ProcessBuilder pb = new ProcessBuilder(
                mcpCommand,
                mcpPackage
            );
            pb.environment().putAll(env);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            
            mcpProcess = pb.start();
            processInput = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream()));
            processOutput = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream()));
            
            logger.info("MCP server process started");
            
            // Initialize the connection
            sendInitialize();
            
            // List available tools
            listTools();
            
            logger.info("Successfully connected to MCP server with {} tools", 
                availableTools != null ? availableTools.size() : 0);

        } catch (Exception e) {
            logger.error("Failed to initialize MCP client", e);
            throw new RuntimeException("Failed to initialize MCP client", e);
        }
    }

    private void sendInitialize() throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.getAndIncrement());
        request.put("method", "initialize");
        
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "spring-ai-mcp-client");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);
        
        ObjectNode capabilities = objectMapper.createObjectNode();
        params.set("capabilities", capabilities);
        
        request.set("params", params);
        
        sendRequest(request);
        JsonNode response = readResponse();
        logger.info("Initialize response: {}", response);
        
        // Send initialized notification
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        sendRequest(notification);
    }

    private void listTools() throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.getAndIncrement());
        request.put("method", "tools/list");
        request.set("params", objectMapper.createObjectNode());
        
        sendRequest(request);
        JsonNode response = readResponse();
        
        if (response.has("result") && response.get("result").has("tools")) {
            availableTools = new ArrayList<>();
            response.get("result").get("tools").forEach(tool -> {
                Map<String, Object> toolMap = objectMapper.convertValue(tool, Map.class);
                availableTools.add(toolMap);
                logger.info("  - Tool: {} - {}", 
                    toolMap.get("name"), 
                    toolMap.get("description"));
            });
        }
    }

    /**
     * Call an MCP tool with the given name and arguments.
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        try {
            logger.info("Calling MCP tool: {} with args: {}", toolName, arguments);
            
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestId.getAndIncrement());
            request.put("method", "tools/call");
            
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", objectMapper.valueToTree(arguments));
            request.set("params", params);
            
            sendRequest(request);
            JsonNode response = readResponse();
            
            if (response.has("result") && response.get("result").has("content")) {
                JsonNode content = response.get("result").get("content");
                if (content.isArray() && content.size() > 0) {
                    JsonNode firstContent = content.get(0);
                    if (firstContent.has("text")) {
                        return firstContent.get("text").asText();
                    }
                }
            }
            
            if (response.has("error")) {
                return "Error: " + response.get("error").toString();
            }
            
            return "No content returned from tool";
            
        } catch (Exception e) {
            logger.error("Error calling MCP tool: {}", toolName, e);
            return "Error calling tool: " + e.getMessage();
        }
    }

    private void sendRequest(ObjectNode request) throws IOException {
        String requestStr = objectMapper.writeValueAsString(request);
        logger.debug("Sending request: {}", requestStr);
        processInput.write(requestStr);
        processInput.newLine();
        processInput.flush();
    }

    private JsonNode readResponse() throws IOException {
        String line = processOutput.readLine();
        if (line == null) {
            throw new IOException("MCP server closed connection");
        }
        logger.debug("Received response: {}", line);
        return objectMapper.readTree(line);
    }

    /**
     * Get list of available tools from the MCP server.
     */
    public List<Map<String, Object>> getAvailableTools() {
        return availableTools;
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (mcpProcess != null && mcpProcess.isAlive()) {
                logger.info("Closing MCP client connection...");
                processInput.close();
                processOutput.close();
                mcpProcess.destroy();
                mcpProcess.waitFor();
            }
        } catch (Exception e) {
            logger.error("Error closing MCP client", e);
        }
    }
}
