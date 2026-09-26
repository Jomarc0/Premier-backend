package com.premier.service;
import com.premier.rfid.*;
import com.premier.exception.*;
import com.premier.driver.repository.*;
import com.premier.device.service.DeviceService;
import com.premier.realtime.RealtimeEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentErrorContractTest {
    @Test void adminControllerUsesTypedErrorsWithoutGuessingFromMessages() throws Exception {
        var service = mock(com.premier.admin.service.AdminService.class);
        var controller = new com.premier.admin.controller.AdminController(service,
                mock(com.premier.admin.security.AdminJwtUtil.class), mock(com.premier.admin.service.AdminAnalyticsService.class),
                mock(com.premier.admin.repository.AdminRepository.class), mock(DriverRepository.class), mock(VehicleRepository.class),
                mock(RfidUidCaptureService.class), mock(com.premier.admin.service.FleetAssignmentService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.login(anyString(), anyString(), isNull(), anyString()))
                .thenThrow(new ClientException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials."))
                .thenThrow(new IllegalArgumentException("locked private database detail"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database address"));
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"synthetic\",\"password\":\"synthetic\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"synthetic\",\"password\":\"synthetic\"}"))
                .andExpect(status().isInternalServerError()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"synthetic\",\"password\":\"synthetic\"}"))
                .andExpect(status().isServiceUnavailable());
    }
    @org.springframework.web.bind.annotation.RestController
    static class InputController {
        record Input(int amount) {}
        @org.springframework.web.bind.annotation.PostMapping("/input")
        Input input(@org.springframework.web.bind.annotation.RequestBody Input input) { return input; }
    }
    @Test void malformedInputHasSafeClientErrorAndCorrelatedReference() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new InputController())
                .addFilters(new com.premier.security.RequestCorrelationFilter())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var result = mvc.perform(post("/input").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"private-data\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-data")))).andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(result.getResponse().getContentAsString()
                .contains(result.getResponse().getHeader("X-Request-Reference")));
        mvc.perform(get("/input")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
    @Test void paymentConflictsAndOutagesKeepSafeStatusAndCode() throws Exception {
        var fares = mock(FarePaymentService.class);
        var controller = new RfidController(fares, mock(VehicleRepository.class),
                mock(DeviceService.class), mock(RfidUidCaptureService.class), mock(RealtimeEventPublisher.class),
                mock(com.premier.device.service.GpsTelemetryService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(fares.processQrPayment(any(DeviceFareRequest.class), isNull()))
                .thenThrow(new ClientException(HttpStatus.CONFLICT, "QR_ALREADY_USED", "QR authorization has already been used."))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic database URL and private detail"))
                .thenThrow(new IllegalStateException("synthetic internal implementation detail"));
        mvc.perform(post("/api/rfid/qr/process").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_ALREADY_USED")).andExpect(jsonPath("$.reference").isNotEmpty());
        mvc.perform(post("/api/rfid/qr/process").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private detail"))));
        mvc.perform(post("/api/rfid/qr/process").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
