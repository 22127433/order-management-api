package com.example.ordermanagement.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.security.TokenService;
import com.example.ordermanagement.support.TestSecurity;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwords;
    @Mock private AuthenticationManager authentication;
    @Mock private TokenService tokens;
    private AuthService service;

    @BeforeEach void setUp() { service = new AuthService(users, passwords, authentication, tokens, VALIDATORS.getValidator()); }
    @AfterAll static void closeValidator() { VALIDATORS.close(); }

    @Test
    void registrationNormalizesEmailAssignsCustomerAndDelegatesHashing() {
        String password = TestSecurity.PASSWORD;
        when(passwords.encode(password)).thenReturn(TestSecurity.PASSWORD_HASH);
        when(users.saveAndFlush(any())).thenAnswer(call -> {
            AppUser user = call.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return user;
        });
        UserResponse response = service.register(new RegisterRequest("  CUSTOMER@Example.com  ", password));
        var saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo(TestSecurity.PASSWORD_HASH).isNotEqualTo(password);
        assertThat(response.role()).isEqualTo(Role.CUSTOMER);
        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.id()).isEqualTo(1L);
        verifyNoInteractions(authentication, tokens);
    }

    @Test
    void duplicateEmailDoesNotHashOrCreateAccount() {
        when(users.existsByEmail("customer@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("customer@example.com", TestSecurity.PASSWORD)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(passwords, tokens, authentication);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "           ", "12345678901"})
    void rejectsInvalidRegistrationPasswordBeforePersistence(String password) {
        assertThatThrownBy(() -> service.register(new RegisterRequest("customer@example.com", password)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(users, passwords);
    }

    @Test
    void rejectsUtf8PasswordOver72BytesDespiteValidCharacterLength() {
        assertThatThrownBy(() -> service.register(new RegisterRequest("customer@example.com", "é".repeat(40))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("72 UTF-8 bytes");
        verifyNoInteractions(users, passwords);
    }

    @Test
    void preservesPasswordWhitespaceInsteadOfSilentlyNormalizingIt() {
        String password = "  " + TestSecurity.PASSWORD + "  ";
        when(passwords.encode(password)).thenReturn(TestSecurity.PASSWORD_HASH);
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.register(new RegisterRequest("customer@example.com", password));
        verify(passwords).encode(password);
    }

    @Test
    void successfulLoginUsesAuthenticationManagerAndIssuesTokenForAuthenticatedId() {
        var user = TestSecurity.user(1L);
        when(authentication.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated("1", null, List.of()));
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var expected = new TokenResponse("test-token", "Bearer", 900, UserResponse.from(user));
        when(tokens.issue(user)).thenReturn(expected);
        assertThat(service.login(new LoginRequest(" CUSTOMER@Example.com ", TestSecurity.PASSWORD))).isEqualTo(expected);
        var credentials = ArgumentCaptor.forClass(org.springframework.security.core.Authentication.class);
        verify(authentication).authenticate(credentials.capture());
        assertThat(credentials.getValue().getName()).isEqualTo("customer@example.com");
    }

    @Test
    void failedLoginDoesNotIssueToken() {
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));
        assertThatThrownBy(() -> service.login(new LoginRequest("customer@example.com", TestSecurity.PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(users, tokens);
    }

    @Test
    void credentialsAndTokenDtoToStringDoNotLeakSecrets() {
        assertThat(new RegisterRequest("c@example.com", TestSecurity.PASSWORD).toString()).doesNotContain(TestSecurity.PASSWORD);
        assertThat(new LoginRequest("c@example.com", TestSecurity.PASSWORD).toString()).doesNotContain(TestSecurity.PASSWORD);
        assertThat(new TokenResponse(TestSecurity.PASSWORD, "Bearer", 900, null).toString()).doesNotContain(TestSecurity.PASSWORD);
    }
}
