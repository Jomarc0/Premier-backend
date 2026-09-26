package com.premier.admin.service;

import com.premier.device.repository.DeviceRepository;
import com.premier.driver.model.Vehicle;
import com.premier.driver.repository.DriverLocationRepository;
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
import com.premier.exception.ClientException;
import com.premier.repository.TransactionRepository;
import com.premier.staffcash.repository.StaffCashTransactionRepository;
import com.premier.staffqueue.response.BusQueueDashboardResponse;
import com.premier.staffqueue.service.BusQueueService;
import com.premier.support.repository.SupportTicketRepository;
import com.premier.trip.model.TripDirection;
import com.premier.trip.model.TripStatus;
import com.premier.trip.model.VehicleTrip;
import com.premier.trip.repository.VehicleTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {
    @Mock TransactionRepository transactionRepository;
    @Mock FarePaymentAttemptRepository attemptRepository;
    @Mock StaffCashTransactionRepository cashRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock DriverLocationRepository locationRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock SupportTicketRepository ticketRepository;
    @Mock BusQueueService queueService;
    @Mock VehicleTripRepository tripRepository;

    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnalyticsService(transactionRepository, attemptRepository, cashRepository,
                vehicleRepository, locationRepository, deviceRepository, ticketRepository, queueService,
                tripRepository);
    }

    private void stubEmptyDashboardDependencies() {
        when(transactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(attemptRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(cashRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(vehicleRepository.findAll()).thenReturn(List.of());
        when(locationRepository.findLatestPerPlate()).thenReturn(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(ticketRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(transactionRepository.countDistinctFareOperatingDays()).thenReturn(0L);
        when(queueService.getDashboard()).thenReturn(new BusQueueDashboardResponse(LocalDateTime.now(), List.of(), List.of()));
        when(tripRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of());
    }

    @Test
    void emptyDashboardDoesNotInventTripsOrRoutes() {
        stubEmptyDashboardDependencies();
        Map<String, Object> dashboard = service.getDashboard("today", null, null,
                null, null, null, null, "Asia/Manila");

        Map<?, ?> summary = (Map<?, ?>) dashboard.get("summary");
        Map<?, ?> tripPerformance = (Map<?, ?>) dashboard.get("tripPerformance");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) dashboard.get("options");

        assertThat(summary.get("totalRevenue")).isEqualTo(BigDecimal.ZERO);
        assertThat(summary.get("totalPassengers")).isEqualTo(0);
        assertThat(summary.get("totalTrips")).isEqualTo(0);
        assertThat(tripPerformance.get("available")).isEqualTo(true);
        assertThat((List<?>) options.get("directions")).hasSize(2);
        assertThat(options).doesNotContainKey("routes");
        verify(ticketRepository).findTop10ByOrderByCreatedAtDesc();
        verify(ticketRepository, never()).findAll();
    }

    @Test
    void successfulFareIsGroupedByBusAndFixedDirection() {
        stubEmptyDashboardDependencies();
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

    @Test
    void customDashboardRangeIsCappedAndStructured() {
        assertThatThrownBy(() -> service.getDashboard("custom", LocalDate.now().minusDays(120), LocalDate.now(),
                null, null, null, null, "Asia/Manila"))
                .isInstanceOf(ClientException.class)
                .satisfies(error -> assertThat(((ClientException) error).getCode()).isEqualTo("ANALYTICS_RANGE_INVALID"));
    }

    @Test
    void tenPassengersAcrossTwoCompletedTripsProduceSixAndFourDirectionalCounts() {
        stubEmptyDashboardDependencies();
        Vehicle bus = Vehicle.builder().id(7L).plateNumber("DAR-5315").totalCapacity(50).build();
        LocalDateTime now = LocalDateTime.now();
        VehicleTrip outbound = VehicleTrip.builder().id(101L).vehicle(bus).vehiclePlateNumber("DAR-5315")
                .direction(TripDirection.SM_TO_GRAND).originTerminal("SM Terminal").destinationTerminal("Grand Terminal")
                .startedAt(now.minusHours(2)).endedAt(now.minusHours(1)).status(TripStatus.COMPLETED).build();
        VehicleTrip inbound = VehicleTrip.builder().id(102L).vehicle(bus).vehiclePlateNumber("DAR-5315")
                .direction(TripDirection.GRAND_TO_SM).originTerminal("Grand Terminal").destinationTerminal("SM Terminal")
                .startedAt(now.minusMinutes(50)).endedAt(now.minusMinutes(5)).status(TripStatus.COMPLETED).build();
        List<Transaction> fares = IntStream.range(0, 10).mapToObj(index -> {
            VehicleTrip trip = index < 6 ? outbound : inbound;
            return Transaction.builder().id(200L + index).passenger(Passenger.builder().id(300L + index).build())
                    .type(TransactionType.FARE_DEDUCTION).status(TransactionStatus.SUCCESS)
                    .amount(new BigDecimal("60.00")).paymentMethod(PaymentMethod.RFID).vehicle(bus)
                    .vehiclePlateNumber("DAR-5315").trip(trip).tripDirection(trip.getDirection())
                    .originTerminal(trip.getOriginTerminal()).destinationTerminal(trip.getDestinationTerminal())
                    .referenceNumber("FARE-" + index).createdAt(now.minusMinutes(100L - index)).build();
        }).toList();
        when(transactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(fares);
        when(vehicleRepository.findAll()).thenReturn(List.of(bus));
        when(tripRepository.findByStartedAtBetween(any(), any())).thenReturn(List.of(outbound, inbound));

        Map<String, Object> dashboard = service.getDashboard("today", null, null,
                null, null, null, null, "Asia/Manila");

        Map<?, ?> summary = (Map<?, ?>) dashboard.get("summary");
        Map<?, ?> directionAnalytics = (Map<?, ?>) dashboard.get("directionAnalytics");
        List<?> directions = (List<?>) directionAnalytics.get("directions");
        Map<?, ?> smToGrand = (Map<?, ?>) directions.get(0);
        Map<?, ?> grandToSm = (Map<?, ?>) directions.get(1);
        Map<?, ?> tripPerformance = (Map<?, ?>) dashboard.get("tripPerformance");
        List<?> daily = (List<?>) dashboard.get("dailyBusPerformance");
        Map<?, ?> dailyBus = (Map<?, ?>) daily.get(0);
        assertThat(summary.get("totalPassengers")).isEqualTo(10);
        assertThat((BigDecimal) summary.get("totalRevenue")).isEqualByComparingTo("600.00");
        assertThat(summary.get("totalTrips")).isEqualTo(2);
        assertThat(smToGrand.get("passengers")).isEqualTo(6L);
        assertThat(grandToSm.get("passengers")).isEqualTo(4L);
        assertThat(((Map<?, ?>) summary.get("peakDirection")).get("direction")).isEqualTo(AdminAnalyticsService.SM_TO_GRAND);
        assertThat(tripPerformance.get("averagePassengersPerTrip")).isEqualTo(new BigDecimal("5.00"));
        assertThat(tripPerformance.get("averageRevenuePerTrip")).isEqualTo(new BigDecimal("300.00"));
        assertThat(dailyBus.get("smToGrandPassengers")).isEqualTo(6L);
        assertThat(dailyBus.get("grandToSmPassengers")).isEqualTo(4L);
        assertThat(dailyBus.get("totalPassengers")).isEqualTo(10);
        assertThat(dailyBus.get("trips")).isEqualTo(2L);
    }

    @Test
    void failedAttemptDirectionComesFromItsTripSnapshotNotTheMutableRouteText() {
        stubEmptyDashboardDependencies();
        Vehicle bus = Vehicle.builder().id(7L).plateNumber("DAR-5315").totalCapacity(50)
                .route("SM Terminal to Grand Terminal").build();
        FarePaymentAttempt attempt = FarePaymentAttempt.builder().id(250L)
                .paymentMethod(PaymentMethod.QR).status(FarePaymentAttemptStatus.FAILED)
                .failureReason(FarePaymentFailureReason.INVALID_TOKEN).vehicle(bus)
                .vehiclePlateNumber("DAR-5315").tripDirection(TripDirection.GRAND_TO_SM)
                .routeSnapshot("SM Terminal to Grand Terminal").deviceId("bus-001")
                .createdAt(LocalDateTime.now()).build();
        when(attemptRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of(attempt));

        Map<String, Object> dashboard = service.getDashboard("today", null, null,
                null, null, null, null, "Asia/Manila");
        Map<?, ?> transactionAnalytics = (Map<?, ?>) dashboard.get("transactionAnalytics");
        Map<?, ?> failure = (Map<?, ?>) ((List<?>) transactionAnalytics.get("failedTransactions")).get(0);

        assertThat(failure.get("direction")).isEqualTo(AdminAnalyticsService.GRAND_TO_SM);
        assertThat(failure.get("directionLabel")).isEqualTo(AdminAnalyticsService.GRAND_TO_SM_LABEL);
        assertThat(failure.get("terminal")).isEqualTo("bus-001");
    }
}
