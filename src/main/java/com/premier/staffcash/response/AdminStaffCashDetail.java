package com.premier.staffcash.response;

import lombok.Builder;
import java.util.List;

@Builder
public record AdminStaffCashDetail(AdminStaffCashSummary summary,
                                   List<AdminStaffCashTransaction> transactions) {}
