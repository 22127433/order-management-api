package com.example.ordermanagement.security;

import com.example.ordermanagement.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;
    public SecurityErrorHandler(ObjectMapper mapper) { this.mapper = mapper; }
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        if (exception instanceof OAuth2AuthenticationException oauth && oauth.getError().getErrorCode().equals("server_error")) {
            write(request, response, 500, "Internal Server Error", "An unexpected error occurred");
            return;
        }
        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, 401, "Unauthorized", "Authentication is required or bearer token is invalid");
    }
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        write(request, response, 403, "Forbidden", "Access denied");
    }
    private void write(HttpServletRequest request, HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), status, error, message, request.getRequestURI(), List.of()));
    }
}
