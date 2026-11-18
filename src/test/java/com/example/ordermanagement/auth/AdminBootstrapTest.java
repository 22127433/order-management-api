package com.example.ordermanagement.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.support.TestSecurity;
import jakarta.validation.Validation;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {
    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwords;

    @Test
    void explicitBootstrapCreatesAdminWithEncodedPassword() {
        when(passwords.encode(TestSecurity.PASSWORD)).thenReturn(TestSecurity.PASSWORD_HASH);
        bootstrap("admin@example.com", TestSecurity.PASSWORD);
        var saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo(TestSecurity.PASSWORD_HASH);
    }

    @Test
    void refusesToPromoteCustomer() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(TestSecurity.user(1L)));
        assertThatThrownBy(() -> bootstrap("admin@example.com", TestSecurity.PASSWORD)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(passwords);
    }

    @Test
    void existingAdminPasswordIsNeverResetByStartup() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(AppUser.admin("admin@example.com", TestSecurity.PASSWORD_HASH)));
        bootstrap("admin@example.com", TestSecurity.PASSWORD);
        verifyNoInteractions(passwords);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void invalidConfigurationFailsWithoutLeakingPassword() {
        String invalid = "short";
        assertThatThrownBy(() -> bootstrap("not-an-email", invalid)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(invalid);
        verifyNoInteractions(users, passwords);
    }

    private void bootstrap(String email, String password) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            new AdminBootstrap(users, passwords, factory.getValidator(), email, password).run(new DefaultApplicationArguments());
        }
    }
}
