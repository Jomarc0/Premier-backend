package com.premier.rfid;

import com.premier.driver.model.VehicleStatus;
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

    private final FarePaymentService farePaymentService;
    private final VehicleRepository vehicleRepository;
    private final DeviceService deviceService;
    private final RfidUidCaptureService rfidUidCaptureService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final com.premier.device.service.GpsTelemetryService gpsTelemetry;

    private static final double SM_LIPA_LAT = 13.954781;
    private static final double SM_LIPA_LNG = 121.163096;

    @PostMapping("/tap")
    public ResponseEntity<?> tapCard(@RequestBody DeviceFareRequest request) {
        long started = System.nanoTime();
        log.info("[PERF] payment=RFID stage=request_received elapsedMs=0");
        try {
            ResponseEntity<?> response = ResponseEntity.ok(farePaymentService.processRfidPayment(
                    request,
                    DeviceContext.get()));
            log.info("[PERF] payment=RFID stage=response_ready_after_commit elapsedMs={}", elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    @PostMapping("/qr/process")
    public ResponseEntity<?> processQrFare(@RequestBody DeviceFareRequest request) {
        long started = System.nanoTime();
        log.info("[PERF] payment=QR stage=request_received elapsedMs=0");
        try {
            ResponseEntity<?> response = ResponseEntity.ok(farePaymentService.processQrPayment(
                    request,
                    DeviceContext.get()));
            log.info("[PERF] payment=QR stage=response_ready_after_commit elapsedMs={}", elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    @PostMapping("/nfc/tap")
    public ResponseEntity<?> processNfcTap(@RequestBody DeviceFareRequest request) {
        long started = System.nanoTime();
        log.info("[PERF] payment=NFC stage=request_received elapsedMs=0");
        try {
            String mobileNfcToken = request.getMobileNfcToken();
            if ((mobileNfcToken != null && !mobileNfcToken.isBlank())
                    || (request.getPayload() != null && !request.getPayload().isBlank())) {
                ResponseEntity<?> response = ResponseEntity.ok(farePaymentService.processMobileNfcTokenPayment(
                        request,
                        DeviceContext.get()));
                log.info("[PERF] payment=NFC stage=response_ready_after_commit elapsedMs={}", elapsedMillis(started));
                return response;
            }

            ResponseEntity<?> response = ResponseEntity.ok(farePaymentService.processRfidPayment(
                    request,
                    DeviceContext.get()));
            log.info("[PERF] payment=NFC_RFID_FALLBACK stage=response_ready_after_commit elapsedMs={}", elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            throw e; // Preserve typed payment status/code; global handler sanitizes unexpected failures.
        }
    }

    private long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
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
