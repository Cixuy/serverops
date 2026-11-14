package com.qingruan.dto;
import jakarta.validation.constraints.NotBlank;
public record UserCreateRequest(@NotBlank String username, @NotBlank String password, @NotBlank String nickname, @NotBlank String role, String email, String phone) {}
