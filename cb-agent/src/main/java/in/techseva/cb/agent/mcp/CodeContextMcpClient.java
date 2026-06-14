package in.techseva.cb.agent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;

@Component
public class CodeContextMcpClient {

    private static final Logger log = LoggerFactory.getLogger(CodeContextMcpClient.class);

    private final String mcpServerUrl;
    private McpSyncClient mcpClient;

    public CodeContextMcpClient(@Value("${agent.mcp.server-url:http://localhost:3000}") String mcpServerUrl) {
        this.mcpServerUrl = mcpServerUrl;
    }

    @PostConstruct
    public void init() {
        try {
            var transport = HttpClientSseClientTransport.builder(mcpServerUrl).build();
            this.mcpClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            mcpClient.initialize();
            log.info("MCP client initialized: {}", mcpServerUrl);
        } catch (Exception e) {
            log.warn("MCP server unavailable at {}: {}", mcpServerUrl, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (mcpClient != null) {
            try { mcpClient.close(); } catch (Exception ignored) {}
        }
    }

    public String readFileContext(String filePath, int lineNo, int contextLines) {
        if (mcpClient == null) {
            log.debug("MCP unavailable, skipping file context for {}", filePath);
            return "";
        }
        try {
            var request = new CallToolRequest("read_file_context",
                    Map.of("path", filePath, "line", lineNo, "context_lines", contextLines));
            CallToolResult result = mcpClient.callTool(request);
            if (result != null && result.content() != null && !result.content().isEmpty()) {
                var first = result.content().get(0);
                if (first instanceof TextContent tc) return tc.text();
                return first.toString();
            }
        } catch (Exception e) {
            log.warn("MCP call failed for {}: {}", filePath, e.getMessage());
        }
        return "";
    }

    public String listProjectFiles(String directory) {
        if (mcpClient == null) return "";
        try {
            var request = new CallToolRequest("list_files", Map.of("directory", directory));
            CallToolResult result = mcpClient.callTool(request);
            if (result != null && result.content() != null && !result.content().isEmpty()) {
                var first = result.content().get(0);
                if (first instanceof TextContent tc) return tc.text();
                return first.toString();
            }
        } catch (Exception e) {
            log.warn("MCP list_files failed: {}", e.getMessage());
        }
        return "";
    }
}
