package com.aiops.security;

import lombok.Data;

import java.util.Set;

/**
 * 当前登录用户（来自 JWT 解析）
 */
@Data
public class LoginUser {

    private Long userId;
    private String username;
    private Set<String> roles;
    /** 权限编码集合，如 system:user:list */
    private Set<String> perms;

    public boolean hasPerm(String perm) {
        if (perms == null) {
            return false;
        }
        // ADMIN 拥有全部权限
        return perms.contains("*:*:*") || perms.contains(perm);
    }
}
