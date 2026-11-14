package com.qingruan.dto;
import com.qingruan.entity.SysUser;
public record UserInfo(Long id, String username, String nickname, String role, String email, String phone, Integer status) {
    public static UserInfo from(SysUser u) { return new UserInfo(u.getId(), u.getUsername(), u.getNickname(), u.getRole(), u.getEmail(), u.getPhone(), u.getStatus()); }
}
