package com.premier.support.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SupportTicketSummaryResponse {
    private long pending;
    private long inReview;
}
