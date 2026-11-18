package com.example.ordermanagement.security;

import com.example.ordermanagement.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper, UserRepository users) throws Exception {
        SecurityErrorHandler errors = new SecurityErrorHandler(mapper);
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http
                // Bearer tokens are explicit Authorization headers; there is no cookie/session authentication.
                .csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/{id}").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/products", "/api/products/{id}").permitAll()
                        .requestMatchers("/api/products", "/api/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/orders/{id}").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers("/api/orders/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            try {
                                users.findById(Long.parseLong(token.getSubject()))
                                        .filter(u -> u.getRole().name().equals(token.getClaimAsString("role")))
                                        .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));
                                return converter.convert(token);
                            } catch (NumberFormatException exception) {
                                throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                            } catch (DataAccessException exception) {
                                throw new OAuth2AuthenticationException(new OAuth2Error("server_error"), exception);
                            }
                        }))).build();
    }
}
