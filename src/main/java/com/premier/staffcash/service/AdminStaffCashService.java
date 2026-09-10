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
    private final StaffCashRemittanceAdjustmentRepository adjustments;
    private final com.premier.admin.repository.ActivityLogRepository activityLogs;

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
        if (date == null || date.isAfter(LocalDate.now()) || actual == null || actual.signum() < 0
                || actual.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST", "A collection date and valid cash amount are required.");
        actual = com.premier.payment.service.Money.exact(actual);
        // Serialize confirmations even when no remittance row exists yet; never overwrite accepted history.
        adminRepository.findLockedById(staffId).orElseThrow();
        if (remittanceRepository.findByStaffIdAndCollectionDate(staffId, date).isPresent())
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT,
                    "REMITTANCE_ALREADY_CONFIRMED", "Remittance already confirmed. Use an audited adjustment for corrections.");
        Admin staff = requireStaff(staffId);
        List<StaffCashTransaction> rows = transactionsFor(staffId, date);
        if (rows.isEmpty()) throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "No staff cash transactions exist for this date.");
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
        audit(admin, remittance.getId(), "CONFIRM_REMITTANCE", "Confirmed staff cash collection for " + date);
        return detail(staffId, date);
    }

    @Transactional
    public ApiResponse<AdminStaffCashDetail> adjust(Admin principal, Long staffId,
            com.premier.staffcash.request.AdjustStaffRemittanceRequest request) {
        Admin admin = principal == null ? null : adminRepository.findById(principal.getId()).orElse(null);
        if (admin == null || admin.getRole() != AdminRole.SUPER_ADMIN || !Boolean.TRUE.equals(admin.getActive())
                || admin.isLocked() || !Boolean.TRUE.equals(admin.getIs2FaEnabled()) || admin.getSessionVersion() != principal.getSessionVersion())
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN,"FORBIDDEN","Current Super Admin authorization is required.");
        if (request == null || request.date() == null || request.amount() == null || request.amount().signum() == 0
                || request.amount().abs().compareTo(new BigDecimal("99999999.99")) > 0 || request.reason() == null
                || request.reason().trim().length() < 10 || request.reason().trim().length() > 240
                || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9-]{12,80}"))
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Provide an exact signed adjustment, reason and unique request ID.");
        BigDecimal amount = com.premier.payment.service.Money.exact(request.amount());
        adminRepository.findLockedById(staffId).orElseThrow();
        StaffCashRemittance remittance = remittanceRepository.findByStaffIdAndCollectionDate(staffId,request.date())
                .orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT,"NOT_CONFIRMED","Confirm the original remittance first."));
        var existing = adjustments.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            var row=existing.get();
            if (!row.getRemittanceId().equals(remittance.getId()) || row.getAmount().compareTo(amount)!=0
                    || !row.getReason().equals(request.reason().trim()) || !row.getAuthorizedBy().equals(admin.getId()))
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This request ID belongs to a different correction.");
            return detail(staffId,request.date());
        }
        BigDecimal effective = remittance.getActualCashReceived().add(adjustments.total(remittance.getId())).add(amount);
        if(effective.signum()<0 || effective.compareTo(new BigDecimal("99999999.99"))>0)
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT,"INVALID_TOTAL","Correction would create an invalid cash total.");
        adjustments.saveAndFlush(StaffCashRemittanceAdjustment.builder().remittanceId(remittance.getId()).authorizedBy(admin.getId())
                .amount(amount).reason(request.reason().trim()).requestId(request.requestId()).build());
        audit(admin,remittance.getId(),"ADJUST_REMITTANCE",request.reason().trim());
        return detail(staffId,request.date());
    }

    @Transactional(readOnly=true)
    public ApiResponse<List<StaffCashRemittanceAdjustment>> adjustmentHistory(Long staffId, LocalDate date) {
        var remittance=remittanceRepository.findByStaffIdAndCollectionDate(staffId,date).orElseThrow();
        return ApiResponse.success("Remittance correction history fetched.",adjustments.findByRemittanceIdOrderByIdAsc(remittance.getId()));
    }

    private void audit(Admin admin,Long id,String action,String reason) {
        activityLogs.save(com.premier.admin.model.ActivityLog.builder().admin(admin).action(action).targetType("STAFF_CASH_REMITTANCE")
                .targetId(id).details(reason).build());
    }

    private AdminStaffCashSummary summary(Admin staff, LocalDate date, List<StaffCashTransaction> rows) {
        long regular = rows.stream().filter(tx -> tx.getFareCategory() == StaffCashCardPurpose.REGULAR_CASH).count();
        Optional<StaffCashRemittance> remittance = remittanceRepository.findByStaffIdAndCollectionDate(staff.getId(), date);
        BigDecimal adjustment = remittance.map(r -> adjustments.total(r.getId())).orElse(BigDecimal.ZERO);
        BigDecimal actual = remittance.map(r -> r.getActualCashReceived().add(adjustment)).orElse(null);
        BigDecimal currentExpected = total(rows);
        BigDecimal difference = actual == null ? null : actual.subtract(currentExpected);
        boolean lateCollections = remittance.map(r -> r.getExpectedCash().compareTo(currentExpected) != 0).orElse(false);
        return AdminStaffCashSummary.builder().staffId(staff.getId()).staffName(staff.getFullName()).date(date)
                .regularCount(regular).discountedCount(rows.size() - regular).totalTransactions(rows.size())
                .expectedCash(currentExpected).actualCashReceived(actual).difference(difference)
                .confirmedExpectedCash(remittance.map(StaffCashRemittance::getExpectedCash).orElse(null))
                .adjustmentsTotal(adjustment).requiresReconciliation(lateCollections)
                .remittanceState(remittance.isPresent() ? "CONFIRMED" : "PENDING")
                .result(difference == null ? null : difference.signum()==0 ? StaffRemittanceStatus.BALANCED : difference.signum()<0 ? StaffRemittanceStatus.SHORT : StaffRemittanceStatus.OVER)
                .confirmedAt(remittance.map(StaffCashRemittance::getConfirmedAt).orElse(null)).build();
    }

    private AdminStaffCashTransaction transaction(StaffCashTransaction tx) {
        return AdminStaffCashTransaction.builder().id(tx.getId()).staffId(tx.getStaff().getId())
                .staffName(tx.getStaff().getFullName()).referenceNumber(tx.getReferenceNumber())
                .plateNumber(tx.getVehicle().getPlateNumber()).deviceId(tx.getDeviceId())
                .driverShiftId(tx.getDriverShift().getId()).route(tx.getRouteSnapshot()).terminal(tx.getTerminalSnapshot())
                .fareCategory(tx.getFareCategory()).baseFare(tx.getBaseFare()).discountAmount(tx.getDiscountAmount())
                .finalFare(tx.getFinalFare()).createdAt(tx.getCreatedAt()).offlineCapturedAt(tx.getOfflineCapturedAt()).build();
    }

    private List<StaffCashTransaction> transactionsFor(LocalDate date) {
        return transactionRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }
    private List<StaffCashTransaction> transactionsFor(Long staffId, LocalDate date) {
        return transactionRepository.findByStaffIdAndCreatedAtBetweenOrderByCreatedAtDesc(staffId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }
    private BigDecimal total(List<StaffCashTransaction> rows) { return rows.stream().map(StaffCashTransaction::getFinalFare).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private Admin requireStaff(Long id) {
        Admin staff = adminRepository.findById(id).orElseThrow(() -> new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Staff account not found."));
        if (staff.getRole() != AdminRole.STAFF) throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Selected account is not staff.");
        return staff;
    }
}


