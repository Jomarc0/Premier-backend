package com.premier.response;

import lombok.Builder;
import lombok.Value;
import java.util.List;

@Value
@Builder
public class NotificationHistoryResponse {
    List<NotificationResponse> content;
    int page;
    int size;
    long totalElements;
    int totalPages;
    long unreadCount;
}
