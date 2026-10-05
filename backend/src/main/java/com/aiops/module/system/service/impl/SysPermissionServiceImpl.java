package com.aiops.module.system.service.impl;

import com.aiops.module.system.entity.SysPermission;
import com.aiops.module.system.entity.SysRole;
import com.aiops.module.system.entity.SysRolePermission;
import com.aiops.module.system.entity.SysUserRole;
import com.aiops.module.system.mapper.SysPermissionMapper;
import com.aiops.module.system.mapper.SysRoleMapper;
import com.aiops.module.system.mapper.SysRolePermissionMapper;
import com.aiops.module.system.mapper.SysUserRoleMapper;
import com.aiops.module.system.service.SysPermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限服务实现
 */
@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermission>
        implements SysPermissionService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    private static final String ADMIN = "ADMIN";

    @Override
    public Set<String> getUserPermCodes(Long userId) {
        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        // ADMIN 直接管全量
        List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
        boolean isAdmin = roles.stream().anyMatch(r -> ADMIN.equals(r.getRoleCode()));
        if (isAdmin) {
            return Set.of("*:*:*");
        }
        List<Long> permIds = sysRolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
                .stream().map(SysRolePermission::getPermissionId).toList();
        if (permIds.isEmpty()) {
            return Set.of();
        }
        return listByIds(permIds).stream()
                .map(SysPermission::getPerms)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getUserRoleCodes(Long userId) {
        List<Long> roleIds = getUserRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return sysRoleMapper.selectBatchIds(roleIds).stream()
                .map(SysRole::getRoleCode).collect(Collectors.toSet());
    }

    @Override
    public List<SysPermission> tree() {
        // 只返回 M 类型菜单（按钮 B 不该出现在 /auth/info.menus，B 的 path=NULL 会让前端 startsWith 崩）
        List<SysPermission> all = list(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getStatus, 1)
                .eq(SysPermission::getPermType, "M")
                .orderByAsc(SysPermission::getSort));
        return buildTree(all, 0L);
    }

    private List<SysPermission> buildTree(List<SysPermission> all, Long parentId) {
        List<SysPermission> result = new ArrayList<>();
        for (SysPermission p : all) {
            if (parentId.equals(p.getParentId())) {
                p.setChildren(buildTree(all, p.getId()));
                result.add(p);
            }
        }
        result.sort(Comparator.comparing(SysPermission::getSort,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private List<Long> getUserRoleIds(Long userId) {
        return sysUserRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }
}
