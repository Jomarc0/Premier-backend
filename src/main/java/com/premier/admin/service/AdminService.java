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
import com.premier.response.CardIssuanceResponse;
import com.premier.service.TotpService;
import com.premier.security.TotpSecretCrypto;
import com.premier.realtime.RealtimeEventPublisher;
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
    private final TotpSecretCrypto       totpSecretCrypto;
    private final DriverRepository       driverRepository;
    private final VehicleRepository      vehicleRepository;
    private final RealtimeEventPublisher  realtimeEventPublisher;
    private final com.premier.service.RfidUidRegistrationService uidRegistrations;

    public AdminService(
            AdminRepository adminRepository,
            AdminJwtUtil adminJwtUtil,
            PasswordEncoder passwordEncoder,
            PassengerRepository passengerRepository,
            TransactionRepository transactionRepository,
            ActivityLogRepository activityLogRepository,
            TotpService totpService,
            TotpSecretCrypto totpSecretCrypto,
            DriverRepository driverRepository,
            VehicleRepository vehicleRepository,
            RealtimeEventPublisher realtimeEventPublisher, com.premier.service.RfidUidRegistrationService uidRegistrations) {
        this.adminRepository       = adminRepository;
        this.adminJwtUtil          = adminJwtUtil;
        this.passwordEncoder       = passwordEncoder;
        this.passengerRepository   = passengerRepository;
        this.transactionRepository = transactionRepository;
        this.activityLogRepository = activityLogRepository;
        this.totpService           = totpService;
        this.totpSecretCrypto      = totpSecretCrypto;
        this.driverRepository      = driverRepository;
        this.vehicleRepository     = vehicleRepository;
        this.realtimeEventPublisher = realtimeEventPublisher;
        this.uidRegistrations = uidRegistrations;
    }

    // AUTH
    @Transactional(noRollbackFor = com.premier.exception.ClientException.class)
    public ApiResponse<Map<String, Object>> login(
            String username,
            String password,
            String totpCode,
            String ipAddress) {

        Admin admin = adminRepository
            .findLockedByUsername(username)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials."));

        if (admin.isLocked())
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED", "Account is locked. Try again later.");

        if (!admin.getActive())
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is disabled.");

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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials.");
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
                    totpSecretCrypto.decrypt(admin.getTwofaSecret()), totpCode.trim())) {
                admin.setLoginAttempts(admin.getLoginAttempts() + 1);
                if (admin.getLoginAttempts() >= 3) admin.setLockedUntil(LocalDateTime.now().plusMinutes(30));
                adminRepository.save(admin);
                throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid authenticator code.");
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

    @Transactional
    public ApiResponse<TotpSetupResponse> getAdminTotpSetup(Admin principal) {
        Admin admin = adminRepository.findLockedByUsername(principal.getUsername()).orElseThrow();
        if (Boolean.TRUE.equals(admin.getIs2FaEnabled())) {
            return ApiResponse.success("Authenticator is already enabled.", TotpSetupResponse.builder().is2FaEnabled(true).build());
        }
        if (admin.getTwofaSecret() == null ||
            admin.getTwofaSecret().isBlank()) {
            admin.setTwofaSecret(totpSecretCrypto.encrypt(totpService.generateSecret()));
            adminRepository.save(admin);
        }

        String totpSecret = totpSecretCrypto.decrypt(admin.getTwofaSecret());
        String qrCodeUrl = totpService.generateQrCodeUrl(
            totpSecret, admin.getUsername());

        return ApiResponse.success(
            "Scan QR code with Google Authenticator.",
            TotpSetupResponse.builder()
                .secret(null)
                .manualEntryKey(totpSecret)
                .qrCodeUrl(qrCodeUrl)
                .qrImageDataUri(totpService.generateQrImageDataUri(totpSecret, admin.getUsername()))
                .is2FaEnabled(Boolean.TRUE.equals(
                    admin.getIs2FaEnabled()))
                .build());
    }

    @Transactional(noRollbackFor = com.premier.exception.ClientException.class)
    public ApiResponse<Map<String, Object>> verifyAdminTotp(
            Admin principal,
            String code) {
        Admin admin = adminRepository.findLockedByUsername(principal.getUsername()).orElseThrow();
        if (Boolean.TRUE.equals(admin.getIs2FaEnabled()) || admin.isLocked()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Enrollment is not available.");
        }
        if (admin.getTwofaSecret() == null ||
            admin.getTwofaSecret().isBlank()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Begin enrollment before verifying a code.");
        }

        if (code == null || code.isBlank() ||
            !totpService.verifyCode(
                totpSecretCrypto.decrypt(admin.getTwofaSecret()), code.trim())) {
            admin.setLoginAttempts(admin.getLoginAttempts() + 1);
            if (admin.getLoginAttempts() >= 3) admin.setLockedUntil(LocalDateTime.now().plusMinutes(30));
            adminRepository.save(admin);
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid authenticator code.");
        }

        admin.setIs2FaEnabled(true);
        admin.setLoginAttempts(0);
        admin.setLockedUntil(null);
        admin.setSessionVersion(admin.getSessionVersion() + 1);
        adminRepository.save(admin);

        logActivity(admin, "ENABLE_ADMIN_2FA",
            "ADMIN", admin.getId(),
            "Enabled Google Authenticator security",
            "localhost");

        Map<String, Object> result = new HashMap<>();
        result.put("twoFactorEnabled", true);
        result.put("token", adminJwtUtil.generateAdminToken(admin.getId(), admin.getRole().name()));
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
    public ApiResponse<Page<com.premier.response.TransactionResponse>> getAllTransactions(
            int page, int size) {
        return ApiResponse.success(
            "Transactions fetched.",
            transactionRepository.findAllForAdmin(
                PageRequest.of(page, size,
                    Sort.by(Sort.Direction.DESC,
                        "createdAt"))).map(com.premier.response.TransactionResponse::from));
    }

    @Transactional
    public ApiResponse<Map<String, Object>> approveTransaction(
            Admin admin, Long transactionId) {

        Transaction tx = transactionRepository
            .findLockedById(transactionId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Transaction not found."));

        if (tx.getStatus() != TransactionStatus.PENDING)
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Transaction is not pending.");

        if (tx.getType() != TransactionType.TOPUP) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Only pending top-up records can be approved.");
        }
        Passenger passenger = passengerRepository.findLockedById(tx.getPassenger().getId()).orElseThrow();
        BigDecimal before = passenger.getBalance();
        BigDecimal after  = before.add(tx.getAmount());

        passenger.setBalance(after);
        passengerRepository.save(passenger);

        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setBalanceBefore(before);
        tx.setBalanceAfter(after);
        transactionRepository.save(tx);
        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "TRANSACTION_STATUS_CHANGED", "TRANSACTION", tx.getId());

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
            .findLockedById(transactionId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Transaction not found."));

        if (tx.getStatus() != TransactionStatus.PENDING)
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Transaction is not pending.");

        tx.setStatus(TransactionStatus.FAILED);
        transactionRepository.save(tx);
        realtimeEventPublisher.adminAndPassenger(tx.getPassenger().getId(), "TRANSACTION_STATUS_CHANGED", "TRANSACTION", tx.getId());

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

        amount = com.premier.payment.service.Money.exact(amount);
        if (amount == null
                || amount.compareTo(new BigDecimal("1.00")) < 0
                || amount.compareTo(new BigDecimal("10000.00")) > 0) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Amount must be between 1.00 and 10000.00.");
        }

        if (reason == null || reason.trim().isEmpty()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Adjustment reason is required.");
        }

        if (amount.compareTo(new BigDecimal("5000.00")) >= 0 && !admin.isSuperAdmin()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Super Admin approval is required for adjustments of 5000.00 or more.");
        }

        Passenger passenger = passengerRepository
            .findLockedById(passengerId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Passenger not found."));

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
                .replace("-", "").toUpperCase());
        transactionRepository.save(tx);
        realtimeEventPublisher.adminAndPassenger(passengerId, "BALANCE_UPDATED", "PASSENGER", passengerId);
        realtimeEventPublisher.adminAndPassenger(passengerId, "TRANSACTION_CREATED", "TRANSACTION", tx.getId());

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
            .findLockedById(passengerId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Passenger not found."));

        PassengerStatus oldStatus = passenger.getStatus();
        passenger.setStatus(status);
        passengerRepository.save(passenger);
        realtimeEventPublisher.adminAndPassenger(passengerId, "CARD_STATUS_CHANGED", "PASSENGER", passengerId);

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
    public ApiResponse<CardIssuanceResponse> createPassenger(
            Admin admin,
            String rfidUid,
            String category) {

        String normalizedUid = normalizeRfidUid(rfidUid);
        PassengerCardCategory cardCategory = parseCardCategory(category);

        if (passengerRepository.existsByRfidUid(normalizedUid))
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "RFID UID already registered.");

        String cardNumber = generateUniqueCardNumber();

        Passenger passenger = Passenger.builder()
                .cardNumber(cardNumber)
                .rfidUid(normalizedUid)
                .balance(Passenger.INITIAL_CARD_BALANCE)
                .cardCategory(cardCategory)
                .discountEligible(cardCategory != PassengerCardCategory.REGULAR)
                .createdByAdminId(admin != null ? admin.getId() : null)
                .is2FaEnabled(false)
                .status(PassengerStatus.AVAILABLE)
                .build();

        Passenger saved =
            passengerRepository.save(passenger);
        uidRegistrations.claim(normalizedUid, "PASSENGER", saved.getId());
        transactionRepository.save(Transaction.builder().passenger(saved).type(TransactionType.ADMIN_ADJUSTMENT)
                .status(TransactionStatus.SUCCESS).amount(Passenger.INITIAL_CARD_BALANCE)
                .balanceBefore(BigDecimal.ZERO).balanceAfter(Passenger.INITIAL_CARD_BALANCE)
                .referenceNumber("ISSUE-" + UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .description("Opening balance on card issuance").build());
        realtimeEventPublisher.admin("PASSENGER_CREATED", "PASSENGER", saved.getId());

        logActivity(admin, "CREATE_RFID_CARD",
            "PASSENGER", saved.getId(),
            "Created RFID card stock - Card: " + cardNumber +
            " | Type: " + cardCategory,
            "localhost");

        return ApiResponse.success("RFID card created successfully. Passenger can set up an authenticator using the card number.",
                CardIssuanceResponse.builder()
                        .passengerId(saved.getId()).cardNumber(saved.getCardNumber()).status(saved.getStatus()).build());
    }

    /** An operator correction adds a linked ledger entry; the original fare is never edited. */
    @Transactional
    public ApiResponse<Map<String, Object>> reverseFare(Admin admin, Long transactionId, String reason) {
        if (admin == null || !admin.isSuperAdmin() || !Boolean.TRUE.equals(admin.getActive())
                || !Boolean.TRUE.equals(admin.getIs2FaEnabled()) || admin.isLocked()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Super Admin authorization is required.");
        }
        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 240) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Provide a correction reason of 10 to 240 characters.");
        }
        Transaction original = transactionRepository.findLockedById(transactionId).orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Transaction not found."));
        if (original.getStatus() != TransactionStatus.SUCCESS || original.getType() != TransactionType.FARE_DEDUCTION) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Only successful wallet fares can be reversed here.");
        }
        var existing = transactionRepository.findByReversalOfId(transactionId);
        if (existing.isPresent()) return ApiResponse.success("Fare already reversed.", Map.of("referenceNumber", existing.get().getReferenceNumber(), "originalTransactionId", transactionId));
        Passenger passenger = passengerRepository.findLockedById(original.getPassenger().getId()).orElseThrow();
        BigDecimal before = passenger.getBalance();
        BigDecimal amount = com.premier.payment.service.Money.exact(original.getAmount());
        BigDecimal after = before.add(amount);
        passenger.setBalance(after);
        Transaction reversal = Transaction.builder().passenger(passenger).type(TransactionType.REFUND).status(TransactionStatus.SUCCESS)
                .amount(amount).balanceBefore(before).balanceAfter(after).paymentMethod(PaymentMethod.ADMIN)
                .reversalOfId(transactionId).referenceNumber("REV-" + UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .description("Fare correction for " + original.getReferenceNumber()).build();
        transactionRepository.saveAndFlush(reversal);
        logActivity(admin, "REVERSE_FARE", "TRANSACTION", transactionId, "Linked reversal " + reversal.getReferenceNumber() + " | " + reason.trim(), "unknown");
        realtimeEventPublisher.adminAndPassenger(passenger.getId(), "FARE_REVERSED", "TRANSACTION", reversal.getId());
        return ApiResponse.success("Fare reversed.", Map.of("referenceNumber", reversal.getReferenceNumber(), "originalTransactionId", transactionId));
    }

    private String normalizeRfidUid(String rfidUid) {
        if (rfidUid == null || rfidUid.isBlank()) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "RFID UID is required.");
        }
        String normalized = rfidUid.trim()
                .replaceAll("[^A-Fa-f0-9]", "")
                .toUpperCase();
        if (normalized.length() < 4) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "RFID UID is invalid.");
        }
        return normalized;
    }

    private PassengerCardCategory parseCardCategory(String category) {
        try {
            return PassengerCardCategory.valueOf(
                category == null ? "REGULAR" : category.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid card category.");
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
            String password,
            DriverStatus status) {

        String normalizedLicense = normalizeRequired(
            licenseNumber, "License number").toUpperCase();

        if (driverRepository.existsByLicenseNumber(normalizedLicense))
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "License number already registered.");

        if (!totpSecretCrypto.isEncrypted(admin.getTwofaSecret())) {
            admin.setTwofaSecret(totpSecretCrypto.encrypt(admin.getTwofaSecret()));
        }

        Driver driver = Driver.builder()
            .fullName(normalizeRequired(fullName, "Full name"))
            .licenseNumber(normalizedLicense)
            .phoneNumber(normalizeRequired(
                phoneNumber, "Phone number"))
            .status(status != null
                ? status : DriverStatus.INACTIVE)
            .build();

        Driver saved = driverRepository.save(driver);
        realtimeEventPublisher.admin("DRIVER_CREATED", "DRIVER", saved.getId());

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
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Driver not found."));

        if (licenseNumber != null && !licenseNumber.isBlank()) {
            String normalizedLicense =
                licenseNumber.trim().toUpperCase();
            driverRepository.findByLicenseNumber(normalizedLicense)
                .filter(existing ->
                    !existing.getId().equals(driverId))
                .ifPresent(existing -> {
                    throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "License number already registered.");
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
        realtimeEventPublisher.admin("DRIVER_UPDATED", "DRIVER", saved.getId());

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
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Driver not found."));

        driverRepository.delete(driver);
        realtimeEventPublisher.admin("DRIVER_DELETED", "DRIVER", driverId);

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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Plate number already registered.");

        Vehicle vehicle = Vehicle.builder()
            .plateNumber(normalizedPlate)
            .totalCapacity(validateCapacity(totalCapacity))
            .route(normalizeOptional(route))
            .status(status != null
                ? status : VehicleStatus.INACTIVE)
            .build();

        Vehicle saved = vehicleRepository.save(vehicle);
        realtimeEventPublisher.admin("VEHICLE_CREATED", "VEHICLE", saved.getId());

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
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Vehicle not found."));

        if (plateNumber != null && !plateNumber.isBlank()) {
            String normalizedPlate =
                plateNumber.trim().toUpperCase();
            vehicleRepository.findByPlateNumber(normalizedPlate)
                .filter(existing ->
                    !existing.getId().equals(vehicleId))
                .ifPresent(existing -> {
                    throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Plate number already registered.");
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
        realtimeEventPublisher.admin("VEHICLE_UPDATED", "VEHICLE", saved.getId());

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
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Vehicle not found."));

        vehicleRepository.delete(vehicle);
        realtimeEventPublisher.admin("VEHICLE_DELETED", "VEHICLE", vehicleId);

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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Super Admin can create admins.");

        if (adminRepository.existsByUsername(username))
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Username already exists.");

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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Super Admin can edit admins.");

        Admin target = adminRepository.findById(adminId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Admin not found."));

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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Super Admin can delete admins.");

        if (requestingAdmin.getId().equals(adminId))
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Cannot delete your own account.");

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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Super Admin can reset passwords.");

        Admin target = adminRepository.findById(adminId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Admin not found."));

        target.setSessionVersion(target.getSessionVersion() + 1);
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
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Super Admin can reset Google Authenticator.");

        Admin target = adminRepository.findById(adminId)
            .orElseThrow(() ->
                new com.premier.exception.ClientException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "Admin not found."));

        if (AdminRole.STAFF.equals(target.getRole()))
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.CONFLICT, "CONFLICT", "Staff accounts do not use Google Authenticator.");

        target.setSessionVersion(target.getSessionVersion() + 1);
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
        realtimeEventPublisher.admin("ACTIVITY_LOG_CREATED", "ACTIVITY_LOG", log.getId());
    }

    private String normalizeRequired(
            String value,
            String label) {
        if (value == null || value.isBlank())
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", label + " is required.");
        return value.trim();
    }

    private int validateCapacity(Integer totalCapacity) {
        if (totalCapacity == null || totalCapacity < 1)
            throw new com.premier.exception.ClientException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Vehicle capacity must be at least 1.");
        return totalCapacity;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank()
            ? null
            : value.trim();
    }
}

