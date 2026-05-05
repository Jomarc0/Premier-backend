package com.premier.service;

import com.premier.request.TopUpRequestDto;
import com.premier.response.ApiResponse;
import com.premier.response.TopUpResponse;
import com.premier.model.*;
import com.premier.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayMongoService {

    @Value("${paymongo.secret-key}")
    private String secretKey;

    @Value("${paymongo.base-url}")
    private String baseUrl;

    private final TopUpRequestRepository topUpRequestRepository;
    private final PassengerRepository passengerRepository;
    private final TransactionRepository transactionRepository;
    private final FirebaseService firebaseService;
    private final RestTemplate restTemplate;

    //  CREATE PAYMENT LINK 

    @Transactional
    public ApiResponse<TopUpResponse> initiateTopUp(
            Passenger passenger, TopUpRequestDto dto) {

        String referenceNumber = "PMR-" +
            UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase();

        // PayMongo amount is in centavos
        long amountInCentavos = dto.getAmount()
                .multiply(BigDecimal.valueOf(100)).longValue();


        Map<String, Object> attributes = new HashMap<>();
        attributes.put("amount", amountInCentavos);
        attributes.put("currency", "PHP");
        attributes.put("description",
                "Premier Transit Top-Up - " + referenceNumber);
        attributes.put("remarks",
                "Passenger ID: " + passenger.getId());  

        Map<String, Object> data = new HashMap<>();
        data.put("attributes", attributes);

        Map<String, Object> payload = new HashMap<>();
        payload.put("data", data);

        HttpHeaders headers = buildHeaders();
        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(payload, headers);

        try {
            // Create PayMongo Payment Link
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(
                            baseUrl + "/links",
                            entity, Map.class);

            Map responseData =
                    (Map) response.getBody().get("data");
            String linkId = (String) responseData.get("id");
            Map responseAttributes =
                    (Map) responseData.get("attributes");
            String checkoutUrl =
                    (String) responseAttributes.get("checkout_url");

            // Save top-up request to database
            TopUpRequest topUpRequest = new TopUpRequest();
            topUpRequest.setPassenger(passenger);
            topUpRequest.setAmount(dto.getAmount());
            topUpRequest.setPaymongoLinkId(linkId);
            topUpRequest.setPaymongoCheckoutUrl(checkoutUrl);
            topUpRequest.setReferenceNumber(referenceNumber);
            topUpRequest.setStatus(TransactionStatus.PENDING);
            topUpRequestRepository.save(topUpRequest);

            log.info("PayMongo link created: {} for passenger ID: {}",
                    linkId, passenger.getId());

            return ApiResponse.success(
                    "Top-up initiated. Please complete payment.",
                    TopUpResponse.builder()
                            .topUpId(topUpRequest.getId())
                            .amount(dto.getAmount())
                            .checkoutUrl(checkoutUrl)
                            .referenceNumber(referenceNumber)
                            .status("PENDING")
                            .build());

        } catch (Exception e) {
            log.error("PayMongo error: {}", e.getMessage());
            throw new RuntimeException(
                    "Payment initiation failed: " + e.getMessage());
        }
    }

    //WEBHOOK HANDLER 

    @Transactional
    public void handleWebhook(
            String rawBody,
            String paymongoSignature) {

        try {
      
            // In production, verify signature here
            log.info("Webhook received");

        } catch (Exception e) {
            log.error("Webhook error: {}", e.getMessage());
        }
    }

    //PROCESS PAYMENT

    @Transactional
    public ApiResponse<Map<String, Object>> processPayment(
            String referenceNumber) {

        TopUpRequest topUpRequest = topUpRequestRepository
                .findByReferenceNumber(referenceNumber)
                .orElseThrow(() ->
                    new RuntimeException("Transaction not found."));

        if (topUpRequest.getStatus() ==
                TransactionStatus.SUCCESS) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "ALREADY_PROCESSED");
            result.put("balance",
                    topUpRequest.getPassenger().getBalance());
            return ApiResponse.success(
                    "Already processed.", result);
        }

        // Verify payment with PayMongo
        String linkId = topUpRequest.getPaymongoLinkId();
        boolean isPaid = verifyPayment(linkId);

        if (!isPaid) {
            throw new RuntimeException(
                    "Payment not yet completed.");
        }

        //Update passenger balance
        Passenger passenger = topUpRequest.getPassenger();
        BigDecimal amount = topUpRequest.getAmount();
        BigDecimal balanceBefore = passenger.getBalance();
        BigDecimal balanceAfter = balanceBefore.add(amount);

        passenger.setBalance(balanceAfter);
        passengerRepository.save(passenger);

        // Update top-up request status
        topUpRequest.setStatus(TransactionStatus.SUCCESS);
        topUpRequestRepository.save(topUpRequest);

        // Save transaction record
        Transaction tx = new Transaction();
        tx.setPassenger(passenger);
        tx.setType(TransactionType.TOPUP);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setAmount(amount);
        tx.setBalanceBefore(balanceBefore);
        tx.setBalanceAfter(balanceAfter);
        tx.setReferenceNumber(referenceNumber);
        tx.setDescription("Top-up via PayMongo");
        transactionRepository.save(tx);

        // Send push notification - uses specific top-up success method
        if (passenger.getFcmToken() != null) {
            firebaseService.sendTopUpSuccess(
                passenger.getFcmToken(),
                amount.toString(),
                balanceAfter.toString()
            );
        }

        log.info("Top-up SUCCESS: ₱{} for passenger ID: {}",
                amount, passenger.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "SUCCESS");
        result.put("amount", amount);
        result.put("newBalance", balanceAfter);
        result.put("referenceNumber", referenceNumber);

        return ApiResponse.success(
                "Top-up successful! ₱" + amount + " added.",
                result);
    }

    //  VERIFY PAYMENT

    private boolean verifyPayment(String linkId) {
        try {
            HttpHeaders headers = buildHeaders();
            HttpEntity<?> entity =
                    new HttpEntity<>(headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            baseUrl + "/links/" + linkId,
                            HttpMethod.GET,
                            entity, Map.class);

            Map responseData =
                    (Map) response.getBody().get("data");
            Map attributes =
                    (Map) responseData.get("attributes");
            String status =
                    (String) attributes.get("status");

            return "paid".equals(status);

        } catch (Exception e) {
            log.error("Verify payment error: {}",
                    e.getMessage());
            return false;
        }
    }

    // CHECK STATUS 

    public ApiResponse<Map<String, Object>> checkPaymentStatus(
            String referenceNumber) {

        TopUpRequest topUpRequest = topUpRequestRepository
                .findByReferenceNumber(referenceNumber)
                .orElseThrow(() ->
                    new RuntimeException("Transaction not found."));

        Map<String, Object> result = new HashMap<>();
        result.put("referenceNumber", referenceNumber);
        result.put("amount", topUpRequest.getAmount());
        result.put("status", topUpRequest.getStatus());
        result.put("checkoutUrl",
                topUpRequest.getPaymongoCheckoutUrl());

        return ApiResponse.success("Status fetched.", result);
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