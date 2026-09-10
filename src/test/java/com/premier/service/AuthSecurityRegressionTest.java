package com.premier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premier.exception.*;
import com.premier.model.*;
import com.premier.repository.*;
import com.premier.request.*;
import com.premier.response.AuthResponse;
import com.premier.security.*;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityRegressionTest {
    @Autowired AuthService auth;
    @Autowired PassengerRepository passengers;
    @Autowired AuthChallengeRepository challenges;
    @Autowired BiometricAuthService biometrics;
    @Autowired JwtUtil jwt;
    @Autowired TotpService totp;
    @Autowired TotpSecretCrypto crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired com.premier.admin.repository.AdminRepository admins;
    @Autowired com.premier.admin.service.AdminService adminService;
    @Autowired com.premier.admin.security.AdminJwtUtil adminJwt;
    @Autowired com.premier.support.repository.SupportTicketRepository tickets;
    @Autowired com.premier.support.service.SupportTicketService support;

    private com.premier.admin.model.Admin recoveryAdmin() {
        String id = UUID.randomUUID().toString();
        return admins.saveAndFlush(com.premier.admin.model.Admin.builder().adminId(id.substring(0,12))
                .username(id).fullName("Recovery verifier").password(encoder.encode(id))
                .role(com.premier.admin.model.AdminRole.SUPER_ADMIN).is2FaEnabled(true).build());
    }
    private com.premier.support.model.SupportTicket recoveryTicket(Passenger p) {
        return tickets.saveAndFlush(com.premier.support.model.SupportTicket.builder()
                .ticketNumber(UUID.randomUUID().toString()).cardNumber(p.getCardNumber()).passenger(p)
                .email("synthetic@example.invalid").issueType(com.premier.support.model.SupportTicketIssueType.LOGIN_PROBLEM)
                .reason("Manual identity verification requested").build());
    }

    @Test void manualIntakeCreatesLinkedTicketWithoutAuthorizingRecovery() {
        var passenger = issued(UUID.randomUUID().toString()); var admin = recoveryAdmin();
        var ticket = auth.openRecoveryTicket(admin, passenger.getCardNumber(), "synthetic@example.invalid", "Synthetic external support reference").getData();
        assertThat(ticket.getPassengerId()).isEqualTo(passenger.getId());
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getSessionVersion()).isZero();
        assertThat(challenges.existsBySupportTicketId(ticket.getId())).isFalse();
        admin.setRole(com.premier.admin.model.AdminRole.ADMIN); admins.saveAndFlush(admin);
        assertThatThrownBy(() -> auth.openRecoveryTicket(admin, passenger.getCardNumber(), "synthetic@example.invalid", "Unauthorized support intake"))
                .isInstanceOf(ClientException.class);
    }
    @Test void closedRecoveryTicketCannotResetAccount() {
        var passenger = issued(UUID.randomUUID().toString()); var ticket = recoveryTicket(passenger);
        ticket.setStatus(com.premier.support.model.SupportTicketStatus.RESOLVED); tickets.saveAndFlush(ticket);
        assertThatThrownBy(() -> auth.authorizeRecovery(recoveryAdmin(), ticket.getId(), "Verified but closed ticket", true))
                .isInstanceOf(ClientException.class);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getSessionVersion()).isZero();
    }

    @Test void controlledRecoveryRevokesOldAccessUsesNewSeedAndCannotReplay() throws Exception {
        String proof = UUID.randomUUID().toString(); Passenger p = issued(proof);
        String enroll = auth.register(registerRequest(p)).getData().getTempToken();
        String oldSeed = auth.getTotpSetup(enroll).getData().getManualEntryKey();
        String oldAccess = auth.verifyTotp(verification(enroll, oldSeed)).getData().getToken();
        var ticket = recoveryTicket(p); var admin = recoveryAdmin();
        var result = auth.authorizeRecovery(admin, ticket.getId(), "Verified in person against approved procedure", true);
        String recovery = (String) result.getData().get("recoveryToken");
        assertThatThrownBy(() -> auth.getTotpSetup(recovery)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> auth.authorizeRecovery(admin, ticket.getId(), "Repeated authorization request", true)).isInstanceOf(ClientException.class);
        mvc.perform(get("/api/passenger/auth/profile").header("Authorization", "Bearer " + oldAccess)).andExpect(status().isUnauthorized());
        String newEnrollment = auth.completeRecovery(recovery).getData().getTempToken();
        assertThatThrownBy(() -> auth.completeRecovery(recovery)).isInstanceOf(ClientException.class);
        String newSeed = auth.getTotpSetup(newEnrollment).getData().getManualEntryKey();
        assertThat(newSeed).isNotEqualTo(oldSeed);
        assertThat(auth.verifyTotp(verification(newEnrollment, newSeed)).getData().getToken()).isNotBlank();
        assertThat(support.getPassengerTicket(p, ticket.getId()).getData().getAdminNotes()).isNull();
    }

    @Test void recoveryRequiresVerifiedCurrentSuperAdminAndOwnedTicket() {
        Passenger p = issued(UUID.randomUUID().toString()); var ticket = recoveryTicket(p); var admin = recoveryAdmin();
        assertThatThrownBy(() -> auth.authorizeRecovery(admin, ticket.getId(), "Identity not verified", false)).isInstanceOf(ClientException.class);
        admin.setRole(com.premier.admin.model.AdminRole.ADMIN); admins.saveAndFlush(admin);
        assertThatThrownBy(() -> auth.authorizeRecovery(admin, ticket.getId(), "Unauthorized verifier", true)).isInstanceOf(ClientException.class);
        assertThat(passengers.findById(p.getId()).orElseThrow().getSessionVersion()).isZero();
    }

    @Test void expiredRecoveryFailsWithoutGeneratingSeed() {
        Passenger p = issued(UUID.randomUUID().toString()); var ticket = recoveryTicket(p);
        String token = (String) auth.authorizeRecovery(recoveryAdmin(), ticket.getId(), "Verified against approved identity procedure", true)
                .getData().get("recoveryToken");
        AuthChallenge recovery = challenges.findById(jwt.challengeId(token)).orElseThrow();
        recovery.setExpiresAt(Instant.now().minusSeconds(1)); challenges.saveAndFlush(recovery);
        assertThatThrownBy(() -> auth.completeRecovery(token)).isInstanceOf(ClientException.class);
        assertThat(passengers.findById(p.getId()).orElseThrow().getTwofaSecret()).isNull();
    }

    @Test void privilegedEnrollmentTokenCannotAccessAdminOperationsOrRevealEnrolledSeed() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String password = UUID.randomUUID().toString();
        var admin = admins.saveAndFlush(com.premier.admin.model.Admin.builder()
                .adminId(suffix.substring(0, 12)).username(suffix).fullName("Synthetic Test Admin")
                .password(encoder.encode(password)).role(com.premier.admin.model.AdminRole.SUPER_ADMIN).build());
        var challenge = adminService.login(admin.getUsername(), password, null, "127.0.0.1").getData();
        String token = (String) challenge.get("token");
        assertThat(adminJwt.isEnrollmentToken(token)).isTrue();
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/auth/totp/setup").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        var setup = adminService.getAdminTotpSetup(admin).getData();
        String code = new DefaultCodeGenerator().generate(setup.getManualEntryKey(), Instant.now().getEpochSecond()/30);
        String full = (String)adminService.verifyAdminTotp(admin, code).getData().get("token");
        assertThat(adminJwt.isEnrollmentToken(full)).isFalse();
        assertThat(adminJwt.isCurrentSession(token, admins.findById(admin.getId()).orElseThrow())).isFalse();
        var enabled = adminService.getAdminTotpSetup(admin).getData();
        assertThat(enabled.is2FaEnabled()).isTrue();
        assertThat(enabled.getManualEntryKey()).isNull(); assertThat(enabled.getQrCodeUrl()).isNull();
        assertThatThrownBy(() -> adminService.verifyAdminTotp(admin, code)).isInstanceOf(ClientException.class);
    }

    private Passenger issued(String proof) {
        return passengers.saveAndFlush(Passenger.builder().cardNumber(UUID.randomUUID().toString())
                .status(PassengerStatus.AVAILABLE).is2FaEnabled(false).build());
    }
    private RegisterRequest registerRequest(Passenger passenger) {
        RegisterRequest request = new RegisterRequest();
        request.setCardNumber(passenger.getCardNumber()); return request;
    }
    private AuthResponse login(Passenger passenger) {
        LoginRequest request = new LoginRequest(); request.setCardNumber(passenger.getCardNumber());
        return auth.login(request).getData();
    }
    private TotpVerifyRequest verification(String token, String secret) throws Exception {
        TotpVerifyRequest request = new TotpVerifyRequest(); request.setTempToken(token);
        request.setTotpCode(new DefaultCodeGenerator().generate(secret, Instant.now().getEpochSecond() / 30));
        return request;
    }

    @Test void cardNumberStartsAuthenticatorEnrollmentWithoutProof() {
        Passenger passenger = issued(UUID.randomUUID().toString());
        AuthResponse login = login(passenger);
        assertThat(login.isRequireSetup()).isTrue();
        assertThat(login.getTempToken()).isNotBlank();
        assertThat(jwt.extractTokenType(login.getTempToken())).isEqualTo("ENROLL");
        Passenger enrolled = passengers.findById(passenger.getId()).orElseThrow();
        assertThat(enrolled.getTwofaSecret()).isNotBlank();
    }

    @Test void legacyActivationCodeFieldIsIgnoredDuringEnrollment() throws Exception {
        Passenger passenger = issued(UUID.randomUUID().toString());
        String response = mvc.perform(post("/api/passenger/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardNumber\":\"" + passenger.getCardNumber() + "\",\"activationCode\":\"wrong-or-old-code\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).path("data").path("tempToken").asText();
        assertThat(token).isNotBlank();
        assertThat(jwt.extractTokenType(token)).isEqualTo("ENROLL");
    }

    @Test void repeatedEnrollmentRequestsReusePassengerSeedAndIssueFreshChallenges() {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        String first = auth.register(registerRequest(passenger)).getData().getTempToken();
        String firstSecret = passengers.findById(passenger.getId()).orElseThrow().getTwofaSecret();
        String second = login(passenger).getTempToken();
        String secondSecret = passengers.findById(passenger.getId()).orElseThrow().getTwofaSecret();
        assertThat(first).isNotEqualTo(second);
        assertThat(firstSecret).isEqualTo(secondSecret);
        assertThat(jwt.extractTokenType(second)).isEqualTo("ENROLL");
    }

    @Test void existingPassengerAndLegacyTemporaryTokenCannotReadSeed() throws Exception {
        Passenger passenger = issued(UUID.randomUUID().toString());
        passenger.setStatus(PassengerStatus.ACTIVE); passenger.setIs2FaEnabled(true);
        passenger.setTwofaSecret(crypto.encrypt(totp.generateSecret())); passengers.saveAndFlush(passenger);
        String token = login(passenger).getTempToken();
        assertThatThrownBy(() -> auth.getTotpSetup(token)).isInstanceOf(ClientException.class);
        mvc.perform(get("/api/passenger/auth/totp/setup").header("Authorization", "Bearer " + token)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/passenger/auth/totp/setup")).andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> auth.getTotpSetup(jwt.generateTempToken(passenger.getId()))).isInstanceOf(ClientException.class);
        assertThat(mapper.writeValueAsString(passenger)).doesNotContain("twofaSecret", "fcmToken");
    }

    @Test void enrollmentChallengeCannotBeForgedReusedOrAppliedToAnotherPassenger() throws Exception {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        String token = auth.register(registerRequest(passenger)).getData().getTempToken();
        var setup = auth.getTotpSetup(token).getData();
        assertThat(setup.getQrImageDataUri()).startsWith("data:image/png;base64,");
        Passenger other = issued(UUID.randomUUID().toString());
        String forged = jwt.generateChallenge(other.getId(), "ENROLL", jwt.challengeId(token), Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> auth.getTotpSetup(forged)).isInstanceOf(ClientException.class);
        var verify = verification(token, setup.getManualEntryKey());
        AuthResponse response = auth.verifyTotp(verify).getData();
        assertThat(response.getToken()).isNotBlank();
        assertThatThrownBy(() -> auth.verifyTotp(verify)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> auth.getTotpSetup(token)).isInstanceOf(ClientException.class);
        assertThat(passengers.findById(passenger.getId()).orElseThrow().getStatus()).isEqualTo(PassengerStatus.ACTIVE);
    }

    @Test void expiredAndUnregisteredChallengesFailClosed() {
        Passenger passenger = issued(UUID.randomUUID().toString());
        String token = jwt.generateChallenge(passenger.getId(), "ENROLL", UUID.randomUUID().toString(), Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> auth.getTotpSetup(token)).isInstanceOf(ClientException.class);
        String expired = jwt.generateChallenge(passenger.getId(), "ENROLL", UUID.randomUUID().toString(), Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> auth.getTotpSetup(expired)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> biometrics.enroll(passenger, "test-device")).isInstanceOf(InvalidBiometricTokenException.class);
    }

    @Test void mfaFailuresSurviveRollbackAndNewChallengesDoNotBypassLock() {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        String token = auth.register(registerRequest(passenger)).getData().getTempToken();
        TotpVerifyRequest bad = new TotpVerifyRequest(); bad.setTempToken(token); bad.setTotpCode("not-a-code");
        for(int i=0;i<3;i++) assertThatThrownBy(() -> auth.verifyTotp(bad)).isInstanceOf(InvalidTotpException.class);
        Passenger locked = passengers.findById(passenger.getId()).orElseThrow();
        assertThat(locked.getMfaFailures()).isEqualTo(3); assertThat(locked.getMfaLockedUntil()).isAfter(Instant.now());
    }

    @Test void revocationInvalidatesAccessAndOutstandingChallenges() throws Exception {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        String token = auth.register(registerRequest(passenger)).getData().getTempToken();
        String secret = auth.getTotpSetup(token).getData().getManualEntryKey();
        String access = auth.verifyTotp(verification(token, secret)).getData().getToken();
        String pending = login(passenger).getTempToken();
        auth.revokeSessions(passengers.findById(passenger.getId()).orElseThrow());
        assertThat(jwt.isCurrentSession(access, passengers.findById(passenger.getId()).orElseThrow())).isFalse();
        assertThatThrownBy(() -> auth.verifyTotp(verification(pending, secret))).isInstanceOf(ClientException.class);
    }

    @Test void frozenAndRevokedCardsCannotEnrollAndInvalidRequestsDoNotMutate() {
        for (PassengerStatus state : List.of(PassengerStatus.FROZEN, PassengerStatus.BLOCKED, PassengerStatus.LOST)) {
            String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
            passenger.setStatus(state); passengers.saveAndFlush(passenger);
            assertThatThrownBy(() -> auth.register(registerRequest(passenger))).isInstanceOf(ClientException.class);
            Passenger actual = passengers.findById(passenger.getId()).orElseThrow();
            assertThat(actual.getStatus()).isEqualTo(state);
        }
        assertThatThrownBy(() -> auth.register(new RegisterRequest())).isInstanceOf(ClientException.class);
    }

    @Test void concurrentEnrollmentVerificationHasOnlyOneWinner() throws Exception {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        String token = auth.register(registerRequest(passenger)).getData().getTempToken();
        var request = verification(token, auth.getTotpSetup(token).getData().getManualEntryKey());
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> { start.await();
                try { auth.verifyTotp(request); return true; } catch (ClientException expected) { return false; }
            }));
            start.countDown(); int successes = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void directHttpLegacyActivationFieldIsIgnoredAndRevokedAccessIsRejected() throws Exception {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        Map<String, String> body = new HashMap<>();
        body.put("cardNumber", passenger.getCardNumber());
        body.put("activationCode", UUID.randomUUID().toString());
        mvc.perform(post("/api/passenger/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk());
        String token = auth.register(registerRequest(passenger)).getData().getTempToken();
        String secret = auth.getTotpSetup(token).getData().getManualEntryKey();
        String access = auth.verifyTotp(verification(token, secret)).getData().getToken();
        String normal = login(passenger).getTempToken();
        assertThat(auth.verifyTotp(verification(normal, secret)).getData().getToken()).isNotBlank();
        auth.revokeSessions(passengers.findById(passenger.getId()).orElseThrow());
        mvc.perform(get("/api/passenger/auth/profile").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test void persistedChallengeExpiryOverridesUnexpiredSignedToken() {
        String proof = UUID.randomUUID().toString(); Passenger passenger = issued(proof);
        String token = auth.register(registerRequest(passenger)).getData().getTempToken();
        AuthChallenge challenge = challenges.findById(jwt.challengeId(token)).orElseThrow();
        challenge.setExpiresAt(Instant.now().minusSeconds(1)); challenges.saveAndFlush(challenge);
        assertThatThrownBy(() -> auth.getTotpSetup(token)).isInstanceOf(ClientException.class);
    }
}
