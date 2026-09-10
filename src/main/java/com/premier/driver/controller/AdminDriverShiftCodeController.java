package com.premier.driver.controller;

import com.premier.admin.model.Admin;
import com.premier.driver.request.IssueDriverShiftCodeRequest;
import com.premier.driver.response.DriverShiftCodeResponse;
import com.premier.driver.service.DriverPortalService;
import com.premier.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/driver-shift-codes")
@RequiredArgsConstructor
public class AdminDriverShiftCodeController {
    private final DriverPortalService service;

    @PostMapping
    public ApiResponse<DriverShiftCodeResponse> issue(Authentication authentication,
            @Valid @RequestBody IssueDriverShiftCodeRequest request) {
        return service.issueCode((Admin) authentication.getPrincipal(), request);
    }
}
