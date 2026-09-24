package com.premier.response;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;
import com.premier.model.PaymentMethod;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TransactionResponse {
    private Long id;
    private Long passengerId;
    private TransactionType type;
    private TransactionStatus status;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String referenceNumber;
    private PaymentMethod paymentMethod;
    private Long vehicleId;
    private String plateNumber;
    private Long tripId;
    private String direction;
    private String originTerminal;
    private String destinationTerminal;
    private Long driverShiftId;
    private String routeSnapshot;
    private String deviceId;
    private String description;
    private LocalDateTime createdAt;

    public static TransactionResponse from(com.premier.model.Transaction transaction) {
        boolean fare = transaction.getType() == TransactionType.FARE_DEDUCTION
                || transaction.getType() == TransactionType.RIDE_FARE;
        String plate = null;
        Long vehicleId = null;
        if (fare) {
            vehicleId = transaction.getVehicle() == null ? null : transaction.getVehicle().getId();
            plate = clean(transaction.getVehiclePlateNumber());
            if (plate == null && transaction.getVehicle() != null) {
                plate = clean(transaction.getVehicle().getPlateNumber());
            }
        }

        return TransactionResponse.builder()
                .id(transaction.getId())
                .passengerId(transaction.getPassenger() == null ? null : transaction.getPassenger().getId())
                .type(transaction.getType())
                .status(transaction.getStatus())
                .amount(transaction.getAmount())
                .balanceBefore(transaction.getBalanceBefore())
                .balanceAfter(transaction.getBalanceAfter())
                .referenceNumber(transaction.getReferenceNumber())
                .paymentMethod(transaction.getPaymentMethod())
                .vehicleId(vehicleId)
                .plateNumber(plate)
                .tripId(transaction.getTrip() == null ? null : transaction.getTrip().getId())
                .direction(transaction.getTripDirection() == null ? null : transaction.getTripDirection().name())
                .originTerminal(transaction.getOriginTerminal())
                .destinationTerminal(transaction.getDestinationTerminal())
                .driverShiftId(transaction.getDriverShift() == null ? null : transaction.getDriverShift().getId())
                .routeSnapshot(transaction.getRouteSnapshot())
                .deviceId(transaction.getDeviceId())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .build();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
