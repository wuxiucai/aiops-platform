package com.aiops.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限（菜单/按钮树）
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("parent_id")
    private Long parentId;

    @TableField("name")
    private String name;

    /** M菜单 B按钮 */
    @TableField("perm_type")
    private String permType;

    @TableField("path")
    private String path;

    @TableField("component")
    private String component;

    @TableField("perms")
    private String perms;

    @TableField("icon")
    private String icon;

    @TableField("sort")
    private Integer sort;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 子树（非表字段） */
    @TableField(exist = false)
    private List<SysPermission> children;
}
