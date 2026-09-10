package com.premier.service;

import com.premier.request.*;
import com.premier.response.*;
import com.premier.exception.*;
import com.premier.model.*;
import com.premier.repository.*;
import com.premier.security.*;
import com.premier.realtime.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final PassengerRepository passengerRepository;
    private final AuthChallengeRepository challengeRepository;
    private final BiometricRefreshTokenRepository biometricRepository;
    private final JwtUtil jwtUtil;
    private final TotpService totpService;
    private final TotpSecretCrypto totpSecretCrypto;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final com.premier.admin.repository.AdminRepository adminRepository;
    private final com.premier.support.repository.SupportTicketRepository supportTickets;
    private final com.premier.admin.repository.ActivityLogRepository activityLogs;
    private final FareQrTokenRepository fareTokens;

    @Transactional
    public ApiResponse<com.premier.support.response.SupportTicketResponse> openRecoveryTicket(
            com.premier.admin.model.Admin principal, String cardNumber, String email, String reason) {
        var admin = requireRecoveryAdmin(principal);
        if (cardNumber == null || email == null || reason == null || reason.trim().length() < 10 || reason.length() > 500)
            throw new ClientException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Card, contact email, and support reason are required.");
        var passenger = passengerRepository.findByCardNumber(cardNumber.trim()).orElseThrow(() ->
                new ClientException(HttpStatus.NOT_FOUND, "PASSENGER_NOT_FOUND", "Passenger not found."));
        var ticket = com.premier.support.model.SupportTicket.builder()
                .ticketNumber("TICKET-" + UUID.randomUUID().toString().replace("-", ""))
                .passenger(passenger).cardNumber(passenger.getCardNumber()).email(email.trim())
                .issueType(com.premier.support.model.SupportTicketIssueType.LOGIN_PROBLEM)
                .reason(reason.trim()).handledBy(admin)
                .adminNotes("Recovery intake opened by Super Admin. Identity verification is still required; no account access changed.").build();
        supportTickets.saveAndFlush(ticket);
        activityLogs.save(com.premier.admin.model.ActivityLog.builder().admin(admin).action("MFA_RECOVERY_INTAKE")
                .targetType("SUPPORT_TICKET").targetId(ticket.getId()).details("Manual recovery intake; identity verification pending.").build());
        realtimeEventPublisher.admin("TICKET_CREATED", "SUPPORT_TICKET", ticket.getId());
        return ApiResponse.success("Verification ticket opened. Complete the approved identity procedure before authorizing recovery.",
                com.premier.support.response.SupportTicketResponse.from(ticket));
    }

    private com.premier.admin.model.Admin requireRecoveryAdmin(com.premier.admin.model.Admin principal) {
        if (principal == null || principal.getId() == null) throw invalidChallenge();
        var admin = adminRepository.findLockedById(principal.getId()).orElseThrow(this::invalidChallenge);
        if (!admin.isSuperAdmin() || !Boolean.TRUE.equals(admin.getActive()) || admin.isLocked()
                || !Boolean.TRUE.equals(admin.getIs2FaEnabled()) || admin.getSessionVersion() != principal.getSessionVersion())
            throw new ClientException(HttpStatus.FORBIDDEN, "FORBIDDEN", "An authorized Super Admin must verify identity.");
        return admin;
    }

    @Transactional
    public ApiResponse<java.util.Map<String, Object>> authorizeRecovery(com.premier.admin.model.Admin principal,
            Long ticketId, String reason, boolean identityVerified) {
        if (principal == null || ticketId == null || !identityVerified || reason == null
                || reason.trim().length() < 10 || reason.length() > 240) throw invalidChallenge();
        var admin = requireRecoveryAdmin(principal);
        var ticket = supportTickets.findByIdForUpdate(ticketId).orElseThrow(this::invalidChallenge);
        if (ticket.getStatus() == com.premier.support.model.SupportTicketStatus.RESOLVED
                || ticket.getStatus() == com.premier.support.model.SupportTicketStatus.REJECTED)
            throw new ClientException(HttpStatus.CONFLICT, "TICKET_CLOSED", "Open a new verification ticket for recovery.");
        if (ticket.getPassenger() == null || challengeRepository.existsBySupportTicketId(ticketId))
            throw new ClientException(HttpStatus.CONFLICT, "RECOVERY_ALREADY_AUTHORIZED", "A new verified support ticket is required.");
        Passenger passenger = passengerRepository.findLockedById(ticket.getPassenger().getId()).orElseThrow(this::invalidChallenge);
        if (passenger.getStatus() != PassengerStatus.ACTIVE && passenger.getStatus() != PassengerStatus.AVAILABLE)
            throw new ClientException(HttpStatus.CONFLICT, "ACCOUNT_INACTIVE", "Resolve the card restriction before MFA recovery.");
        passenger.setSessionVersion(passenger.getSessionVersion() + 1);
        biometricRepository.revokeAllForPassenger(passenger.getId(), Instant.now());
        passenger.setTwofaSecret(null); passenger.setIs2FaEnabled(false);
        passenger.setStatus(PassengerStatus.AVAILABLE);
        passenger.setMfaFailures(0); passenger.setMfaLockedUntil(null);
        passengerRepository.save(passenger);
        fareTokens.findByPassengerIdAndStatus(passenger.getId(), FareQrTokenStatus.ACTIVE).forEach(t -> {
            t.setStatus(FareQrTokenStatus.EXPIRED); fareTokens.save(t);
        });
        AuthChallenge recovery = new AuthChallenge(); recovery.setId(UUID.randomUUID().toString());
        recovery.setPassenger(passenger); recovery.setPurpose("RECOVERY"); recovery.setExpiresAt(Instant.now().plusSeconds(300));
        recovery.setSessionVersion(passenger.getSessionVersion()); recovery.setSupportTicketId(ticketId); recovery.setAuthorizedBy(admin.getId());
        challengeRepository.saveAndFlush(recovery);
        ticket.setAdminNotes((ticket.getAdminNotes() == null ? "" : ticket.getAdminNotes() + "\n")
                + "MFA recovery: identity verified by Super Admin " + admin.getId() + "; " + reason.trim());
        ticket.setHandledBy(admin); supportTickets.save(ticket);
        activityLogs.save(com.premier.admin.model.ActivityLog.builder().admin(admin).action("PASSENGER_MFA_RECOVERY")
                .targetType("PASSENGER").targetId(passenger.getId()).details("Identity verified; ticket " + ticket.getTicketNumber()
                        + "; sessions revoked; " + reason.trim()).build());
        return ApiResponse.success("Deliver this one-use recovery authorization only to the verified passenger.", java.util.Map.of(
                "recoveryToken", jwtUtil.generateChallenge(passenger.getId(), "RECOVERY", recovery.getId(), recovery.getExpiresAt()),
                "expiresAt", recovery.getExpiresAt(), "ticketNumber", ticket.getTicketNumber()));
    }

    @Transactional
    public ApiResponse<AuthResponse> completeRecovery(String token) {
        Passenger passenger = lockChallengePassenger(token);
        AuthChallenge recovery = validChallenge(token, passenger);
        if (!"RECOVERY".equals(recovery.getPurpose()) || recovery.getSupportTicketId() == null
                || recovery.getAuthorizedBy() == null || Boolean.TRUE.equals(passenger.getIs2FaEnabled())) throw invalidChallenge();
        recovery.setUsedAt(Instant.now()); challengeRepository.save(recovery);
        passenger.setTwofaSecret(totpSecretCrypto.encrypt(totpService.generateSecret()));
        passengerRepository.save(passenger);
        return ApiResponse.success("Recovery authorized. Enroll a new authenticator.", challenge(passenger, "ENROLL"));
    }

    @Transactional
    public ApiResponse<AuthResponse> register(RegisterRequest request) {
        if (request == null || request.getCardNumber() == null || request.getCardNumber().isBlank()
                || request.getCardNumber().length() > 100) throw invalidAccount();
        Passenger passenger = passengerRepository.findLockedByCardNumber(request.getCardNumber().trim())
                .orElseThrow(this::invalidAccount);
        if (passenger.getStatus() != PassengerStatus.AVAILABLE || Boolean.TRUE.equals(passenger.getIs2FaEnabled())) {
            throw invalidAccount();
        }
        if (passenger.getTwofaSecret() == null) {
            passenger.setTwofaSecret(totpSecretCrypto.encrypt(totpService.generateSecret()));
        }
        passenger.setIs2FaEnabled(false);
        passengerRepository.save(passenger);
        return ApiResponse.success("Complete authenticator enrollment.", challenge(passenger, "ENROLL"));
    }

    @Transactional
    public ApiResponse<AuthResponse> login(LoginRequest request) {
        if (request == null || request.getCardNumber() == null || request.getCardNumber().isBlank()
                || request.getCardNumber().length() > 100) throw invalidChallenge();
        Passenger passenger = passengerRepository.findLockedByCardNumber(request.getCardNumber().trim())
                .orElseThrow(() -> new InvalidRfidException("Invalid card number or account status."));
        if (passenger.getStatus() != PassengerStatus.ACTIVE && passenger.getStatus() != PassengerStatus.AVAILABLE) {
            throw new InvalidRfidException("Invalid card number or account status.");
        }
        if (!Boolean.TRUE.equals(passenger.getIs2FaEnabled())) {
            if (passenger.getTwofaSecret() == null) {
                passenger.setTwofaSecret(totpSecretCrypto.encrypt(totpService.generateSecret()));
            }
            passengerRepository.save(passenger);
            return ApiResponse.success("Complete authenticator enrollment.", challenge(passenger, "ENROLL"));
        }
        enforceMfaLock(passenger);
        return ApiResponse.success("Enter your authenticator code.", challenge(passenger, "TEMP"));
    }

    private AuthResponse challenge(Passenger passenger, String purpose) {
        AuthChallenge challenge = new AuthChallenge();
        challenge.setId(UUID.randomUUID().toString());
        challenge.setPassenger(passenger);
        challenge.setPurpose(purpose);
        challenge.setExpiresAt(Instant.now().plusSeconds(300));
        challenge.setSessionVersion(passenger.getSessionVersion());
        challengeRepository.save(challenge);
        return AuthResponse.builder().require2FA(true).requireSetup("ENROLL".equals(purpose))
                .tempToken(jwtUtil.generateChallenge(passenger.getId(), purpose, challenge.getId(), challenge.getExpiresAt()))
                .passengerName("Passenger #" + passenger.getId()).build();
    }

    @Transactional
    public ApiResponse<TotpSetupResponse> getTotpSetup(String token) {
        Passenger passenger = lockChallengePassenger(token);
        AuthChallenge challenge = validChallenge(token, passenger);
        if (!"ENROLL".equals(challenge.getPurpose()) || Boolean.TRUE.equals(passenger.getIs2FaEnabled())
                || passenger.getTwofaSecret() == null) {
            throw invalidChallenge();
        }
        String secret = totpSecretCrypto.decrypt(passenger.getTwofaSecret());
        return ApiResponse.success("Scan this code in your authenticator during this enrollment session.",
                TotpSetupResponse.builder().manualEntryKey(secret)
                        .qrImageDataUri(totpService.generateQrImageDataUri(secret, "Passenger #" + passenger.getId()))
                        .qrCodeUrl(totpService.generateQrCodeUrl(secret, "Passenger #" + passenger.getId()))
                        .is2FaEnabled(false).build());
    }

    // Failed MFA counters must commit; no financial state is changed in this transaction.
    @Transactional(noRollbackFor = InvalidTotpException.class)
    public ApiResponse<AuthResponse> verifyTotp(TotpVerifyRequest request) {
        Passenger passenger = lockChallengePassenger(request.getTempToken());
        AuthChallenge challenge = validChallenge(request.getTempToken(), passenger);
        enforceMfaLock(passenger);
        boolean enrollment = "ENROLL".equals(challenge.getPurpose());
        if (!enrollment && !"TEMP".equals(challenge.getPurpose())) throw invalidChallenge();
        if ((enrollment && Boolean.TRUE.equals(passenger.getIs2FaEnabled()))
                || (!enrollment && !Boolean.TRUE.equals(passenger.getIs2FaEnabled()))) {
            throw invalidChallenge();
        }
        if (request.getTotpCode() == null || !request.getTotpCode().matches("[0-9]{6}")
                || passenger.getTwofaSecret() == null
                || !totpService.verifyCode(totpSecretCrypto.decrypt(passenger.getTwofaSecret()), request.getTotpCode())) {
            passenger.setMfaFailures(passenger.getMfaFailures() + 1);
            if (passenger.getMfaFailures() >= 3) {
                passenger.setMfaLockedUntil(Instant.now().plusSeconds(60L * Math.min(15, passenger.getMfaFailures() - 2)));
            }
            passengerRepository.saveAndFlush(passenger);
            enforceMfaLock(passenger);
            throw new InvalidTotpException("Invalid authenticator code.");
        }
        challenge.setUsedAt(Instant.now());
        challengeRepository.save(challenge);
        passenger.setMfaFailures(0);
        passenger.setMfaLockedUntil(null);
        if (enrollment) {
            passenger.setIs2FaEnabled(true);
            passenger.setStatus(PassengerStatus.ACTIVE);
        }
        if (!totpSecretCrypto.isEncrypted(passenger.getTwofaSecret())) {
            passenger.setTwofaSecret(totpSecretCrypto.encrypt(passenger.getTwofaSecret()));
        }
        passengerRepository.save(passenger);
        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "PASSENGER_UPDATED", "PASSENGER", passenger.getId());
        return ApiResponse.success("Login successful.", AuthResponse.builder()
                .token(jwtUtil.generateFullToken(passenger.getId())).require2FA(false)
                .passengerId(passenger.getId()).passengerName("Passenger #" + passenger.getId()).build());
    }

    private Passenger lockChallengePassenger(String token) {
        if (!jwtUtil.isTokenValid(token)) throw invalidChallenge();
        String purpose = jwtUtil.extractTokenType(token);
        if (!"TEMP".equals(purpose) && !"ENROLL".equals(purpose) && !"RECOVERY".equals(purpose)) throw invalidChallenge();
        return passengerRepository.findLockedById(jwtUtil.extractPassengerId(token)).orElseThrow(this::invalidChallenge);
    }

    private AuthChallenge validChallenge(String token, Passenger passenger) {
        String id = jwtUtil.challengeId(token);
        if (id == null) throw invalidChallenge();
        AuthChallenge challenge = challengeRepository.findById(id).orElseThrow(this::invalidChallenge);
        if (!challenge.getPassenger().getId().equals(passenger.getId())
                || !challenge.getPurpose().equals(jwtUtil.extractTokenType(token))
                || challenge.getSessionVersion() != passenger.getSessionVersion()
                || challenge.getUsedAt() != null || !challenge.getExpiresAt().isAfter(Instant.now())
                || (passenger.getStatus() != PassengerStatus.ACTIVE && passenger.getStatus() != PassengerStatus.AVAILABLE)) {
            throw invalidChallenge();
        }
        return challenge;
    }

    private void enforceMfaLock(Passenger passenger) {
        if (passenger.getMfaLockedUntil() != null && passenger.getMfaLockedUntil().isAfter(Instant.now())) {
            throw new InvalidTotpException("Too many incorrect codes. Please try again later.",
                    Math.max(1, Duration.between(Instant.now(), passenger.getMfaLockedUntil()).toSeconds()));
        }
    }

    @Transactional
    public ApiResponse<Void> revokeSessions(Passenger principal) {
        if (principal == null) throw invalidChallenge();
        Passenger passenger = passengerRepository.findLockedById(principal.getId()).orElseThrow(this::invalidChallenge);
        passenger.setSessionVersion(passenger.getSessionVersion() + 1);
        passengerRepository.save(passenger);
        biometricRepository.revokeAllForPassenger(passenger.getId(), Instant.now());
        return ApiResponse.success("All sessions revoked.");
    }

    public ApiResponse<PassengerResponse> getProfile(Passenger passenger) {
        if (passenger == null) throw invalidChallenge();
        String card = passenger.getCardNumber();
        String masked = card == null ? null : "****" + card.substring(Math.max(0, card.length() - 4));
        return ApiResponse.success("Profile fetched.", PassengerResponse.builder()
                .id(passenger.getId()).balance(passenger.getBalance()).cardNumber(masked).rfidUid(null)
                .is2FaEnabled(passenger.getIs2FaEnabled()).status(passenger.getStatus())
                .createdAt(passenger.getCreatedAt()).build());
    }

    private ClientException invalidAccount() {
        return new ClientException(HttpStatus.UNAUTHORIZED, "INVALID_ACCOUNT", "Invalid card number or account status.");
    }
    private ClientException invalidChallenge() {
        return new ClientException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid, expired or completed authentication challenge.");
    }
}

