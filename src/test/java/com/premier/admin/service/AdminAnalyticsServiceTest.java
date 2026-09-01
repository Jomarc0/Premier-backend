package com.premier.admin.service;

import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.model.Passenger;
import com.premier.model.PaymentMethod;
import com.premier.model.Transaction;
import com.premier.model.TransactionStatus;
import com.premier.model.TransactionType;
import com.premier.payment.model.FarePaymentAttempt;
import com.premier.payment.model.FarePaymentAttemptStatus;
import com.premier.payment.model.FarePaymentFailureReason;
import com.premier.payment.repository.FarePaymentAttemptRepository;
import com.premier.repository.TransactionRepository;
import com.premier.staffcash.repository.StaffCashTransactionRepository;
import com.premier.staffqueue.response.BusQueueDashboardResponse;
import com.premier.staffqueue.service.BusQueueService;
import com.premier.support.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {
    @Mock TransactionRepository transactionRepository;
    @Mock FarePaymentAttemptRepository attemptRepository;
    @Mock StaffCashTransactionRepository cashRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock DriverShiftRepository shiftRepository;
    @Mock DriverLocationRepository locationRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock SupportTicketRepository ticketRepository;
    @Mock BusQueueService queueService;

    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnalyticsService(transactionRepository, attemptRepository, cashRepository,
                vehicleRepository, shiftRepository, locationRepository, deviceRepository, ticketRepository, queueService);
        when(transactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(attemptRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(cashRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(vehicleRepository.findAll()).thenReturn(List.of());
        when(shiftRepository.findByShiftStartBetween(any(), any())).thenReturn(List.of());
        when(locationRepository.findLatestPerPlate()).thenReturn(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(ticketRepository.findAll()).thenReturn(List.of());
        when(transactionRepository.countDistinctFareOperatingDays()).thenReturn(0L);
        when(queueService.getDashboard()).thenReturn(new BusQueueDashboardResponse(LocalDateTime.now(), List.of(), List.of()));
    }

    @Test
    void emptyDashboardDoesNotInventTripsOrRoutes() {
        Map<String, Object> dashboard = service.getDashboard("today", null, null,
                null, null, null, null, "Asia/Manila");

        Map<?, ?> summary = (Map<?, ?>) dashboard.get("summary");
        Map<?, ?> tripPerformance = (Map<?, ?>) dashboard.get("tripPerformance");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) dashboard.get("options");

        assertThat(summary.get("totalRevenue")).isEqualTo(BigDecimal.ZERO);
        assertThat(summary.get("totalPassengers")).isEqualTo(0);
        assertThat(summary.get("totalTrips")).isNull();
        assertThat(tripPerformance.get("available")).isEqualTo(false);
        assertThat((List<?>) options.get("directions")).hasSize(2);
        assertThat(options).doesNotContainKey("routes");
    }

    @Test
    void successfulFareIsGroupedByBusAndFixedDirection() {
        Vehicle bus = Vehicle.builder().id(7L).plateNumber("DAR-5315").totalCapacity(50)
                .route("SM Terminal to Grand Terminal").build();
        Passenger passenger = Passenger.builder().id(10L).build();
        Transaction transaction = Transaction.builder().id(20L).passenger(passenger)
                .type(TransactionType.FARE_DEDUCTION).status(TransactionStatus.SUCCESS)
                .amount(new BigDecimal("60.00")).paymentMethod(PaymentMethod.RFID).vehicle(bus)
                .routeSnapshot("SM Terminal to Grand Terminal").referenceNumber("RFID-TEST")
                .createdAt(LocalDateTime.now()).build();
        FarePaymentAttempt attempt = FarePaymentAttempt.builder().id(30L).passenger(passenger)
                .transaction(transaction).paymentMethod(PaymentMethod.RFID)
                .status(FarePaymentAttemptStatus.SUCCESS).failureReason(FarePaymentFailureReason.NONE)
                .amount(new BigDecimal("60.00")).vehicle(bus).routeSnapshot("SM Terminal to Grand Terminal")
                .createdAt(LocalDateTime.now()).build();

        when(transactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(transaction));
        when(attemptRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(attempt));
        when(vehicleRepository.findAll()).thenReturn(List.of(bus));
        when(transactionRepository.countDistinctFareOperatingDays()).thenReturn(1L);

        Map<String, Object> dashboard = service.getDashboard("today", null, null,
                "DAR-5315", AdminAnalyticsService.SM_TO_GRAND, "RFID", "SUCCESSFUL", "Asia/Manila");

        Map<?, ?> summary = (Map<?, ?>) dashboard.get("summary");
        Map<?, ?> directionAnalytics = (Map<?, ?>) dashboard.get("directionAnalytics");
        List<?> directions = (List<?>) directionAnalytics.get("directions");
        Map<?, ?> smToGrand = (Map<?, ?>) directions.get(0);

        assertThat(summary.get("totalRevenue")).isEqualTo(new BigDecimal("60.00"));
        assertThat(summary.get("totalPassengers")).isEqualTo(1);
        assertThat(summary.get("uniquePassengers")).isEqualTo(1L);
        assertThat(smToGrand.get("direction")).isEqualTo(AdminAnalyticsService.SM_TO_GRAND);
        assertThat(smToGrand.get("passengers")).isEqualTo(1L);
    }
}
