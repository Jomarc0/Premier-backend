package com.premier.response;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(
            String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    // ✅ Make sure this exists
    public static <T> ApiResponse<T> error(
            String message) {
        return new ApiResponse<>(false, message, null);
    }
}