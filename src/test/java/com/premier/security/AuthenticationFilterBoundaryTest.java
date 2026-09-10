package com.premier.security;

import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.admin.security.*;
import com.premier.device.model.DeviceType;
import com.premier.device.security.*;
import com.premier.device.service.DeviceService;
import com.premier.repository.PassengerRepository;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationFilterBoundaryTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); DeviceContext.clear(); }
    @Test void downstreamDeviceFailureIsNotRewrittenAsUnauthorized() throws Exception {
        var devices = mock(DeviceService.class);
        when(devices.authenticate("synthetic-device", "synthetic-token")).thenReturn(
                new DevicePrincipal(1L, "synthetic-device", DeviceType.VEHICLE_TERMINAL, 1L, "SYN-001", 0));
        var request = new MockHttpServletRequest("POST", "/api/rfid/tap");
        request.setServletPath("/api/rfid/tap");
        request.addHeader("X-Device-Id", "synthetic-device"); request.addHeader("X-Device-Token", "synthetic-token");
        var response = new MockHttpServletResponse();
        var downstream = new SecurityException("downstream failure");
        assertSame(downstream, assertThrows(SecurityException.class,
                () -> new DeviceAuthFilter(devices).doFilter(request, response, (req, res) -> { throw downstream; })));
        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
    }
    @Test void rejectedDeviceDoesNotExposeExceptionContent() throws Exception {
        var devices = mock(DeviceService.class);
        when(devices.authenticate(null, null)).thenThrow(new SecurityException("private \"credential\""));
        var request = new MockHttpServletRequest("POST", "/api/rfid/tap"); request.setServletPath("/api/rfid/tap");
        var response = new MockHttpServletResponse();
        new DeviceAuthFilter(devices).doFilter(request, response, (req, res) -> fail("Rejected request reached controller"));
        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().contains("private"));
        assertTrue(response.getContentAsString().contains("DEVICE_REJECTED"));
    }
    @Test void deviceAuthStoreFailureIsServiceUnavailable() throws Exception {
        var devices = mock(DeviceService.class);
        when(devices.authenticate("synthetic-device", "synthetic-token")).thenThrow(new IllegalStateException("database password"));
        var request = new MockHttpServletRequest("POST", "/api/rfid/tap"); request.setServletPath("/api/rfid/tap");
        request.addHeader("X-Device-Id", "synthetic-device"); request.addHeader("X-Device-Token", "synthetic-token");
        var response = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request, response,
                (req, res) -> new DeviceAuthFilter(devices).doFilter(req, res, (innerReq, innerRes) -> fail("Unavailable auth reached controller")));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("SERVICE_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains("database password"));
        assertTrue(response.getContentAsString().contains(response.getHeader("X-Request-Reference")));
    }
    @Test void passengerAuthStoreFailureIsServiceUnavailable() throws Exception {
        var jwt = mock(JwtUtil.class); var passengers = mock(PassengerRepository.class);
        when(jwt.isFullToken("synthetic")).thenReturn(true);
        when(jwt.extractPassengerId("synthetic")).thenReturn(7L);
        when(passengers.findById(7L)).thenThrow(new IllegalStateException("jdbc password"));
        var request = new MockHttpServletRequest("GET", "/api/passenger/balance"); request.setServletPath("/api/passenger/balance");
        request.addHeader("Authorization", "Bearer synthetic"); var response = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request, response,
                (req, res) -> new JwtAuthFilter(jwt, passengers).doFilter(req, res, (innerReq, innerRes) -> fail("Unavailable auth reached controller")));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("SERVICE_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains("jdbc password"));
        assertTrue(response.getContentAsString().contains(response.getHeader("X-Request-Reference")));
    }
    @Test void downstreamAdminFailureIsNotSwallowed() throws Exception {
        var jwt = mock(AdminJwtUtil.class); var admins = mock(AdminRepository.class);
        var admin = Admin.builder().id(1L).active(true).role(AdminRole.ADMIN).is2FaEnabled(true).build();
        when(jwt.isAdminToken("synthetic")).thenReturn(true); when(jwt.isTokenValid("synthetic")).thenReturn(true);
        when(jwt.extractAdminId("synthetic")).thenReturn(1L); when(jwt.isCurrentSession("synthetic", admin)).thenReturn(true);
        when(admins.findById(1L)).thenReturn(Optional.of(admin));
        var request = new MockHttpServletRequest("GET", "/api/admin/passengers"); request.setServletPath("/api/admin/passengers");
        request.addHeader("Authorization", "Bearer synthetic"); var response = new MockHttpServletResponse();
        var downstream = new IllegalStateException("downstream failure");
        assertSame(downstream, assertThrows(IllegalStateException.class,
                () -> new AdminAuthFilter(jwt, admins).doFilter(request, response, (req, res) -> { throw downstream; })));
        assertEquals("", response.getContentAsString());
    }
    @Test void adminUnauthorizedIncludesCodeAndReference() throws Exception {
        var jwt = mock(AdminJwtUtil.class); var admins = mock(AdminRepository.class);
        when(jwt.isAdminToken("synthetic")).thenReturn(true); when(jwt.isTokenValid("synthetic")).thenReturn(false);
        var request = new MockHttpServletRequest("GET", "/api/admin/passengers"); request.setServletPath("/api/admin/passengers");
        request.addHeader("Authorization", "Bearer synthetic"); var response = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request, response,
                (req, res) -> new AdminAuthFilter(jwt, admins).doFilter(req, res, (innerReq, innerRes) -> fail("Rejected auth reached controller")));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
        assertTrue(response.getContentAsString().contains(response.getHeader("X-Request-Reference")));
    }
    @Test void correlationReferenceIsGeneratedAndCleared() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-Request-Reference", "untrusted");
        var response = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request, response, (req, res) -> {
            assertNotEquals("untrusted", RequestCorrelationFilter.reference());
            assertEquals(response.getHeader("X-Request-Reference"), RequestCorrelationFilter.reference());
        });
        assertNull(org.slf4j.MDC.get("requestReference"));
    }
}
