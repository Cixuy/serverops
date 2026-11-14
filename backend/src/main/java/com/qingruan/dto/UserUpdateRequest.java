package com.qingruan.dto;
public record UserUpdateRequest(String nickname, String role, Integer status, String password, String email, String phone) {}
