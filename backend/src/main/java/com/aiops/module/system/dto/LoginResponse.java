package com.aiops.module.system.dto;

import lombok.Data;

import java.util.Set;

/**
 * 登录响应
 */
@Data
public class LoginResponse {

    private String token;

    private Long userId;

    private String username;

    private String nickname;

    private Set<String> roles;

    private Set<String> perms;
}
