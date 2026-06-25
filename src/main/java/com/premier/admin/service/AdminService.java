package com.premier.admin.service;

import com.premier.admin.model.*;
import com.premier.admin.repository.*;
import com.premier.admin.security.AdminJwtUtil;
import com.premier.model.*;
import com.premier.repository.*;
import com.premier.response.ApiResponse;
import com.premier.response.TotpSetupResponse;
import com.premier.service.TotpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class AdminService {

    private final AdminRepository        adminRepository;
    private final AdminJwtUtil           adminJwtUtil;
    private final PasswordEncoder        passwordEncoder;
    private final PassengerRepository    passengerRepository;
    private final TransactionRepository  transactionRepository;
    private final ActivityLogRepository  activityLogRepository;
    private final TotpService            totpService;

    public AdminService(
            AdminRepository adminRepository,
            AdminJwtUtil adminJwtUtil,
            PasswordEncoder passwordEncoder,
            PassengerRepository passengerRepository,
            TransactionRepository transactionRepository,
            ActivityLogRepository activityLogRepository,
            TotpService totpService) {
        this.adminRepository       = adminRepository;
        this.adminJwtUtil          = adminJwtUtil;
        this.passwordEncoder       = passwordEncoder;
        this.passengerRepository   = passengerRepository;
        this.transactionRepository = transactionRepository;
        this.activityLogRepository = activityLogRepository;
        this.totpService           = totpService;
    }

    // AUTH
    public ApiResponse<Map<String, Object>> login(
            String username,
            String password,
            String totpCode,
            String ipAddress) {

        Admin admin = adminRepository
            .findByUsername(username)
            .orElseThrow(() ->
                new RuntimeException("Invalid credentials."));

        if (admin.isLocked())
            throw new RuntimeException(
                "Account is locked. Try again later.");

        if (!admin.getActive())
            throw new RuntimeException(
                "Account is disabled.");

        if (!passwordEncoder.matches(
                password, admin.getPassword())) {
            admin.setLoginAttempts(
                admin.getLoginAttempts() + 1);
            if (admin.getLoginAttempts() >= 3) {
                admin.setLockedUntil(
                    LocalDateTime.now().plusMinutes(30));
                log.warn("Admin {} locked after {} attempts",
                    username, admin.getLoginAttempts());
            }
            adminRepository.save(admin);
            throw new RuntimeException("Invalid credentials.");
        }

        if (!AdminRole.STAFF.equals(admin.getRole()) &&
            Boolean.TRUE.equals(admin.getIs2FaEnabled())) {
            if (totpCode == null || totpCode.isBlank()) {
                Map<String, Object> challenge = new HashMap<>();
                challenge.put("requiresTotp", true);
                challenge.put("username", admin.getUsername());
                challenge.put("fullName", admin.getFullName());
                challenge.put("role", admin.getRole().name());
                return ApiResponse.success(
                    "Google Authenticator code required.",
                    challenge);
            }
            if (admin.getTwofaSecret() == null ||
                !totpService.verifyCode(
                    admin.getTwofaSecret(), totpCode.trim())) {
                throw new RuntimeException(
                    "Invalid Google Authenticator code.");
            }
        }

        admin.setLoginAttempts(0);
        admin.setLockedUntil(null);
        admin.setLastLogin(LocalDateTime.now());
        adminRepository.save(admin);

        String token = adminJwtUtil.generateAdminToken(
            admin.getId(), admin.getRole().name());

        Map<String, Object> data = new HashMap<>();
        data.put("token",    token);
        data.put("fullName", admin.getFullName());
        data.put("username", admin.getUsername());
        data.put("role",     admin.getRole().name());
        data.put("requiresTotp", false);

        logActivity(admin, "LOGIN", "ADMIN",
            admin.getId(),
            "Admin logged in successfully",
            ipAddress);

        return ApiResponse.success("Login successful.", data);
    }

    public ApiResponse<TotpSetupResponse> getAdminTotpSetup(Admin admin) {
        if (admin.getTwofaSecret() == null ||
            admin.getTwofaSecret().isBlank()) {
            admin.setTwofaSecret(totpService.generateSecret());
            adminRepository.save(admin);
        }

        String qrCodeUrl = totpService.generateQrCodeUrl(
            admin.getTwofaSecret(), admin.getUsername());

        return ApiResponse.success(
            "Scan QR code with Google Authenticator.",
            TotpSetupResponse.builder()
                .secret(admin.getTwofaSecret())
                .manualEntryKey(admin.getTwofaSecret())
                .qrCodeUrl(qrCodeUrl)
                .is2FaEnabled(Boolean.TRUE.equals(
                    admin.getIs2FaEnabled()))
                .build());
    }

    @Transactional
    public ApiResponse<Map<String, Object>> verifyAdminTotp(
            Admin admin,
            String code) {
        if (admin.getTwofaSecret() == null ||
            admin.getTwofaSecret().isBlank()) {
            admin.setTwofaSecret(totpService.generateSecret());
        }

        if (code == null || code.isBlank() ||
            !totpService.verifyCode(
                admin.getTwofaSecret(), code.trim())) {
            throw new RuntimeException(
                "Invalid Google Authenticator code.");
        }

        admin.setIs2FaEnabled(true);
        adminRepository.save(admin);

        logActivity(admin, "ENABLE_ADMIN_2FA",
            "ADMIN", admin.getId(),
            "Enabled Google Authenticator security",
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("twoFactorEnabled", true);
        result.put("username", admin.getUsername());

        return ApiResponse.success(
            "Google Authenticator enabled.", result);
    }

    //  DASHBOARD 
    public ApiResponse<Map<String, Object>> getDashboardStats() {
        long totalUsers        = passengerRepository.count();
        long totalTransactions = transactionRepository.count();

        List<Transaction> all = transactionRepository.findAll();

        long pending = all.stream()
            .filter(t -> t.getStatus() ==
                TransactionStatus.PENDING)
            .count();

        long completed = all.stream()
            .filter(t -> t.getStatus() ==
                TransactionStatus.SUCCESS)
            .count();

        BigDecimal revenue = all.stream()
            .filter(t -> t.getStatus() ==
                TransactionStatus.SUCCESS
                && t.getType() == TransactionType.TOPUP)
            .map(Transaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBalance = passengerRepository
            .findAll().stream()
            .map(Passenger::getBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers",           totalUsers);
        stats.put("totalTransactions",    totalTransactions);
        stats.put("pendingTransactions",  pending);
        stats.put("completedTransactions",completed);
        stats.put("totalRevenue",         revenue);
        stats.put("totalBalance",         totalBalance);

        return ApiResponse.success("Stats fetched.", stats);
    }

    //  TRANSACTIONS 
    public ApiResponse<Page<Transaction>> getAllTransactions(
            int page, int size) {
        return ApiResponse.success(
            "Transactions fetched.",
            transactionRepository.findAll(
                PageRequest.of(page, size,
                    Sort.by(Sort.Direction.DESC,
                        "createdAt"))));
    }

    @Transactional
    public ApiResponse<Map<String, Object>> approveTransaction(
            Admin admin, Long transactionId) {

        Transaction tx = transactionRepository
            .findById(transactionId)
            .orElseThrow(() ->
                new RuntimeException(
                    "Transaction not found."));

        if (tx.getStatus() != TransactionStatus.PENDING)
            throw new RuntimeException(
                "Transaction is not pending.");

        Passenger passenger = tx.getPassenger();
        BigDecimal before = passenger.getBalance();
        BigDecimal after  = before.add(tx.getAmount());

        passenger.setBalance(after);
        passengerRepository.save(passenger);

        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setBalanceBefore(before);
        tx.setBalanceAfter(after);
        transactionRepository.save(tx);

        logActivity(admin, "APPROVE_TRANSACTION",
            "TRANSACTION", transactionId,
            "Approved ₱" + tx.getAmount() +
            " for passenger " + passenger.getId(),
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("transactionId", transactionId);
        result.put("newBalance",    after);
        result.put("status",        "SUCCESS");
        return ApiResponse.success(
            "Transaction approved.", result);
    }

    @Transactional
    public ApiResponse<String> rejectTransaction(
            Admin admin, Long transactionId) {

        Transaction tx = transactionRepository
            .findById(transactionId)
            .orElseThrow(() ->
                new RuntimeException(
                    "Transaction not found."));

        if (tx.getStatus() != TransactionStatus.PENDING)
            throw new RuntimeException(
                "Transaction is not pending.");

        tx.setStatus(TransactionStatus.FAILED);
        transactionRepository.save(tx);

        logActivity(admin, "REJECT_TRANSACTION",
            "TRANSACTION", transactionId,
            "Rejected transaction " + transactionId,
            "localhost");

        return ApiResponse.success(
            "Transaction rejected.", "REJECTED");
    }

    //  USERS 
    public ApiResponse<Page<Passenger>> getAllUsers(
            int page, int size) {
        return ApiResponse.success(
            "Users fetched.",
            passengerRepository.findAll(
                PageRequest.of(page, size,
                    Sort.by(Sort.Direction.DESC,
                        "createdAt"))));
    }

    @Transactional
    public ApiResponse<Map<String, Object>> addBalance(
            Admin admin, Long passengerId,
            BigDecimal amount, String note) {

        Passenger passenger = passengerRepository
            .findById(passengerId)
            .orElseThrow(() ->
                new RuntimeException(
                    "Passenger not found."));

        BigDecimal oldBalance = passenger.getBalance();
        BigDecimal newBalance = oldBalance.add(amount);
        passenger.setBalance(newBalance);
        passengerRepository.save(passenger);

        Transaction tx = new Transaction();
        tx.setPassenger(passenger);
        tx.setType(TransactionType.TOPUP);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setAmount(amount);
        tx.setBalanceBefore(oldBalance);
        tx.setBalanceAfter(newBalance);
        tx.setDescription("Admin top-up: " +
            (note != null ? note : ""));
        tx.setReferenceNumber("ADMIN-" +
            UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase());
        transactionRepository.save(tx);

        logActivity(admin, "ADD_BALANCE",
            "PASSENGER", passengerId,
            "Added ₱" + amount +
            " to passenger " + passengerId,
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("passengerId", passengerId);
        result.put("addedAmount", amount);
        result.put("newBalance",  newBalance);
        return ApiResponse.success("Balance added.", result);
    }

    @Transactional
    public ApiResponse<Passenger> createPassenger(
            Admin admin,
            String cardNumber,
            String rfidUid,
            BigDecimal initialBalance) {

        if (passengerRepository.existsByCardNumber(cardNumber))
            throw new RuntimeException(
                "Card number already registered.");

        if (rfidUid != null &&
            passengerRepository.existsByRfidUid(rfidUid))
            throw new RuntimeException(
                "RFID UID already registered.");

        String totpSecret = totpService.generateSecret();

        Passenger passenger = Passenger.builder()
                .cardNumber(cardNumber)
                .rfidUid(rfidUid)
                .balance(initialBalance != null
                    ? initialBalance : BigDecimal.ZERO)
                .twofaSecret(totpSecret)   
                .is2FaEnabled(false)     
                .status(PassengerStatus.ACTIVE)
                .build();

        Passenger saved =
            passengerRepository.save(passenger);

        logActivity(admin, "CREATE_USER",
            "PASSENGER", saved.getId(),
            "Created passenger — Card: " + cardNumber +
            " | RFID: " + rfidUid,
            "localhost");

        return ApiResponse.success(
            "Passenger created successfully.", saved);
    }

    //  ADMIN MANAGEMENT 
    public ApiResponse<List<Admin>> getAllAdmins() {
        return ApiResponse.success(
            "Admins fetched.",
            adminRepository.findAll());
    }

    @Transactional
    public ApiResponse<Admin> createAdmin(
            Admin requestingAdmin,
            String username, String password,
            String fullName, String email,
            String phoneNumber, AdminRole role) {

        if (!requestingAdmin.isSuperAdmin())
            throw new RuntimeException(
                "Only Super Admin can create admins.");

        if (adminRepository.existsByUsername(username))
            throw new RuntimeException(
                "Username already exists.");

        String adminId = "ADM-" + String.format(
            "%03d", adminRepository.count() + 1);

        Admin newAdmin = Admin.builder()
            .adminId(adminId)
            .username(username)
            .password(passwordEncoder.encode(password))
            .fullName(fullName)
            .email(email)
            .phoneNumber(phoneNumber)
            .role(role)
            .active(true)
            .is2FaEnabled(false)
            .twofaSecret(null)
            .build();

        adminRepository.save(newAdmin);

        logActivity(requestingAdmin, "CREATE_ADMIN",
            "ADMIN", newAdmin.getId(),
            "Created admin: " + username +
            " with role " + role,
            "localhost");

        return ApiResponse.success(
            "Admin created.", newAdmin);
    }

    @Transactional
    public ApiResponse<Admin> updateAdmin(
            Admin requestingAdmin, Long adminId,
            String fullName, String email,
            String phoneNumber, AdminRole role,
            Boolean active) {

        if (!requestingAdmin.isSuperAdmin())
            throw new RuntimeException(
                "Only Super Admin can edit admins.");

        Admin target = adminRepository.findById(adminId)
            .orElseThrow(() ->
                new RuntimeException("Admin not found."));

        if (fullName    != null) target.setFullName(fullName);
        if (email       != null) target.setEmail(email);
        if (phoneNumber != null) target.setPhoneNumber(phoneNumber);
        if (role        != null) target.setRole(role);
        if (active      != null) target.setActive(active);

        adminRepository.save(target);

        logActivity(requestingAdmin, "UPDATE_ADMIN",
            "ADMIN", adminId,
            "Updated admin ID: " + adminId,
            "localhost");

        return ApiResponse.success("Admin updated.", target);
    }

    @Transactional
    public ApiResponse<String> deleteAdmin(
            Admin requestingAdmin, Long adminId) {

        if (!requestingAdmin.isSuperAdmin())
            throw new RuntimeException(
                "Only Super Admin can delete admins.");

        if (requestingAdmin.getId().equals(adminId))
            throw new RuntimeException(
                "Cannot delete your own account.");

        adminRepository.deleteById(adminId);

        logActivity(requestingAdmin, "DELETE_ADMIN",
            "ADMIN", adminId,
            "Deleted admin ID: " + adminId,
            "localhost");

        return ApiResponse.success(
            "Admin deleted.", "DELETED");
    }

    @Transactional
    public ApiResponse<String> resetAdminPassword(
            Admin requestingAdmin, Long adminId,
            String newPassword) {

        if (!requestingAdmin.isSuperAdmin())
            throw new RuntimeException(
                "Only Super Admin can reset passwords.");

        Admin target = adminRepository.findById(adminId)
            .orElseThrow(() ->
                new RuntimeException("Admin not found."));

        target.setPassword(
            passwordEncoder.encode(newPassword));
        target.setLoginAttempts(0);
        target.setLockedUntil(null);
        adminRepository.save(target);

        logActivity(requestingAdmin, "RESET_PASSWORD",
            "ADMIN", adminId,
            "Password reset for admin: " + adminId,
            "localhost");

        return ApiResponse.success(
            "Password reset.", "RESET");
    }

    //ACTIVITY LOGS 
    public ApiResponse<Page<ActivityLog>> getActivityLogs(
            int page, int size) {
        return ApiResponse.success("Logs fetched.",
            activityLogRepository
                .findAllByOrderByCreatedAtDesc(
                    PageRequest.of(page, size)));
    }

    public ApiResponse<Map<String, Object>> getLogStats() {
        long total       = activityLogRepository.count();
        long todayCount  = activityLogRepository
            .countTodayLogs(
                LocalDate.now().atStartOfDay());
        long uniqueAdmins = activityLogRepository
            .countUniqueAdmins();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalLogs",    total);
        stats.put("todayLogs",    todayCount);
        stats.put("uniqueAdmins", uniqueAdmins);
        return ApiResponse.success(
            "Log stats fetched.", stats);
    }

    //  HELPER 
    private void logActivity(Admin admin, String action,
                              String targetType, Long targetId,
                              String details, String ip) {
        ActivityLog log = ActivityLog.builder()
            .admin(admin)
            .action(action)
            .targetType(targetType)
            .targetId(targetId)
            .details(details)
            .ipAddress(ip)
            .status("SUCCESS")
            .build();
        activityLogRepository.save(log);
    }
}
