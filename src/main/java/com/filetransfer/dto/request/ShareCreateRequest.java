package com.filetransfer.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ShareCreateRequest {

    @Min(1) @Max(8760)   // 1 hour to 1 year
    private Integer expiryHours;   // null = use server default

    @Min(1)
    private Integer maxDownloads;  // null = unlimited

    @Size(min = 4, max = 64, message = "Password must be 4–64 characters")
    private String password;       // null = no password
}