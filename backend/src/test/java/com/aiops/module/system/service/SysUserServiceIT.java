package com.aiops.module.system.service;

import com.aiops.module.system.controller.SysUserController.UserSaveRequest;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户-角色写操作的事务性，在真实 MySQL 上验证。
 * <p>
 * 只在显式提供测试库时运行，避免误连开发库：
 * <pre>mvn test -Dit.db.url="jdbc:mysql://localhost:3306/aiops_it?...&allowPublicKeyRetrieval=true"</pre>
 * 测试库由 Flyway 从空库建出（V1 结构 + V2 种子），与生产同一套迁移。
 */
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${it.db.url}",
        "aiops.logstash.enabled=false",
        "spring.task.scheduling.pool.size=1"
})
class SysUserServiceIT {

    @Autowired
    SysUserService sysUserService;
    @Autowired
    SysUserMapper sysUserMapper;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 改角色时插入失败_旧角色随事务回滚保留() {
        String username = "it_tx_" + System.nanoTime();
        UserSaveRequest add = new UserSaveRequest();
        add.setUsername(username);
        add.setPassword("123456");
        add.setRoleIds(List.of(2L));
        sysUserService.add(add);
        Long uid = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)).getId();
        assertEquals(List.of(2L), sysUserService.roleIdsOf(uid));

        // 新角色列表含重复项：先删旧角色、插入第一个 3 后，第二个 3 违反唯一键 uk_ur(user_id, role_id)
        // （不用 null 注入：MyBatis-Plus 默认 NOT_NULL 策略会把 null 列从 INSERT 里省略，不会触发约束）
        UserSaveRequest upd = new UserSaveRequest();
        upd.setId(uid);
        upd.setUsername(username);
        upd.setRoleIds(List.of(3L, 3L));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> sysUserService.update(upd));

        // 改造前：旧角色已删、只插进去 3 → 用户角色变成 [3] 或空；改造后整体回滚，仍是 [2]
        assertEquals(List.of(2L), sysUserService.roleIdsOf(uid));

        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", uid);
        jdbc.update("DELETE FROM sys_user WHERE id=?", uid);
    }

    @Test
    void 只改角色不改基本信息_不再生成空SET的UPDATE() {
        String username = "it_role_only_" + System.nanoTime();
        UserSaveRequest add = new UserSaveRequest();
        add.setUsername(username);
        add.setPassword("123456");
        add.setRoleIds(List.of(2L));
        sysUserService.add(add);
        Long uid = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)).getId();

        UserSaveRequest upd = new UserSaveRequest();
        upd.setId(uid);
        upd.setUsername(username);
        upd.setRoleIds(List.of(3L));
        sysUserService.update(upd);   // 改造前：BadSqlGrammarException（UPDATE sys_user SET  WHERE ...）
        assertEquals(List.of(3L), sysUserService.roleIdsOf(uid));

        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", uid);
        jdbc.update("DELETE FROM sys_user WHERE id=?", uid);
    }
}
