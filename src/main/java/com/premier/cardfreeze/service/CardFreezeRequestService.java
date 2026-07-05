package com.premier.cardfreeze.service;

import com.premier.admin.model.ActivityLog;
import com.premier.admin.model.Admin;
import com.premier.admin.repository.ActivityLogRepository;
import com.premier.cardfreeze.model.CardFreezeRequest;
import com.premier.cardfreeze.model.CardFreezeRequestStatus;
import com.premier.cardfreeze.model.CardFreezeRequestType;
import com.premier.cardfreeze.repository.CardFreezeRequestRepository;
import com.premier.cardfreeze.response.CardFreezeRequestResponse;
import com.premier.model.Passenger;
import com.premier.model.PassengerStatus;
import com.premier.repository.PassengerRepository;
import com.premier.response.ApiResponse;
import com.premier.service.FirebaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CardFreezeRequestService {

    private final CardFreezeRequestRepository repository;
    private final PassengerRepository passengerRepository;
    private final ActivityLogRepository activityLogRepository;
    private final FirebaseService firebaseService;

    @Transactional
    public ApiResponse<CardFreezeRequestResponse> submit(Passenger passenger,
                                                         CardFreezeRequestType type,
                                                         String reason) {
        Passenger card = passengerRepository.findById(passenger.getId())
                .orElseThrow(() -> new RuntimeException("Passenger not found."));

        if (card.getRfidUid() == null || card.getRfidUid().isBlank()) {
            throw new RuntimeException("No RFID card is linked to this passenger account.");
        }

        CardFreezeRequestType resolvedType = type != null ? type : CardFreezeRequestType.FREEZE_REQUEST;
        CardFreezeRequest existing = repository.findFirstByPassengerIdAndRfidCardIdAndStatus(
                        passenger.getId(), card.getId(), CardFreezeRequestStatus.PENDING)
                .orElse(null);

        if (existing != null) {
            return ApiResponse.success(
                    "You already have a pending card-freeze request for the card linked to your account (" + mask(card.getCardNumber()) + "). An administrator will review it soon.",
                    CardFreezeRequestResponse.from(existing));
        }

        CardFreezeRequest created = CardFreezeRequest.builder()
                .passenger(card)
                .rfidCard(card)
                .requestType(resolvedType)
                .reason(cleanReason(reason))
                .status(CardFreezeRequestStatus.PENDING)
                .build();

        repository.save(created);
        return ApiResponse.success(
                submittedMessage(resolvedType, card),
                CardFreezeRequestResponse.from(created));
    }

    public ApiResponse<List<CardFreezeRequestResponse>> passengerRequests(Passenger passenger) {
        return ApiResponse.success("Card freeze requests fetched.",
                repository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId())
                        .stream()
                        .map(CardFreezeRequestResponse::from)
                        .toList());
    }

    public ApiResponse<List<CardFreezeRequestResponse>> adminRequests() {
        return ApiResponse.success("Card freeze requests fetched.",
                repository.findAllByOrderByCreatedAtDesc()
                        .stream()
                        .map(CardFreezeRequestResponse::from)
                        .toList());
    }

    public ApiResponse<CardFreezeRequestResponse> adminRequest(Long id) {
        CardFreezeRequest request = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Card freeze request not found."));
        return ApiResponse.success("Card freeze request fetched.", CardFreezeRequestResponse.from(request));
    }

    @Transactional
    public ApiResponse<CardFreezeRequestResponse> approve(Admin admin, Long id) {
        CardFreezeRequest request = pendingRequest(id);
        Passenger card = passengerRepository.findLockedById(request.getRfidCard().getId())
                .orElseThrow(() -> new RuntimeException("Linked RFID card not found."));

        boolean shouldFreezeCard = request.getRequestType() != CardFreezeRequestType.CARD_UPDATE;
        if (shouldFreezeCard) {
            card.setStatus(PassengerStatus.FROZEN);
            passengerRepository.save(card);
        }

        request.setStatus(CardFreezeRequestStatus.APPROVED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewedByAdmin(admin);
        request.setAdminRemarks(shouldFreezeCard
                ? "Approved and card frozen."
                : "Approved for card information/update review.");
        repository.save(request);

        activityLogRepository.save(ActivityLog.builder()
                .admin(admin)
                .action("APPROVE_CARD_FREEZE_REQUEST")
                .targetType("CARD_FREEZE_REQUEST")
                .targetId(request.getId())
                .userId(card.getId())
                .details(shouldFreezeCard
                        ? "Approved card-freeze request and froze masked card " + mask(card.getCardNumber())
                        : "Approved card update request for masked card " + mask(card.getCardNumber()))
                .status("SUCCESS")
                .build());

        firebaseService.sendNotification(card.getFcmToken(),
                shouldFreezeCard ? "RFID card frozen" : "Card request approved",
                shouldFreezeCard
                        ? "Your card-freeze request was approved. Your RFID card is now frozen."
                        : "Your card request was approved. Please contact Premier Transport support for the next steps.");

        return ApiResponse.success(shouldFreezeCard
                        ? "Card freeze request approved and card frozen."
                        : "Card update request approved.",
                CardFreezeRequestResponse.from(request));
    }

    @Transactional
    public ApiResponse<CardFreezeRequestResponse> reject(Admin admin, Long id, String remarks) {
        if (remarks == null || remarks.trim().isBlank()) {
            throw new RuntimeException("Admin remarks are required when rejecting a request.");
        }

        CardFreezeRequest request = pendingRequest(id);
        request.setStatus(CardFreezeRequestStatus.REJECTED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewedByAdmin(admin);
        request.setAdminRemarks(cleanReason(remarks));
        repository.save(request);

        activityLogRepository.save(ActivityLog.builder()
                .admin(admin)
                .action("REJECT_CARD_FREEZE_REQUEST")
                .targetType("CARD_FREEZE_REQUEST")
                .targetId(request.getId())
                .userId(request.getPassenger().getId())
                .details("Rejected card-freeze request: " + cleanReason(remarks))
                .status("SUCCESS")
                .build());

        firebaseService.sendNotification(request.getPassenger().getFcmToken(),
                "Card-freeze request reviewed",
                "Your card-freeze request was reviewed. Please contact support for details.");

        return ApiResponse.success("Card freeze request rejected.",
                CardFreezeRequestResponse.from(request));
    }

    private CardFreezeRequest pendingRequest(Long id) {
        CardFreezeRequest request = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Card freeze request not found."));
        if (request.getStatus() != CardFreezeRequestStatus.PENDING) {
            throw new RuntimeException("This request has already been processed.");
        }
        return request;
    }

    private String cleanReason(String reason) {
        if (reason == null) return null;
        return reason.replaceAll("<[^>]*>", "")
                .replaceAll("[<>\"'&;]", "")
                .trim();
    }

    private String submittedMessage(CardFreezeRequestType type, Passenger card) {
        String maskedCard = mask(card.getCardNumber());
        if (type == CardFreezeRequestType.CARD_UPDATE) {
            return "Your card update request has been submitted for the card linked to your account (" + maskedCard + "). An administrator will review it before making any card changes.";
        }
        return "Your card security request has been submitted for the card linked to your account (" + maskedCard + "). An administrator will review your request and freeze the card if verified. Until it is approved, please avoid sharing your card details.";
    }

    private String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return "****";
        return cardNumber.substring(0, 4) + "****" + cardNumber.substring(cardNumber.length() - 4);
    }
}
