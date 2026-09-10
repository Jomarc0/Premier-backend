package com.premier.security;

import com.premier.admin.model.Admin;
import com.premier.device.security.DeviceContext;
import com.premier.model.Passenger;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AuthenticatedRateLimitFilter extends OncePerRequestFilter {
    private final SecurityRateLimitFilter limits;
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String identity = null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (DeviceContext.get() != null) identity = "device:" + DeviceContext.get().deviceId();
        else if (authentication != null && authentication.getPrincipal() instanceof Passenger passenger) identity = "passenger:" + passenger.getId();
        else if (authentication != null && authentication.getPrincipal() instanceof Admin admin) identity = "admin:" + admin.getId();
        if (identity != null && !limits.consume(identity, SecurityRateLimitFilter.policy(request))) {
            SecurityRateLimitFilter.reject(response); return;
        }
        chain.doFilter(request, response);
    }
}
