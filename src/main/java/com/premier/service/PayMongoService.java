package com.premier.service;

import com.premier.request.TopUpRequestDto;
import com.premier.response.ApiResponse;
import com.premier.response.TopUpResponse;
import com.premier.realtime.RealtimeEventPublisher;
import com.premier.model.*;
import com.premier.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayMongoService {

    @Value("${paymongo.secret-key}")
    private String secretKey;

    @Value("${paymongo.base-url}")
    private String baseUrl;

    @Value("${paymongo.webhook-secret:}")
    private String webhookSecret;

    private final TopUpRequestRepository topUpRequestRepository;
    private final PassengerRepository passengerRepository;
    private final TransactionRepository transactionRepository;
    private final FirebaseService firebaseService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final com.premier.payment.service.PaymentNotificationService paymentNotifications;
    private final com.premier.payment.service.ProviderDeadline providerDeadline;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final com.premier.payment.service.PayMongoSignature signatureVerifier;
    private final com.premier.payment.repository.ProviderEventRepository eventRepository;
    private final com.premier.admin.repository.ActivityLogRepository activityLogs;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @Value("${paymongo.expiration-minutes:15}")
    private int topUpExpirationMinutes;

    // CREATE PAYMENT LINK

    // Network I/O stays outside database transactions. Persist a recovery identity before provider submission.
    public ApiResponse<TopUpResponse> initiateTopUp(Passenger principal, TopUpRequestDto dto) {
        BigDecimal amount = com.premier.payment.service.Money.exact(dto.getAmount());
        if (amount.compareTo(new BigDecimal("20.00")) < 0 || amount.compareTo(new BigDecimal("10000.00")) > 0)
            throw client(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Top-up must be between 20.00 and 10000.00.");
        String reference = "PMR-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        Long id = tx().execute(status -> {
            Passenger passenger = passengerRepository.findLockedById(principal.getId()).orElseThrow(PayMongoService::notFound);
            if (passenger.getStatus() != PassengerStatus.ACTIVE)
                throw client(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is inactive.");
            TopUpRequest request = new TopUpRequest();
            request.setPassenger(passenger); request.setAmount(amount); request.setReferenceNumber(reference);
            request.setStatus(TransactionStatus.PROCESSING);
            return topUpRequestRepository.saveAndFlush(request).getId();
        });
        Map<String, Object> attributes = Map.of("amount", amount.movePointRight(2).longValueExact(), "currency", "PHP",
                "description", "Premier Transit Top-Up via " + normalizePaymentMethod(dto.getPaymentMethod()) + " - " + reference,
                "remarks", reference);
        JsonNode resource;
        try {
            JsonNode response = providerDeadline.call(() -> restTemplate.postForObject(baseUrl + "/links",
                    new HttpEntity<>(Map.of("data", Map.of("attributes", attributes)), buildHeaders()), JsonNode.class));
            resource = requireResource(response);
            validateLink(resource, amount, null, false);
            java.net.URI uri = java.net.URI.create(resource.path("attributes").path("checkout_url").asText());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            tx().executeWithoutResult(status -> topUpRequestRepository.findLockedById(id).orElseThrow().setLastSafeError("PROVIDER_UNAVAILABLE"));
            log.warn("PAYMENT_PROVIDER_UNAVAILABLE reference={} type={}", reference, ex.getClass().getSimpleName());
            return ApiResponse.<TopUpResponse>builder()
                    .success(false)
                    .message("Payment provider is temporarily unavailable. Please try again later.")
                    .code("SERVICE_UNAVAILABLE")
                    .reference(reference)
                    .data(TopUpResponse.builder().topUpId(id).amount(amount).referenceNumber(reference).status("PROCESSING").build())
                    .build();
        } catch (org.springframework.web.client.RestClientException | IllegalArgumentException | com.premier.exception.ClientException ex) {
            tx().executeWithoutResult(status -> topUpRequestRepository.findLockedById(id).orElseThrow().setLastSafeError("PAYMENT_UNKNOWN"));
            log.warn("PAYMENT_INITIATION_UNKNOWN reference={} type={}", reference, ex.getClass().getSimpleName());
            ApiResponse<TopUpResponse> result = ApiResponse.error("Payment link outcome is unknown. Contact support with this reference before trying again.");
            result.setCode("PAYMENT_UNKNOWN"); result.setReference(reference);
            result.setData(TopUpResponse.builder().topUpId(id).amount(amount).referenceNumber(reference).status("PROCESSING").build());
            return result;
        }
        String linkId = resource.path("id").asText();
        String checkout = resource.path("attributes").path("checkout_url").asText();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(topUpExpirationMinutes);
        tx().executeWithoutResult(status -> {
            var request = topUpRequestRepository.findLockedById(id).orElseThrow();
            request.setPaymongoLinkId(linkId); request.setPaymongoCheckoutUrl(checkout);
            request.setStatus(TransactionStatus.PENDING); request.setLastSafeError(null);
            request.setExpiresAt(expiresAt);
            realtimeEventPublisher.adminAndPassenger(principal.getId(), "TOPUP_CREATED", "TOPUP", id);
        });
        return ApiResponse.success("Top-up initiated. Please complete payment.", TopUpResponse.builder()
                .topUpId(id).amount(amount).checkoutUrl(checkout).referenceNumber(reference).status("PENDING").expiresAt(expiresAt).build());
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "GCASH";
        }

        String normalized = paymentMethod.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MAYA" -> "MAYA";
            default -> "GCASH";
        };
    }

    //WEBHOOK HANDLER 

    public void handleWebhook(String rawBody, String signature) {
        signatureVerifier.verify(rawBody, signature);
        JsonNode event;
        try { event = objectMapper.readTree(rawBody).path("data"); }
        catch (Exception ex) { throw invalidEvent(); }
        String eventId = event.path("id").asText();
        JsonNode attributes = event.path("attributes");
        if (!eventId.matches("evt_[A-Za-z0-9_-]{1,110}") || !attributes.path("livemode").isBoolean()
                || attributes.path("livemode").asBoolean() != signatureVerifier.liveMode()) throw invalidEvent();
        JsonNode resource = attributes.path("data");
        String linkId = resource.path("id").asText();
        String eventType = attributes.path("type").asText();
        if (eventType.isBlank() || eventType.length() > 80 || linkId.isBlank() || linkId.length() > 120) throw invalidEvent();
        String payloadHash = hash(rawBody);
        receiveEvent(eventId, linkId, eventType, payloadHash);
        // Retain failed/cancelled/unknown notifications for review; never downgrade a completed credit.
        if (!"link.payment.paid".equals(eventType)) return;
        if (!linkId.matches("link_[A-Za-z0-9_-]{1,110}")) throw invalidEvent();
        var request = topUpRequestRepository.findByPaymongoLinkId(linkId).orElseThrow(() ->
                client(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PENDING", "Payment reference is awaiting reconciliation."));
        completeTopUp(request.getId(), resource, eventId, payloadHash);
    }

    public ApiResponse<Map<String, Object>> processPayment(Passenger principal, String reference) {
        try {
            var request = topUpRequestRepository.findByReferenceNumberAndPassengerId(reference, principal.getId()).orElseThrow(PayMongoService::notFound);
            if (request.getStatus() == TransactionStatus.SUCCESS)
                return ApiResponse.success("Already processed.", tx().execute(status -> result(topUpRequestRepository.findLockedById(request.getId()).orElseThrow())));
            if (request.getPaymongoLinkId() == null)
                throw client(HttpStatus.CONFLICT, "PAYMENT_UNKNOWN", "Payment link requires operator reconciliation. Do not create a replacement payment.");

            // Check current PayMongo link status before attempting completion
            JsonNode resource;
            try {
                resource = retrieveLink(request.getPaymongoLinkId());
            } catch (com.premier.exception.ClientException ex) {
                if ("SERVICE_UNAVAILABLE".equals(ex.getCode())) {
                    throw ex;
                }
                throw ex;
            }

            String linkStatus = resource.path("attributes").path("status").asText();
            if ("expired".equalsIgnoreCase(linkStatus)) {
                // Checkout session expired on PayMongo side - mark local record expired
                tx().executeWithoutResult(status -> {
                    var locked = topUpRequestRepository.findLockedById(request.getId()).orElseThrow();
                    if (locked.getStatus() == TransactionStatus.PENDING) {
                        locked.setStatus(TransactionStatus.EXPIRED);
                        locked.setLastSafeError("EXPIRED");
                        topUpRequestRepository.saveAndFlush(locked);
                    }
                });
                return ApiResponse.<Map<String, Object>>builder()
                        .success(false)
                        .message("Checkout session has expired. You can create a new top-up.")
                        .code("EXPIRED")
                        .reference(request.getReferenceNumber())
                        .data(Map.of("status", "EXPIRED", "referenceNumber", request.getReferenceNumber()))
                        .build();
            }
            if ("active".equalsIgnoreCase(linkStatus)) {
                return ApiResponse.<Map<String, Object>>builder()
                        .success(false)
                        .message("Payment not yet completed. Please complete the checkout.")
                        .code("PAYMENT_PENDING")
                        .reference(request.getReferenceNumber())
                        .data(Map.of("status", "PENDING", "referenceNumber", request.getReferenceNumber(), "checkoutUrl", request.getPaymongoCheckoutUrl()))
                        .build();
            }
            // Only proceed to completeTopUp if status is "paid" (validated inside)
            return ApiResponse.success("Top-up settled.", completeTopUp(request.getId(), resource, null, null));
        } catch (com.premier.exception.ClientException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("PROCESS_PAYMENT_UNEXPECTED reference={} type={}", reference, ex.getClass().getSimpleName(), ex);
            throw client(HttpStatus.INTERNAL_SERVER_ERROR, "PROCESS_PAYMENT_ERROR", "Unable to verify payment. Please try again.");
        }
    }

    private JsonNode retrieveLink(String linkId) {
        if (!linkId.matches("link_[A-Za-z0-9_-]{1,110}")) throw invalidEvent();
        try {
            return requireResource(providerDeadline.call(() -> restTemplate.exchange(baseUrl + "/links/" + linkId, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()), JsonNode.class).getBody()));
        } catch (org.springframework.web.client.RestClientException | IllegalArgumentException ex) {
            log.warn("PAYMENT_PROVIDER_UNAVAILABLE type={}", ex.getClass().getSimpleName());
            throw client(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Payment verification is temporarily unavailable. Retry the original reference.");
        }
    }

    public boolean reconcile(Long id) {
        var request = topUpRequestRepository.findById(id).orElseThrow(PayMongoService::notFound);
        if (request.getStatus() == TransactionStatus.SUCCESS) return true;
        if (request.getPaymongoLinkId() == null) return false;
        JsonNode resource = retrieveLink(request.getPaymongoLinkId());
        validateLink(resource, request.getAmount(), request.getPaymongoLinkId(), false);
        if (!"paid".equals(resource.path("attributes").path("status").asText())) return false;
        completeTopUp(id, resource, null, null);
        return true;
    }

    // CHECK STATUS 

    @Transactional(readOnly = true)
    public ApiResponse<?> pendingTopUps(Passenger passenger, int page) {
        if (page < 0 || page > 1000) throw client(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid page.");
        var slice = topUpRequestRepository.findByPassengerIdAndStatusInOrderByCreatedAtDesc(passenger.getId(),
                List.of(TransactionStatus.PENDING, TransactionStatus.PROCESSING),
                org.springframework.data.domain.PageRequest.of(page, 25));
        return ApiResponse.success("Pending top-ups fetched.", slice.map(this::pendingSummary));
    }

    private Map<String, Object> pendingSummary(TopUpRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("referenceNumber", request.getReferenceNumber()); data.put("amount", request.getAmount());
        data.put("status", request.getStatus()); data.put("createdAt", request.getCreatedAt());
        data.put("expiresAt", request.getExpiresAt());
        data.put("checkoutUrl", request.getPaymongoCheckoutUrl()); data.put("code", request.getLastSafeError());
        data.put("requiresReconciliation", request.getPaymongoLinkId() == null
                || request.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusDays(1)));
        return data;
    }

    public ApiResponse<Map<String, Object>> checkPaymentStatus(
            Passenger principal,
            String referenceNumber) {

        TopUpRequest topUpRequest = topUpRequestRepository
                .findByReferenceNumberAndPassengerId(referenceNumber, principal.getId())
                .orElseThrow(PayMongoService::notFound);

        Map<String, Object> result = new HashMap<>();
        result.put("referenceNumber", referenceNumber);
        result.put("amount", topUpRequest.getAmount());
        result.put("status", topUpRequest.getStatus());
        result.put("checkoutUrl",
                topUpRequest.getPaymongoCheckoutUrl());

        return ApiResponse.success("Status fetched.", result);
    }

    private Map<String, Object> completeTopUp(Long id, JsonNode resource, String eventId, String payloadHash) {
        return tx().execute(status -> {
            // Top-up -> wallet. Re-read status under the lock for webhook/verify/reconciler races.
            var request = topUpRequestRepository.findLockedById(id).orElseThrow(PayMongoService::notFound);
            validateLink(resource, request.getAmount(), request.getPaymongoLinkId(), true);
            String remarks = resource.path("attributes").path("remarks").asText();
            if (!remarks.isBlank() && !remarks.equals(request.getReferenceNumber())) throw invalidEvent();
            if (request.getStatus() != TransactionStatus.SUCCESS && request.getStatus() != TransactionStatus.PENDING
                    && request.getStatus() != TransactionStatus.PROCESSING) throw invalidEvent();
            if (eventId != null) {
                var seen = eventRepository.findById(eventId);
                if (seen.isPresent()) {
                    if (!Objects.equals(seen.get().getResourceId(), request.getPaymongoLinkId())
                            || !Objects.equals(seen.get().getPayloadHash(), payloadHash)) throw invalidEvent();
                    seen.get().setStatus("SETTLED");
                } else {
                    var event = new com.premier.payment.model.ProviderEvent();
                    event.setId(eventId); event.setResourceId(request.getPaymongoLinkId()); event.setPayloadHash(payloadHash);
                    event.setStatus("SETTLED"); event.setReceivedAt(java.time.Instant.now());
                    entityManager.persist(event); entityManager.flush();
                }
            }
            if (request.getStatus() == TransactionStatus.SUCCESS) return result(request);
            Passenger passenger = passengerRepository.findLockedById(request.getPassenger().getId()).orElseThrow(PayMongoService::notFound);
            BigDecimal before = passenger.getBalance();
            BigDecimal after = before.add(com.premier.payment.service.Money.exact(request.getAmount()));
            passenger.setBalance(after);
            Transaction ledger = Transaction.builder().passenger(passenger).type(TransactionType.TOPUP).status(TransactionStatus.SUCCESS)
                    .amount(request.getAmount()).balanceBefore(before).balanceAfter(after).referenceNumber(request.getReferenceNumber())
                    .paymentMethod(PaymentMethod.PAYMONGO).description("Top-up via PayMongo").build();
            transactionRepository.saveAndFlush(ledger);
            request.setStatus(TransactionStatus.SUCCESS); request.setSettledBalance(after); request.setLastSafeError(null);
            realtimeEventPublisher.adminAndPassenger(passenger.getId(), "TOPUP_COMPLETED", "TRANSACTION", ledger.getId());
            paymentNotifications.enqueue(passenger.getId(), request.getReferenceNumber(), "TOPUP");
            return result(request);
        });
    }

    private Map<String, Object> result(TopUpRequest request) {
        BigDecimal balance = request.getSettledBalance();
        if (balance == null) balance = transactionRepository.findByReferenceNumberAndPassengerId(request.getReferenceNumber(),
                request.getPassenger().getId()).map(Transaction::getBalanceAfter).orElseThrow(PayMongoService::invalidEvent);
        return Map.of("status", "SUCCESS", "amount", request.getAmount(), "newBalance", balance, "referenceNumber", request.getReferenceNumber());
    }

    private void receiveEvent(String id, String resourceId, String eventType, String payloadHash) {
        try {
            tx().executeWithoutResult(status -> {
                if (eventRepository.existsById(id)) return;
                var event = new com.premier.payment.model.ProviderEvent();
                event.setId(id); event.setResourceId(resourceId); event.setEventType(eventType); event.setPayloadHash(payloadHash);
                event.setStatus("link.payment.paid".equals(eventType) ? "RECEIVED" : "REVIEW");
                event.setReceivedAt(java.time.Instant.now()); entityManager.persist(event); entityManager.flush();
            });
        } catch (RuntimeException ex) {
            // A duplicate insert can race across processes. Only a verified unique-constraint conflict is recoverable here.
            Throwable cause = ex;
            boolean duplicate = false;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) duplicate = true;
                cause = cause.getCause();
            }
            if (!duplicate) throw ex;
        }
        var stored = eventRepository.findById(id).orElseThrow();
        if (!Objects.equals(stored.getResourceId(), resourceId) || !Objects.equals(stored.getPayloadHash(), payloadHash)) throw invalidEvent();
    }

    private void validateLink(JsonNode resource, BigDecimal amount, String expectedId, boolean paid) {
        JsonNode attrs = resource.path("attributes"); String id = resource.path("id").asText();
        if (!"link".equals(resource.path("type").asText())
                || !id.matches("link_[A-Za-z0-9_-]{1,110}") || (expectedId != null && !expectedId.equals(id))
                || !attrs.path("amount").isIntegralNumber() || !attrs.path("amount").canConvertToLong()
                || attrs.path("amount").longValue() != com.premier.payment.service.Money.exact(amount).movePointRight(2).longValueExact()
                || !"PHP".equals(attrs.path("currency").asText()) || !attrs.path("livemode").isBoolean()
                || attrs.path("livemode").asBoolean() != signatureVerifier.liveMode()) throw invalidEvent();
        if (paid && !"paid".equals(attrs.path("status").asText()))
            throw client(HttpStatus.CONFLICT, "PAYMENT_PENDING", "Payment has not been confirmed.");
    }
    private JsonNode requireResource(JsonNode response) {
        if (response == null || !response.path("data").isObject()) throw new IllegalArgumentException("Invalid provider response.");
        return response.path("data");
    }
    private static com.premier.exception.ClientException notFound() { return client(HttpStatus.NOT_FOUND, "NOT_FOUND", "Transaction not found."); }
    private static com.premier.exception.ClientException invalidEvent() { return client(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_MISMATCH", "Payment details could not be verified. Request operator review."); }
    private static com.premier.exception.ClientException client(HttpStatus status, String code, String message) {
        return new com.premier.exception.ClientException(status, code, message);
    }
    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("Unable to identify provider event."); }
    }

    // EXPIRE PAYMONGO CHECKOUT SESSION
    // Called when a pending top-up expires; attempts to cancel the provider checkout session
    public void expireCheckoutSession(String linkId) {
        if (linkId == null || !linkId.matches("link_[A-Za-z0-9_-]{1,110}")) return;
        try {
            HttpEntity<Void> request = new HttpEntity<>(buildHeaders());
            restTemplate.exchange(baseUrl + "/links/" + linkId + "/expire", HttpMethod.POST, request, JsonNode.class);
            log.info("PAYMONGO_CHECKOUT_EXPIRED link_id={}", linkId);
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND || ex.getStatusCode() == HttpStatus.CONFLICT) {
                log.info("PAYMONGO_CHECKOUT_ALREADY_TERMINAL link_id={} status={}", linkId, ex.getStatusCode());
            } else {
                log.warn("PAYMONGO_EXPIRE_FAILED link_id={} status={}", linkId, ex.getStatusCode());
            }
        } catch (Exception ex) {
            log.warn("PAYMONGO_EXPIRE_ERROR link_id={} type={}", linkId, ex.getClass().getSimpleName());
        }
    }

    // BUILD HEADERS 

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(
                List.of(MediaType.APPLICATION_JSON));
        String encoded = Base64.getEncoder()
                .encodeToString(
                    (secretKey + ":").getBytes(
                        StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encoded);
        return headers;
    }
}
