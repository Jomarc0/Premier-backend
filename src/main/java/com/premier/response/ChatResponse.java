package com.premier.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private boolean success;
    private String reply;
    private String intent;
    private Boolean sensitive;
    private String recommendedAction;
    private Long requestId;
    private List<String> quickReplies;
    private String errorCode;
}
