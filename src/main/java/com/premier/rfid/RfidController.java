package com.premier.rfid;

import com.premier.driver.model.DriverLocation;
import com.premier.driver.model.DriverShift;
import com.premier.driver.model.ShiftStatus;
import com.premier.driver.model.VehicleStatus;
import com.premier.driver.repository.DriverLocationRepository;
import com.premier.driver.repository.DriverShiftRepository;
import com.premier.driver.repository.VehicleRepository;
import com.premier.device.security.DeviceContext;
import com.premier.device.service.DeviceService;
import com.premier.response.ApiResponse;
import com.premier.service.FarePaymentService;
import com.premier.realtime.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/rfid")
@RequiredArgsConstructor
@Slf4j
public class RfidController {

    private final DriverShiftRepository driverShiftRepository;
    private final FarePaymentService farePaymentService;
    private final VehicleRepository vehicleRepository;
    private final DeviceService deviceService;
    private final DriverLocationRepository driverLocationRepository;
    private final RfidUidCaptureService rfidUidCaptureService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final com.premier.device.service.GpsTelemetryService gpsTelemetry;

    private static final double SM_LIPA_LAT = 13.954781;
    private static final double SM_LIPA_LNG = 121.163096;

    @PostMapping("/tap")
    public ResponseEntity<?> tapCard(@RequestBody DeviceFareRequest request) {
        try {
            return ResponseEntity.ok(farePaymentService.processRfidPayment(
                    request,
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    @PostMapping("/qr/process")
    public ResponseEntity<?> processQrFare(@RequestBody DeviceFareRequest request) {
        try {
            return ResponseEntity.ok(farePaymentService.processQrPayment(
                    request,
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    @PostMapping("/nfc/tap")
    public ResponseEntity<?> processNfcTap(@RequestBody DeviceFareRequest request) {
        try {
            String mobileNfcToken = request.getMobileNfcToken();
            if ((mobileNfcToken != null && !mobileNfcToken.isBlank())
                    || (request.getPayload() != null && !request.getPayload().isBlank())) {
                return ResponseEntity.ok(farePaymentService.processMobileNfcTokenPayment(
                        request,
                        DeviceContext.get()));
            }

            return ResponseEntity.ok(farePaymentService.processRfidPayment(
                    request,
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    @GetMapping("/vehicles")
    public ResponseEntity<?> getTerminalVehicles() {
        return ResponseEntity.ok(ApiResponse.success(
                "Active terminal vehicles fetched.",
                vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                        .map(RfidVehicleResponse::from).toList()));
    }

    @GetMapping("/registration/uid-request")
    public ResponseEntity<?> getPendingUidCaptureRequest() {
        return ResponseEntity.ok(rfidUidCaptureService.nextForDevice(DeviceContext.get()));
    }

    @PostMapping("/registration/uid-capture")
    public ResponseEntity<?> submitUidCapture(@RequestBody Map<String, Object> body) {
        try {
            deviceService.validateFreshNonce(
                    DeviceContext.get(),
                    (String) body.get("requestNonce"),
                    (String) body.get("requestTimestamp"));
            return ResponseEntity.ok(rfidUidCaptureService.submitFromDevice(
                    (String) body.get("requestId"),
                    (String) body.get("rfidUid"),
                    DeviceContext.get()));
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    @PostMapping("/gps")
    public ResponseEntity<?> updateVehicleGps(@jakarta.validation.Valid @RequestBody com.premier.device.request.GpsTelemetryRequest request) {
        return ResponseEntity.ok(gpsTelemetry.receive(DeviceContext.get(), request));
    }
}
