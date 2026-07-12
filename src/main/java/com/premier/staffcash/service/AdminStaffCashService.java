package com.premier.staffcash.service;

import com.premier.admin.model.Admin;
import com.premier.admin.model.AdminRole;
import com.premier.admin.repository.AdminRepository;
import com.premier.response.ApiResponse;
import com.premier.staffcash.model.*;
import com.premier.staffcash.repository.*;
import com.premier.staffcash.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminStaffCashService {
    private final StaffCashTransactionRepository transactionRepository;
    private final StaffCashRemittanceRepository remittanceRepository;
    private final AdminRepository adminRepository;

    @Transactional(readOnly = true)
    public ApiResponse<List<AdminStaffCashSummary>> summaries(LocalDate date) {
        LocalDate selected = date != null ? date : LocalDate.now();
        Map<Long, List<StaffCashTransaction>> grouped = new LinkedHashMap<>();
        transactionsFor(selected).forEach(tx -> grouped.computeIfAbsent(tx.getStaff().getId(), key -> new ArrayList<>()).add(tx));
        List<AdminStaffCashSummary> result = grouped.values().stream()
                .map(rows -> summary(rows.get(0).getStaff(), selected, rows)).toList();
        return ApiResponse.success("Staff collections fetched.", result);
    }

    @Transactional(readOnly = true)
    public ApiResponse<AdminStaffCashDetail> detail(Long staffId, LocalDate date) {
        LocalDate selected = date != null ? date : LocalDate.now();
        Admin staff = requireStaff(staffId);
        List<StaffCashTransaction> rows = transactionsFor(staffId, selected);
        return ApiResponse.success("Staff collection details fetched.", AdminStaffCashDetail.builder()
                .summary(summary(staff, selected, rows))
                .transactions(rows.stream().map(this::transaction).toList()).build());
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<AdminStaffCashTransaction>> transactions(LocalDate date) {
        LocalDate selected = date != null ? date : LocalDate.now();
        return ApiResponse.success("Staff cash transactions fetched.",
                transactionsFor(selected).stream().map(this::transaction).toList());
    }

    @Transactional
    public ApiResponse<AdminStaffCashDetail> confirm(Admin admin, Long staffId, LocalDate date, BigDecimal actual) {
        Admin staff = requireStaff(staffId);
        List<StaffCashTransaction> rows = transactionsFor(staffId, date);
        if (rows.isEmpty()) throw new RuntimeException("No staff cash transactions exist for this date.");
        BigDecimal expected = total(rows);
        BigDecimal difference = actual.subtract(expected);
        StaffRemittanceStatus status = difference.signum() == 0 ? StaffRemittanceStatus.BALANCED
                : difference.signum() < 0 ? StaffRemittanceStatus.SHORT : StaffRemittanceStatus.OVER;
        StaffCashRemittance remittance = remittanceRepository.findByStaffIdAndCollectionDate(staffId, date)
                .orElseGet(StaffCashRemittance::new);
        remittance.setStaff(staff); remittance.setCollectionDate(date); remittance.setExpectedCash(expected);
        remittance.setActualCashReceived(actual); remittance.setDifference(difference); remittance.setStatus(status);
        remittance.setConfirmedBy(admin); remittance.setConfirmedAt(LocalDateTime.now());
        remittanceRepository.save(remittance);
        return detail(staffId, date);
    }

    private AdminStaffCashSummary summary(Admin staff, LocalDate date, List<StaffCashTransaction> rows) {
        long regular = rows.stream().filter(tx -> tx.getFareCategory() == StaffCashCardPurpose.REGULAR_CASH).count();
        Optional<StaffCashRemittance> remittance = remittanceRepository.findByStaffIdAndCollectionDate(staff.getId(), date);
        return AdminStaffCashSummary.builder().staffId(staff.getId()).staffName(staff.getFullName()).date(date)
                .regularCount(regular).discountedCount(rows.size() - regular).totalTransactions(rows.size())
                .expectedCash(total(rows)).actualCashReceived(remittance.map(StaffCashRemittance::getActualCashReceived).orElse(null))
                .difference(remittance.map(StaffCashRemittance::getDifference).orElse(null))
                .remittanceState(remittance.isPresent() ? "CONFIRMED" : "PENDING")
                .result(remittance.map(StaffCashRemittance::getStatus).orElse(null))
                .confirmedAt(remittance.map(StaffCashRemittance::getConfirmedAt).orElse(null)).build();
    }

    private AdminStaffCashTransaction transaction(StaffCashTransaction tx) {
        return AdminStaffCashTransaction.builder().id(tx.getId()).staffId(tx.getStaff().getId())
                .staffName(tx.getStaff().getFullName()).referenceNumber(tx.getReferenceNumber())
                .plateNumber(tx.getVehicle().getPlateNumber()).deviceId(tx.getDeviceId())
                .driverShiftId(tx.getDriverShift().getId()).route(tx.getRouteSnapshot()).terminal(tx.getTerminalSnapshot())
                .fareCategory(tx.getFareCategory()).baseFare(tx.getBaseFare()).discountAmount(tx.getDiscountAmount())
                .finalFare(tx.getFinalFare()).createdAt(tx.getCreatedAt()).build();
    }

    private List<StaffCashTransaction> transactionsFor(LocalDate date) {
        return transactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }
    private List<StaffCashTransaction> transactionsFor(Long staffId, LocalDate date) {
        return transactionRepository.findByStaffIdAndCreatedAtBetweenOrderByCreatedAtDesc(staffId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }
    private BigDecimal total(List<StaffCashTransaction> rows) { return rows.stream().map(StaffCashTransaction::getFinalFare).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private Admin requireStaff(Long id) {
        Admin staff = adminRepository.findById(id).orElseThrow(() -> new RuntimeException("Staff account not found."));
        if (staff.getRole() != AdminRole.STAFF) throw new RuntimeException("Selected account is not staff.");
        return staff;
    }
}
