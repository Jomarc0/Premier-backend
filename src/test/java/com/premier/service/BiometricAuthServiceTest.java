package com.premier.service;

import com.premier.exception.InvalidBiometricTokenException;
import com.premier.model.BiometricRefreshToken;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.BiometricRefreshTokenRepository;
import com.premier.response.AuthResponse;
import com.premier.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiometricAuthServiceTest {

    @Mock BiometricRefreshTokenRepository tokenRepository;
    @Mock JwtUtil jwtUtil;

    private BiometricAuthService service;

    @BeforeEach
    void setUp() {
        service = new BiometricAuthService(tokenRepository, jwtUtil);
        ReflectionTestUtils.setField(service, "refreshExpirationDays", 30L);
    }

    @Test
    void enrollStoresOnlyHashAndReturnsThirtyDayCredential() {
        Passenger passenger = passenger();
        when(jwtUtil.generateFullToken(10L)).thenReturn("access-token");
        when(tokenRepository.save(any(BiometricRefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = service.enroll(passenger, "device-1").getData();

        ArgumentCaptor<BiometricRefreshToken> captor =
                ArgumentCaptor.forClass(BiometricRefreshToken.class);
        verify(tokenRepository).save(captor.capture());
        BiometricRefreshToken stored = captor.getValue();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(response.getRefreshToken());
        assertThat(response.getToken()).isEqualTo("access-token");
        assertThat(response.getRefreshTokenExpiresAt())
                .isAfter(Instant.now().plusSeconds(29L * 24 * 60 * 60));
    }

    @Test
    void refreshRotatesCredentialAndRevokesPreviousToken() {
        String original = "original-refresh-token";
        BiometricRefreshToken stored = BiometricRefreshToken.builder()
                .passenger(passenger())
                .tokenHash(hash(original))
                .deviceId("device-1")
                .createdAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(tokenRepository.findLockedByTokenHash(hash(original))).thenReturn(Optional.of(stored));
        when(tokenRepository.saveAndFlush(stored)).thenReturn(stored);
        when(tokenRepository.save(any(BiometricRefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtUtil.generateFullToken(10L)).thenReturn("new-access-token");

        AuthResponse response = service.refresh(original, "device-1").getData();

        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getReplacedByHash()).hasSize(64);
        assertThat(response.getRefreshToken()).isNotEqualTo(original);
        assertThat(response.getToken()).isEqualTo("new-access-token");
    }

    @Test
    void expiredCredentialCannotRefresh() {
        String original = "expired-refresh-token";
        BiometricRefreshToken stored = BiometricRefreshToken.builder()
                .passenger(passenger())
                .tokenHash(hash(original))
                .deviceId("device-1")
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(tokenRepository.findLockedByTokenHash(hash(original))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.refresh(original, "device-1"))
                .isInstanceOf(InvalidBiometricTokenException.class);
    }

    private Passenger passenger() {
        return Passenger.builder().id(10L).status(PassengerStatus.ACTIVE).build();
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
