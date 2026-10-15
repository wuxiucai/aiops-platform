package com.aiops.module.system.service;

import com.aiops.common.BizException;
import com.aiops.module.system.controller.SysUserController.UserSaveRequest;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.entity.SysUserRole;
import com.aiops.module.system.mapper.SysUserMapper;
import com.aiops.module.system.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户管理。用户与用户-角色关系的写操作放在同一事务里：
 * 此前在 Controller 中逐条执行，"删除旧角色"成功而"插入新角色"失败时，用户会丢光角色。
 */
@Service
@RequiredArgsConstructor
public class SysUserService {

    /** 内置管理员，不可删除 */
    public static final long BUILTIN_ADMIN_ID = 1L;

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    public Page<SysUser> page(long current, long size, String keyword) {
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SysUser>()
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(SysUser::getUsername, keyword)
                                        .or().like(SysUser::getNickname, keyword))
                        .orderByDesc(SysUser::getCreateTime));
        page.getRecords().forEach(u -> u.setPassword(null));
        return page;
    }

    @Transactional
    public void add(UserSaveRequest req) {
        Long cnt = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (cnt > 0) {
            throw new BizException("用户名已存在");
        }
        // password 上没有 @NotBlank（编辑时允许为空），新增时在此校验，避免 BCrypt.encode(null) 抛 500
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            throw new BizException("密码不能为空");
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        sysUserMapper.insert(user);
        insertRoles(user.getId(), req.getRoleIds());
    }

    /** roleIds 为 null 表示前端没动角色选择器，保留原角色；空数组才表示清空 */
    @Transactional
    public void update(UserSaveRequest req) {
        SysUser user = new SysUser();
        user.setId(req.getId());
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        // MyBatis-Plus 跳过 null 字段：只改角色、基本信息全空时会生成 "UPDATE sys_user SET WHERE ..."，
        // 语法错误导致整个保存失败。没有要改的基本字段就不发 UPDATE
        if (hasBasicFields(user)) {
            sysUserMapper.updateById(user);
        }
        if (req.getRoleIds() != null) {
            sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, req.getId()));
            insertRoles(req.getId(), req.getRoleIds());
        }
    }

    public List<Long> roleIdsOf(Long userId) {
        return sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    @Transactional
    public void delete(Long id) {
        if (id == BUILTIN_ADMIN_ID) {
            throw new BizException("内置管理员不可删除");
        }
        sysUserMapper.deleteById(id);
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
    }

    private static boolean hasBasicFields(SysUser u) {
        return u.getNickname() != null || u.getEmail() != null || u.getPhone() != null
                || u.getStatus() != null || u.getPassword() != null;
    }

    private void insertRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        }
    }
}
