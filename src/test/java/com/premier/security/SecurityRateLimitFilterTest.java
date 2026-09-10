package com.premier.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class SecurityRateLimitFilterTest {
    @Test void spoofedDeviceHeadersDoNotResetPeerLimit() throws Exception {
        var filter=new SecurityRateLimitFilter();
        for (int i=0;i<301;i++) {
            var request=new MockHttpServletRequest("POST","/api/rfid/tap"); request.setServletPath("/api/rfid/tap");
            request.addHeader("X-Device-Id","spoof-"+i);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(i<300?200:429);
        }
    }
    @Test void capacityFloodDoesNotEvictAnExistingBudget() {
        var filter=new SecurityRateLimitFilter(); var policy=new SecurityRateLimitFilter.Policy("test",1,Duration.ofHours(1));
        assertThat(filter.consume("victim",policy)).isTrue();
        for(int i=0;i<9999;i++) assertThat(filter.consume("peer-"+i,policy)).isTrue();
        assertThat(filter.consume("overflow",policy)).isFalse();
        assertThat(filter.consume("victim",policy)).isFalse();
    }
    @Test void authenticatedReadAndWriteRoutesAreCovered() {
        for(String path:new String[]{"/api/passenger/topup/status/a","/api/passenger/fare/qr","/api/admin/devices/3/rotate"}) {
            var request=new MockHttpServletRequest("GET",path);request.setServletPath(path);
            assertThat(SecurityRateLimitFilter.policy(request)).isNotNull();
        }
    }
}
