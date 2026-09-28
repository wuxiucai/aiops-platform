package com.aiops.module.system.service;

import com.aiops.module.system.entity.SysPermission;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Set;

/**
 * 权限服务接口（跨模块只允许经此接口调用）
 */
public interface SysPermissionService extends IService<SysPermission> {

    /** 查询用户权限编码集合（如 system:user:list）；ADMIN 返回 *:*:* */
    Set<String> getUserPermCodes(Long userId);

    /** 查询用户角色编码集合 */
    Set<String> getUserRoleCodes(Long userId);

    /** 完整权限树（管理端维护用） */
    List<SysPermission> tree();
}
