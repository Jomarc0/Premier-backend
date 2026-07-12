package com.premier.staffcash.request;

import com.premier.staffcash.model.StaffCashCardPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RegisterStaffCashCardRequest {
    @NotNull
    private Long staffId;
    @NotNull
    private StaffCashCardPurpose purpose;
    @NotBlank
    private String rfidUid;
}
