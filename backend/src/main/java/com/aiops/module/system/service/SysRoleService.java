package com.aiops.module.system.service;

import com.aiops.common.BizException;
import com.aiops.module.system.controller.SysRoleController.RoleSaveRequest;
import com.aiops.module.system.entity.SysRole;
import com.aiops.module.system.entity.SysRolePermission;
import com.aiops.module.system.mapper.SysRoleMapper;
import com.aiops.module.system.mapper.SysRolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色管理。角色与角色-权限关系的写操作放在同一事务里，避免"旧权限已删、新权限未写"的半截状态。
 * 权限变更由 JwtAuthFilter 的 60s 权限缓存过期后生效。
 */
@Service
@RequiredArgsConstructor
public class SysRoleService {

    /** id ≤ 3 为内置角色（ADMIN / SRE / VIEWER），不可删除 */
    private static final long MAX_BUILTIN_ROLE_ID = 3L;

    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    public Page<SysRole> page(long current, long size) {
        return sysRoleMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    public List<SysRole> listEnabled() {
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>().eq(SysRole::getStatus, 1));
    }

    @Transactional
    public void add(RoleSaveRequest req) {
        Long cnt = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, req.getRoleCode()));
        if (cnt > 0) {
            throw new BizException("角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleCode(req.getRoleCode());
        role.setRoleName(req.getRoleName());
        role.setDescription(req.getDescription());
        role.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        sysRoleMapper.insert(role);
        insertPermissions(role.getId(), req.getPermissionIds());
    }

    public List<Long> permissionIdsOf(Long roleId) {
        return sysRolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream().map(SysRolePermission::getPermissionId).toList();
    }

    /** permissionIds 为 null 表示前端没动权限树，保留原权限；空数组才表示清空 */
    @Transactional
    public void update(RoleSaveRequest req) {
        SysRole role = new SysRole();
        role.setId(req.getId());
        role.setRoleName(req.getRoleName());
        role.setDescription(req.getDescription());
        role.setStatus(req.getStatus());
        // 同 SysUserService：只改权限时基本字段全空，不发会生成空 SET 的 UPDATE
        if (role.getRoleName() != null || role.getDescription() != null || role.getStatus() != null) {
            sysRoleMapper.updateById(role);
        }
        if (req.getPermissionIds() != null) {
            sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                    .eq(SysRolePermission::getRoleId, req.getId()));
            insertPermissions(req.getId(), req.getPermissionIds());
        }
    }

    @Transactional
    public void delete(Long id) {
        if (id <= MAX_BUILTIN_ROLE_ID) {
            throw new BizException("内置角色不可删除");
        }
        sysRoleMapper.deleteById(id);
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, id));
    }

    private void insertPermissions(Long roleId, List<Long> permIds) {
        if (permIds == null) {
            return;
        }
        for (Long pid : permIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pid);
            sysRolePermissionMapper.insert(rp);
        }
    }
}
