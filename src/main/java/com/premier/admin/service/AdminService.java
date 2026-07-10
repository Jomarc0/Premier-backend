package com.premier.admin.service;

import com.premier.admin.model.*;
import com.premier.admin.repository.*;
import com.premier.admin.security.AdminJwtUtil;
import com.premier.driver.model.*;
import com.premier.driver.repository.*;
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
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class AdminService {
    private static final SecureRandom CARD_NUMBER_RANDOM = new SecureRandom();

    private final AdminRepository        adminRepository;
    private final AdminJwtUtil           adminJwtUtil;
    private final PasswordEncoder        passwordEncoder;
    private final PassengerRepository    passengerRepository;
    private final TransactionRepository  transactionRepository;
    private final ActivityLogRepository  activityLogRepository;
    private final TotpService            totpService;
    private final DriverRepository       driverRepository;
    private final VehicleRepository      vehicleRepository;

    public AdminService(
            AdminRepository adminRepository,
            AdminJwtUtil adminJwtUtil,
            PasswordEncoder passwordEncoder,
            PassengerRepository passengerRepository,
            TransactionRepository transactionRepository,
            ActivityLogRepository activityLogRepository,
            TotpService totpService,
            DriverRepository driverRepository,
            VehicleRepository vehicleRepository) {
        this.adminRepository       = adminRepository;
        this.adminJwtUtil          = adminJwtUtil;
        this.passwordEncoder       = passwordEncoder;
        this.passengerRepository   = passengerRepository;
        this.transactionRepository = transactionRepository;
        this.activityLogRepository = activityLogRepository;
        this.totpService           = totpService;
        this.driverRepository      = driverRepository;
        this.vehicleRepository     = vehicleRepository;
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
        data.put("is2FaEnabled", Boolean.TRUE.equals(admin.getIs2FaEnabled()));

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
            "Approved PHP " + tx.getAmount() +
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
            BigDecimal amount, String reason,
            String ipAddress) {

        if (amount == null
                || amount.compareTo(new BigDecimal("1.00")) < 0
                || amount.compareTo(new BigDecimal("10000.00")) > 0) {
            throw new RuntimeException("Amount must be between 1.00 and 10000.00.");
        }

        if (reason == null || reason.trim().isEmpty()) {
            throw new RuntimeException("Adjustment reason is required.");
        }

        if (amount.compareTo(new BigDecimal("5000.00")) >= 0 && !admin.isSuperAdmin()) {
            throw new RuntimeException("Super Admin approval is required for adjustments of 5000.00 or more.");
        }

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
        tx.setPaymentMethod(PaymentMethod.ADMIN);
        tx.setDescription("Admin balance adjustment: " +
            reason.trim());
        logActivity(admin, "ADD_BALANCE_DETAIL",
            "PASSENGER", passengerId,
            "Balance adjustment | amount: PHP " + amount +
            " | " + oldBalance + " -> " + newBalance +
            " | reason: " + reason.trim(),
            ipAddress != null ? ipAddress : "unknown");
        tx.setReferenceNumber("ADMIN-" +
            UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase());
        transactionRepository.save(tx);

        logActivity(admin, "ADD_BALANCE",
            "PASSENGER", passengerId,
            "Added PHP " + amount +
            " to passenger " + passengerId,
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("passengerId", passengerId);
        result.put("addedAmount", amount);
        result.put("newBalance",  newBalance);
        result.put("reason", reason.trim());
        return ApiResponse.success("Balance added.", result);
    }

    @Transactional
    public ApiResponse<Map<String, Object>> freezePassengerCard(
            Admin admin, Long passengerId) {
        return updatePassengerCardStatus(
            admin,
            passengerId,
            PassengerStatus.SUSPENDED,
            "FREEZE_CARD",
            "Card frozen.");
    }

    @Transactional
    public ApiResponse<Map<String, Object>> unfreezePassengerCard(
            Admin admin, Long passengerId) {
        return updatePassengerCardStatus(
            admin,
            passengerId,
            PassengerStatus.ACTIVE,
            "UNFREEZE_CARD",
            "Card unfrozen.");
    }

    private ApiResponse<Map<String, Object>> updatePassengerCardStatus(
            Admin admin,
            Long passengerId,
            PassengerStatus status,
            String action,
            String message) {

        Passenger passenger = passengerRepository
            .findById(passengerId)
            .orElseThrow(() ->
                new RuntimeException(
                    "Passenger not found."));

        PassengerStatus oldStatus = passenger.getStatus();
        passenger.setStatus(status);
        passengerRepository.save(passenger);

        logActivity(admin, action,
            "PASSENGER", passengerId,
            "Updated passenger " + passengerId +
            " card status from " + oldStatus +
            " to " + status,
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("passengerId", passengerId);
        result.put("cardNumber", passenger.getCardNumber());
        result.put("oldStatus", oldStatus);
        result.put("status", status);

        return ApiResponse.success(message, result);
    }

    @Transactional
    public ApiResponse<Passenger> createPassenger(
            Admin admin,
            String rfidUid,
            String category) {

        String normalizedUid = normalizeRfidUid(rfidUid);
        PassengerCardCategory cardCategory = parseCardCategory(category);

        if (passengerRepository.existsByRfidUid(normalizedUid))
            throw new RuntimeException(
                "RFID UID already registered.");

        String cardNumber = generateUniqueCardNumber();

        Passenger passenger = Passenger.builder()
                .cardNumber(cardNumber)
                .rfidUid(normalizedUid)
                .balance(BigDecimal.ZERO)
                .cardCategory(cardCategory)
                .discountEligible(cardCategory != PassengerCardCategory.REGULAR)
                .createdByAdminId(admin != null ? admin.getId() : null)
                .is2FaEnabled(false)
                .status(PassengerStatus.AVAILABLE)
                .build();

        Passenger saved =
            passengerRepository.save(passenger);

        logActivity(admin, "CREATE_RFID_CARD",
            "PASSENGER", saved.getId(),
            "Created RFID card stock - Card: " + cardNumber +
            " | Type: " + cardCategory,
            "localhost");

        return ApiResponse.success(
            "RFID card created successfully.", saved);
    }

    private String normalizeRfidUid(String rfidUid) {
        if (rfidUid == null || rfidUid.isBlank()) {
            throw new RuntimeException("RFID UID is required.");
        }
        String normalized = rfidUid.trim()
                .replaceAll("[^A-Fa-f0-9]", "")
                .toUpperCase();
        if (normalized.length() < 4) {
            throw new RuntimeException("RFID UID is invalid.");
        }
        return normalized;
    }

    private PassengerCardCategory parseCardCategory(String category) {
        try {
            return PassengerCardCategory.valueOf(
                category == null ? "REGULAR" : category.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Invalid card category.");
        }
    }

    private String generateUniqueCardNumber() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String cardNumber = String.valueOf(
                1000000000L + CARD_NUMBER_RANDOM.nextInt(900000000));
            if (!passengerRepository.existsByCardNumber(cardNumber)) {
                return cardNumber;
            }
        }
        throw new RuntimeException("Unable to generate unique card number.");
    }

    // DRIVER MANAGEMENT
    @Transactional
    public ApiResponse<Driver> createDriver(
            Admin admin,
            String fullName,
            String licenseNumber,
            String phoneNumber,
            DriverStatus status) {

        String normalizedLicense = normalizeRequired(
            licenseNumber, "License number").toUpperCase();

        if (driverRepository.existsByLicenseNumber(normalizedLicense))
            throw new RuntimeException(
                "License number already registered.");

        Driver driver = Driver.builder()
            .fullName(normalizeRequired(fullName, "Full name"))
            .licenseNumber(normalizedLicense)
            .phoneNumber(normalizeRequired(
                phoneNumber, "Phone number"))
            .status(status != null
                ? status : DriverStatus.INACTIVE)
            .build();

        Driver saved = driverRepository.save(driver);

        logActivity(admin, "CREATE_DRIVER",
            "DRIVER", saved.getId(),
            "Created driver: " + saved.getFullName(),
            "localhost");

        return ApiResponse.success(
            "Driver created.", saved);
    }

    @Transactional
    public ApiResponse<Driver> updateDriver(
            Admin admin,
            Long driverId,
            String fullName,
            String licenseNumber,
            String phoneNumber,
            DriverStatus status) {

        Driver driver = driverRepository.findById(driverId)
            .orElseThrow(() ->
                new RuntimeException("Driver not found."));

        if (licenseNumber != null && !licenseNumber.isBlank()) {
            String normalizedLicense =
                licenseNumber.trim().toUpperCase();
            driverRepository.findByLicenseNumber(normalizedLicense)
                .filter(existing ->
                    !existing.getId().equals(driverId))
                .ifPresent(existing -> {
                    throw new RuntimeException(
                        "License number already registered.");
                });
            driver.setLicenseNumber(normalizedLicense);
        }

        if (fullName != null && !fullName.isBlank())
            driver.setFullName(fullName.trim());
        if (phoneNumber != null && !phoneNumber.isBlank())
            driver.setPhoneNumber(phoneNumber.trim());
        if (status != null)
            driver.setStatus(status);

        Driver saved = driverRepository.save(driver);

        logActivity(admin, "UPDATE_DRIVER",
            "DRIVER", driverId,
            "Updated driver: " + saved.getFullName(),
            "localhost");

        return ApiResponse.success(
            "Driver updated.", saved);
    }

    @Transactional
    public ApiResponse<String> deleteDriver(
            Admin admin,
            Long driverId) {

        Driver driver = driverRepository.findById(driverId)
            .orElseThrow(() ->
                new RuntimeException("Driver not found."));

        driverRepository.delete(driver);

        logActivity(admin, "DELETE_DRIVER",
            "DRIVER", driverId,
            "Deleted driver: " + driver.getFullName(),
            "localhost");

        return ApiResponse.success(
            "Driver deleted.", "DELETED");
    }

    // VEHICLE MANAGEMENT
    @Transactional
    public ApiResponse<Vehicle> createVehicle(
            Admin admin,
            String plateNumber,
            Integer totalCapacity,
            String route,
            VehicleStatus status) {

        String normalizedPlate = normalizeRequired(
            plateNumber, "Plate number").toUpperCase();

        if (vehicleRepository.existsByPlateNumber(normalizedPlate))
            throw new RuntimeException(
                "Plate number already registered.");

        Vehicle vehicle = Vehicle.builder()
            .plateNumber(normalizedPlate)
            .totalCapacity(validateCapacity(totalCapacity))
            .route(normalizeOptional(route))
            .status(status != null
                ? status : VehicleStatus.INACTIVE)
            .build();

        Vehicle saved = vehicleRepository.save(vehicle);

        logActivity(admin, "CREATE_VEHICLE",
            "VEHICLE", saved.getId(),
            "Created vehicle: " + saved.getPlateNumber(),
            "localhost");

        return ApiResponse.success(
            "Vehicle created.", saved);
    }

    @Transactional
    public ApiResponse<Vehicle> updateVehicle(
            Admin admin,
            Long vehicleId,
            String plateNumber,
            Integer totalCapacity,
            String route,
            VehicleStatus status) {

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
            .orElseThrow(() ->
                new RuntimeException("Vehicle not found."));

        if (plateNumber != null && !plateNumber.isBlank()) {
            String normalizedPlate =
                plateNumber.trim().toUpperCase();
            vehicleRepository.findByPlateNumber(normalizedPlate)
                .filter(existing ->
                    !existing.getId().equals(vehicleId))
                .ifPresent(existing -> {
                    throw new RuntimeException(
                        "Plate number already registered.");
                });
            vehicle.setPlateNumber(normalizedPlate);
        }

        if (totalCapacity != null)
            vehicle.setTotalCapacity(
                validateCapacity(totalCapacity));
        if (route != null && !route.isBlank())
            vehicle.setRoute(route.trim());
        if (status != null)
            vehicle.setStatus(status);

        Vehicle saved = vehicleRepository.save(vehicle);

        logActivity(admin, "UPDATE_VEHICLE",
            "VEHICLE", vehicleId,
            "Updated vehicle: " + saved.getPlateNumber(),
            "localhost");

        return ApiResponse.success(
            "Vehicle updated.", saved);
    }

    @Transactional
    public ApiResponse<String> deleteVehicle(
            Admin admin,
            Long vehicleId) {

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
            .orElseThrow(() ->
                new RuntimeException("Vehicle not found."));

        vehicleRepository.delete(vehicle);

        logActivity(admin, "DELETE_VEHICLE",
            "VEHICLE", vehicleId,
            "Deleted vehicle: " + vehicle.getPlateNumber(),
            "localhost");

        return ApiResponse.success(
            "Vehicle deleted.", "DELETED");
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

    @Transactional
    public ApiResponse<Map<String, Object>> resetAdminTotp(
            Admin requestingAdmin, Long adminId) {

        if (!requestingAdmin.isSuperAdmin())
            throw new RuntimeException(
                "Only Super Admin can reset Google Authenticator.");

        Admin target = adminRepository.findById(adminId)
            .orElseThrow(() ->
                new RuntimeException("Admin not found."));

        if (AdminRole.STAFF.equals(target.getRole()))
            throw new RuntimeException(
                "Staff accounts do not use Google Authenticator.");

        target.setIs2FaEnabled(false);
        target.setTwofaSecret(null);
        target.setLoginAttempts(0);
        target.setLockedUntil(null);
        adminRepository.save(target);

        logActivity(requestingAdmin, "RESET_ADMIN_2FA",
            "ADMIN", adminId,
            "Reset Google Authenticator for admin: " +
            target.getUsername(),
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("adminId", adminId);
        result.put("username", target.getUsername());
        result.put("twoFactorEnabled", false);

        return ApiResponse.success(
            "Google Authenticator reset. Admin can log in with username and password, then set up a new authenticator.",
            result);
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

    private String normalizeRequired(
            String value,
            String label) {
        if (value == null || value.isBlank())
            throw new RuntimeException(label + " is required.");
        return value.trim();
    }

    private int validateCapacity(Integer totalCapacity) {
        if (totalCapacity == null || totalCapacity < 1)
            throw new RuntimeException(
                "Vehicle capacity must be at least 1.");
        return totalCapacity;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank()
            ? null
            : value.trim();
    }
}
