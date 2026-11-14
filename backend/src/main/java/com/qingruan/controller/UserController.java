package com.qingruan.controller;

import com.qingruan.dto.LoginRequest;
import com.qingruan.dto.UserCreateRequest;
import com.qingruan.dto.UserInfo;
import com.qingruan.dto.UserUpdateRequest;
import com.qingruan.service.JwtService;
import com.qingruan.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService service;
    private final JwtService jwtService;

    @PostMapping("/auth/login")
    public UserService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }

    @PostMapping("/auth/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        jwtService.revoke(authorization.substring("Bearer ".length()).trim());
    }

    @GetMapping("/users")
    public List<UserInfo> list() {
        return service.list();
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public UserInfo create(@Valid @RequestBody UserCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserInfo update(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}