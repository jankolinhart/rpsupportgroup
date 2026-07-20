package com.reelypops.rpsupportgroup.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stage-affinity check (D19). Every client request carries {@code X-App-Channel} naming the channel the build is
 * pinned to ({@code dev}/{@code test}/{@code prod}); this deployment's stage is {@code rp.stage}. If a request
 * declares a channel that does not match this stage it is rejected — a RELEASE client can never reach DEV, etc.
 *
 * <p>A missing header is allowed through (health probes and other infra callers send none); the primary stage
 * isolation is the per-stage signing key, and this header check is defence-in-depth against a mismatched build.
 */
class AppChannelAffinityFilter extends OncePerRequestFilter {

    static final String CHANNEL_HEADER = "X-App-Channel";

    private final String stage;

    AppChannelAffinityFilter(String stage) {
        this.stage = stage;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String channel = request.getHeader(CHANNEL_HEADER);
        if (channel != null && !channel.equalsIgnoreCase(stage)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"stage_mismatch\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
