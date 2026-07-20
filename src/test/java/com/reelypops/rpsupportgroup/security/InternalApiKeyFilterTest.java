package com.reelypops.rpsupportgroup.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the internal-api-key filter: a matching key installs {@code ROLE_INTERNAL}; a missing, wrong,
 * or (against a blank configured key) any provided key leaves the request anonymous. The chain always proceeds.
 */
class InternalApiKeyFilterTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingKeyAuthenticatesAsInternal() throws Exception {
        when(request.getHeader(InternalApiKeyFilter.HEADER)).thenReturn("s3cret");

        new InternalApiKeyFilter("s3cret").doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingHeaderStaysAnonymous() throws Exception {
        when(request.getHeader(InternalApiKeyFilter.HEADER)).thenReturn(null);

        new InternalApiKeyFilter("s3cret").doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void wrongKeyStaysAnonymous() throws Exception {
        when(request.getHeader(InternalApiKeyFilter.HEADER)).thenReturn("nope");

        new InternalApiKeyFilter("s3cret").doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void blankConfiguredKeyNeverAuthenticates() throws Exception {
        when(request.getHeader(InternalApiKeyFilter.HEADER)).thenReturn("anything");

        new InternalApiKeyFilter("").doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
