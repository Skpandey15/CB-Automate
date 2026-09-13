package in.techseva.cb.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private final Set<String> validApiKeys;

    public ApiKeyAuthFilter(Set<String> validApiKeys) {
        this.validApiKeys = validApiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && isValidApiKey(apiKey)) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "api-client", null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    /**
     * Constant-time membership check. A plain Set.contains() short-circuits
     * as soon as it finds (or fails to find) a match, which leaks timing
     * information about how close a guessed key is to a real one. Every
     * candidate is compared via MessageDigest.isEqual (constant-time, and
     * safe against length differences) and none of them short-circuit the
     * loop, so the check takes the same time regardless of which key — if
     * any — matches.
     */
    private boolean isValidApiKey(String apiKey) {
        byte[] provided = apiKey.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String candidate : validApiKeys) {
            if (MessageDigest.isEqual(provided, candidate.getBytes(StandardCharsets.UTF_8))) {
                matched = true;
            }
        }
        return matched;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }
}
