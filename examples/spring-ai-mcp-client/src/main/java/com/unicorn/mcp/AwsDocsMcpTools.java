package com.unicorn.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring AI Tools that wrap MCP server calls for AWS Documentation.
 */
@Component
public class AwsDocsMcpTools {

    private final McpStdioClient mcpClient;

    public AwsDocsMcpTools(McpStdioClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    public record SearchRequest(
        @JsonProperty(required = true)
        @JsonPropertyDescription("The search query for AWS documentation")
        String query,
        
        @JsonProperty(required = false)
        @JsonPropertyDescription("Maximum number of results to return (default: 5)")
        Integer limit
    ) {}

    public record ReadDocRequest(
        @JsonProperty(required = true)
        @JsonPropertyDescription("The URL of the AWS documentation page to read")
        String url,
        
        @JsonProperty(required = false)
        @JsonPropertyDescription("Maximum length of content to return (default: 5000)")
        Integer maxLength,
        
        @JsonProperty(required = false)
        @JsonPropertyDescription("Starting index for content (default: 0)")
        Integer startIndex
    ) {}

    @Tool(description = "Search AWS documentation for information about AWS services, features, and best practices. Returns relevant documentation URLs and snippets.")
    public String searchAwsDocs(SearchRequest request) {
        Map<String, Object> args = new HashMap<>();
        args.put("search_phrase", request.query);
        if (request.limit != null) {
            args.put("limit", request.limit);
        }
        
        return mcpClient.callTool("search_documentation", args);
    }

    @Tool(description = "Read the full content of an AWS documentation page. Useful after searching to get detailed information from a specific documentation URL.")
    public String readAwsDoc(ReadDocRequest request) {
        Map<String, Object> args = new HashMap<>();
        args.put("url", request.url);
        if (request.maxLength != null) {
            args.put("max_length", request.maxLength);
        }
        if (request.startIndex != null) {
            args.put("start_index", request.startIndex);
        }
        
        return mcpClient.callTool("read_documentation", args);
    }

    @Tool(description = "Get recommendations for related AWS documentation pages based on a given documentation URL. Helps discover additional relevant content.")
    public String getAwsDocsRecommendations(String url) {
        Map<String, Object> args = new HashMap<>();
        args.put("url", url);
        
        return mcpClient.callTool("recommend", args);
    }
}
