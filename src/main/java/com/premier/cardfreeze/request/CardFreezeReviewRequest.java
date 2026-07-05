package com.premier.cardfreeze.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CardFreezeReviewRequest {
    @NotBlank(message = "Admin remarks are required.")
    @Size(max = 1000, message = "Admin remarks must be 1000 characters or less.")
    private String adminRemarks;
}
