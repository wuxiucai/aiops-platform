package com.aiops.module.esa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ES 数据源
 */
@Data
@TableName("es_datasource")
public class EsDatasource {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("es_scheme")
    private String esScheme;

    @TableField("es_host")
    private String esHost;

    @TableField("es_port")
    private Integer esPort;

    @TableField("username")
    private String username;

    /** AES 加密存储 */
    @TableField("password_enc")
    private String passwordEnc;

    @TableField("api_key")
    private String apiKey;

    @TableField("es_version")
    private String esVersion;

    @TableField("cluster_name")
    private String clusterName;

    @TableField("status")
    private Integer status;

    @TableField("is_default")
    private Integer isDefault;

    @TableField("last_test_time")
    private LocalDateTime lastTestTime;

    @TableField("test_result")
    private String testResult;

    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 拼接 baseUrl */
    public String baseUrl() {
        return (esScheme == null ? "http" : esScheme) + "://" + esHost + ":" + esPort;
    }
}
