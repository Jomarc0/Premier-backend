package com.premier.service;
import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.admin.service.AdminService;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
import com.premier.repository.*;
import com.premier.staffcash.model.*;
import com.premier.staffcash.repository.*;
import com.premier.staffcash.request.AdjustStaffRemittanceRequest;
import com.premier.staffcash.service.AdminStaffCashService;
import com.premier.exception.ClientException;
import com.premier.payment.service.CaptureTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class FinancialHistoryRegressionTest {
    @Autowired AdminRepository admins;
    @Autowired AdminService adminService;
    @Autowired PassengerRepository passengers;
    @Autowired TransactionRepository ledger;
    @Autowired RfidUidRegistrationService ownership;
    @Autowired RfidUidRegistrationRepository registrations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AdminStaffCashService cash;
    @Autowired StaffCashCardRepository cards;
    @Autowired StaffCashTransactionRepository cashTransactions;
    @Autowired StaffCashRemittanceRepository remittances;
    @Autowired StaffCashRemittanceAdjustmentRepository adjustments;
    @Autowired VehicleRepository vehicles;
    @Autowired DriverRepository drivers;
    @Autowired DriverShiftRepository shifts;
    private String uid() {return UUID.randomUUID().toString().replace("-","").substring(0,14).toUpperCase();}
    private Admin admin(AdminRole role) {
        String id=UUID.randomUUID().toString();
        return admins.saveAndFlush(Admin.builder().adminId(id.substring(0,12)).username(id).fullName("Synthetic history test")
                .password("not-used-for-login").role(role).is2FaEnabled(true).build());
    }
    @Test void issuanceCreatesBalancedOpeningLedger() {
        var response=adminService.createPassenger(admin(AdminRole.SUPER_ADMIN),uid(),"REGULAR").getData();
        var p=passengers.findById(response.getPassengerId()).orElseThrow();
        var rows=ledger.findByPassengerIdOrderByCreatedAtDesc(p.getId(),org.springframework.data.domain.PageRequest.of(0,10));
        assertThat(rows.getTotalElements()).isEqualTo(1);
        assertThat(rows.getContent().get(0).getBalanceBefore()).isEqualByComparingTo("0.00");
        assertThat(rows.getContent().get(0).getAmount()).isEqualByComparingTo(p.getBalance());
        assertThat(rows.getContent().get(0).getBalanceAfter()).isEqualByComparingTo(p.getBalance());
    }
    @Test void simultaneousCrossNamespaceUidClaimsHaveOneWinner() throws Exception {
        String uid=uid(); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String kind:List.of("PASSENGER","CASH")) results.add(pool.submit(() -> {
                start.await();
                try {new TransactionTemplate(transactions).executeWithoutResult(s -> ownership.claim(uid,kind,123L));return true;}
                catch(RuntimeException conflict){return false;}
            }));
            start.countDown(); int won=0; for(var result:results) if(result.get(15,TimeUnit.SECONDS))won++;
            assertThat(won).isEqualTo(1); assertThat(registrations.findById(uid)).isPresent();
        } finally {pool.shutdownNow();}
    }
    private StaffCashTransaction collection(Admin staff,Admin verifier,LocalDateTime captured) {
        var vehicle=vehicles.saveAndFlush(Vehicle.builder().plateNumber("H-"+uid()).totalCapacity(20).build());
        var driver=drivers.saveAndFlush(Driver.builder().fullName("Synthetic driver").licenseNumber(uid()).phoneNumber("00000000000").build());
        var shift=shifts.saveAndFlush(DriverShift.builder().driver(driver).vehicle(vehicle).build());
        var card=cards.saveAndFlush(StaffCashCard.builder().rfidUid(uid()).staff(staff).purpose(StaffCashCardPurpose.REGULAR_CASH).registeredBy(verifier).build());
        return cashTransactions.saveAndFlush(StaffCashTransaction.builder().staff(staff).operationCard(card).vehicle(vehicle).driverShift(shift)
                .deviceId("synthetic").fareCategory(StaffCashCardPurpose.REGULAR_CASH).baseFare(new BigDecimal("60.00"))
                .discountAmount(BigDecimal.ZERO).finalFare(new BigDecimal("60.00"))
                .referenceNumber(UUID.randomUUID().toString()).idempotencyKey(UUID.randomUUID().toString()).offlineCapturedAt(captured).build());
    }
    @Test void delayedCashUsesCaptureDayAndCorrectionsPreserveOriginalConfirmation() {
        var verifier=admin(AdminRole.SUPER_ADMIN); var staff=admin(AdminRole.STAFF); var day=LocalDate.now().minusDays(1);
        collection(staff,verifier,day.atTime(12,0));
        assertThat(cash.detail(staff.getId(),day).getData().summary().expectedCash()).isEqualByComparingTo("60.00");
        assertThat(cash.detail(staff.getId(),LocalDate.now()).getData().summary().expectedCash()).isEqualByComparingTo("0.00");
        cash.confirm(verifier,staff.getId(),day,new BigDecimal("50.00"));
        assertThatThrownBy(() -> cash.confirm(verifier,staff.getId(),day,new BigDecimal("60.00"))).isInstanceOf(ClientException.class);
        var correction=new AdjustStaffRemittanceRequest(day,new BigDecimal("10.00"),"Receipt verified against support evidence",UUID.randomUUID().toString());
        cash.adjust(verifier,staff.getId(),correction); cash.adjust(verifier,staff.getId(),correction);
        var original=remittances.findByStaffIdAndCollectionDate(staff.getId(),day).orElseThrow();
        assertThat(original.getActualCashReceived()).isEqualByComparingTo("50.00");
        assertThat(adjustments.findByRemittanceIdOrderByIdAsc(original.getId())).hasSize(1);
        assertThat(cash.detail(staff.getId(),day).getData().summary().difference()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> cash.adjust(staff,staff.getId(),correction)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cash.adjust(verifier,staff.getId(),new AdjustStaffRemittanceRequest(day,new BigDecimal("20.00"),correction.reason(),correction.requestId())))
                .isInstanceOf(ClientException.class);
    }
    @Test void untrustedCaptureTimesRequireReconciliation() {
        for(String value:List.of("broken",Instant.now().plusSeconds(120).toString(),Instant.now().minus(Duration.ofDays(8)).toString()))
            assertThatThrownBy(() -> CaptureTime.optional(value)).isInstanceOf(ClientException.class);
        assertThat(CaptureTime.optional(Instant.now().minusSeconds(30).toString())).isNotNull();
    }
}
