package in.techseva.cb.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validKey_authenticatesAsApiClient() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("correct-key");
        var filter = new ApiKeyAuthFilter(Set.of("correct-key", "another-key"));

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_API_CLIENT");
        verify(chain).doFilter(request, response);
    }

    @Test
    void wrongKey_doesNotAuthenticate() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("guessed-key");
        var filter = new ApiKeyAuthFilter(Set.of("correct-key"));

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void differentLengthKey_doesNotAuthenticate() throws Exception {
        // Exercises the MessageDigest.isEqual path for mismatched byte-array
        // lengths, which is the case a naive length-check-first comparison
        // would short-circuit on fastest.
        when(request.getHeader("X-API-Key")).thenReturn("short");
        var filter = new ApiKeyAuthFilter(Set.of("a-much-longer-correct-key-value"));

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingHeader_doesNotAuthenticate() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn(null);
        var filter = new ApiKeyAuthFilter(Set.of("correct-key"));

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
