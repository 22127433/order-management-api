package com.example.ordermanagement.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public Long id() {
        try {
            long id = Long.parseLong(authentication().getName());
            if (id > 0) { return id; }
        } catch (NumberFormatException ignored) { }
        throw new AuthenticationCredentialsNotFoundException("Authentication is required");
    }
    public boolean isAdmin() {
        return authentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    private Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        return authentication;
    }
}
