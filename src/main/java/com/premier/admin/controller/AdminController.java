package com.premier.admin.controller;

import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.admin.security.AdminJwtUtil;
import com.premier.admin.service.AdminAnalyticsService;
import com.premier.admin.service.AdminService;
import com.premier.driver.model.*;
import com.premier.driver.repository.DriverRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService       adminService;
    private final AdminJwtUtil       adminJwtUtil;
    private final AdminAnalyticsService adminAnalyticsService;
    private final AdminRepository    adminRepository;
    private final DriverRepository   driverRepository;
    private final VehicleRepository  vehicleRepository;

    //  EXCEPTION HANDLER 
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleError(RuntimeException ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.contains("locked"))
            return ResponseEntity.status(403)
                .body(ApiResponse.error(msg));
        if (msg != null && msg.contains("disabled"))
            return ResponseEntity.status(403)
                .body(ApiResponse.error(msg));
        return ResponseEntity.status(401)
            .body(ApiResponse.error(
                msg != null ? msg : "Unauthorized"));
    }

    //HELPER 

    private Admin getCurrentAdmin(HttpServletRequest request) {
        String authHeader =
            request.getHeader("Authorization");
        if (authHeader == null ||
            !authHeader.startsWith("Bearer "))
            throw new RuntimeException(
                "No authorization token");

        String token = authHeader.substring(7);
        Long adminId = adminJwtUtil.extractAdminId(token);
        return adminRepository.findById(adminId)
            .orElseThrow(() ->
                new RuntimeException("Admin not found"));
    }

    // AUTH 

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        return ResponseEntity.ok(adminService.login(
            body.get("username"),
            body.get("password"),
            body.get("totpCode"),
            request.getRemoteAddr()));
    }

    @GetMapping("/auth/totp/setup")
    public ResponseEntity<?> adminTotpSetup(
            HttpServletRequest request) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.getAdminTotpSetup(admin));
    }

    @PostMapping("/auth/totp/verify")
    public ResponseEntity<?> adminTotpVerify(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.verifyAdminTotp(
                admin, body.get("totpCode")));
    }

    // DASHBOARD 

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> dashboardStats(
            HttpServletRequest request) {
        getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.getDashboardStats());
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> analytics(
            HttpServletRequest request,
            @RequestParam(defaultValue = "month") String range,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String bus) {
        getCurrentAdmin(request);
        return ResponseEntity.ok(ApiResponse.success(
            "Analytics fetched.",
            adminAnalyticsService.getAnalytics(range, from, to, route, bus)));
    }

    //TRANSACTIONS 

    @GetMapping("/transactions")
    public ResponseEntity<?> getAllTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {
        getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.getAllTransactions(page, size));
    }

    @PostMapping("/transactions/{id}/approve")
    public ResponseEntity<?> approve(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.approveTransaction(admin, id));
    }

    @PostMapping("/transactions/{id}/reject")
    public ResponseEntity<?> reject(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.rejectTransaction(admin, id));
    }

    // USERS 

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {
        getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.getAllUsers(page, size));
    }

    @PostMapping("/users/{id}/add-balance")
    public ResponseEntity<?> addBalance(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        BigDecimal amount = new BigDecimal(
            body.get("amount").toString());
        String note = (String) body.getOrDefault("note", "");
        return ResponseEntity.ok(
            adminService.addBalance(admin, id, amount, note));
    }

    @PostMapping("/users/{id}/freeze-card")
    public ResponseEntity<?> freezeCard(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.freezePassengerCard(admin, id));
    }

    @PostMapping("/users/{id}/unfreeze-card")
    public ResponseEntity<?> unfreezeCard(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.unfreezePassengerCard(admin, id));
    }

    @PostMapping("/users/create")
    public ResponseEntity<?> createUser(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        String cardNumber = (String) body.get("cardNumber");
        String rfidUid    = (String) body.get("rfidUid");
        BigDecimal balance = body.get("initialBalance") != null
            ? new BigDecimal(
                body.get("initialBalance").toString())
            : BigDecimal.ZERO;
        return ResponseEntity.ok(
            adminService.createPassenger(
                admin, cardNumber, rfidUid, balance));
    }

    // DRIVERS

    @GetMapping("/drivers")
    public ResponseEntity<?> getAllDrivers(
            HttpServletRequest request) {
        getCurrentAdmin(request);
        List<Driver> drivers = driverRepository.findAll();
        return ResponseEntity.ok(
            ApiResponse.success(
                "Drivers fetched.", drivers));
    }

    @PostMapping("/drivers")
    public ResponseEntity<?> createDriver(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        DriverStatus status = body.get("status") != null
            ? DriverStatus.valueOf(
                body.get("status").toString())
            : DriverStatus.INACTIVE;
        return ResponseEntity.ok(adminService.createDriver(
            admin,
            (String) body.get("fullName"),
            (String) body.get("licenseNumber"),
            (String) body.get("phoneNumber"),
            status));
    }

    @PutMapping("/drivers/{id}")
    public ResponseEntity<?> updateDriver(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        DriverStatus status = body.get("status") != null
            ? DriverStatus.valueOf(
                body.get("status").toString())
            : null;
        return ResponseEntity.ok(adminService.updateDriver(
            admin, id,
            (String) body.get("fullName"),
            (String) body.get("licenseNumber"),
            (String) body.get("phoneNumber"),
            status));
    }

    @DeleteMapping("/drivers/{id}")
    public ResponseEntity<?> deleteDriver(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.deleteDriver(admin, id));
    }

    //  VEHICLES 

    @GetMapping("/vehicles")
    public ResponseEntity<?> getAllVehicles(
            HttpServletRequest request) {
        getCurrentAdmin(request);
        List<Vehicle> vehicles = vehicleRepository.findAll();
        return ResponseEntity.ok(
            ApiResponse.success(
                "Vehicles fetched.", vehicles));
    }

    @PostMapping("/vehicles")
    public ResponseEntity<?> createVehicle(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        VehicleStatus status = body.get("status") != null
            ? VehicleStatus.valueOf(
                body.get("status").toString())
            : VehicleStatus.INACTIVE;
        Integer totalCapacity = body.get("totalCapacity") != null
            ? Integer.valueOf(
                body.get("totalCapacity").toString())
            : null;
        return ResponseEntity.ok(adminService.createVehicle(
            admin,
            (String) body.get("plateNumber"),
            totalCapacity,
            (String) body.get("route"),
            status));
    }

    @PutMapping("/vehicles/{id}")
    public ResponseEntity<?> updateVehicle(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        VehicleStatus status = body.get("status") != null
            ? VehicleStatus.valueOf(
                body.get("status").toString())
            : null;
        Integer totalCapacity = body.get("totalCapacity") != null
            ? Integer.valueOf(
                body.get("totalCapacity").toString())
            : null;
        return ResponseEntity.ok(adminService.updateVehicle(
            admin, id,
            (String) body.get("plateNumber"),
            totalCapacity,
            (String) body.get("route"),
            status));
    }

    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<?> deleteVehicle(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        return ResponseEntity.ok(
            adminService.deleteVehicle(admin, id));
    }

    // ADMIN MANAGEMENT 

    @GetMapping("/admins")
    public ResponseEntity<?> getAllAdmins(
            HttpServletRequest request) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        return ResponseEntity.ok(
            adminService.getAllAdmins());
    }

    @PostMapping("/admins/create")
    public ResponseEntity<?> createAdmin(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        AdminRole role = body.get("role") != null
            ? AdminRole.valueOf(
                body.get("role").toString())
            : AdminRole.ADMIN;
        return ResponseEntity.ok(adminService.createAdmin(
            admin,
            (String) body.get("username"),
            (String) body.get("password"),
            (String) body.get("fullName"),
            (String) body.get("email"),
            (String) body.get("phoneNumber"),
            role));
    }

    @PutMapping("/admins/{id}")
    public ResponseEntity<?> updateAdmin(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        AdminRole role = body.get("role") != null
            ? AdminRole.valueOf(
                body.get("role").toString())
            : null;
        Boolean active = body.get("active") != null
            ? Boolean.valueOf(
                body.get("active").toString())
            : null;
        return ResponseEntity.ok(adminService.updateAdmin(
            admin, id,
            (String) body.get("fullName"),
            (String) body.get("email"),
            (String) body.get("phoneNumber"),
            role, active));
    }

    @DeleteMapping("/admins/{id}")
    public ResponseEntity<?> deleteAdmin(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        return ResponseEntity.ok(
            adminService.deleteAdmin(admin, id));
    }

    @PutMapping("/admins/{id}/reset-password")
    public ResponseEntity<?> resetPassword(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        return ResponseEntity.ok(
            adminService.resetAdminPassword(
                admin, id, body.get("newPassword")));
    }

    @PutMapping("/admins/{id}/reset-totp")
    public ResponseEntity<?> resetAdminTotp(
            HttpServletRequest request,
            @PathVariable Long id) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        return ResponseEntity.ok(
            adminService.resetAdminTotp(admin, id));
    }

    // ACTIVITY LOGS 

    @GetMapping("/logs")
    public ResponseEntity<?> getLogs(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        return ResponseEntity.ok(
            adminService.getActivityLogs(page, size));
    }

    @GetMapping("/logs/stats")
    public ResponseEntity<?> getLogStats(
            HttpServletRequest request) {
        Admin admin = getCurrentAdmin(request);
        if (!admin.isSuperAdmin())
            return ResponseEntity.status(403)
                .body(ApiResponse.error(
                    "Access denied. Super Admin only."));
        return ResponseEntity.ok(
            adminService.getLogStats());
    }
}
