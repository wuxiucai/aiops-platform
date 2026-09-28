package com.aiops.module.esa.service;

import com.aiops.common.BizException;
import com.aiops.common.util.AesUtil;
import com.aiops.datasource.log.EsFieldProbe;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsFieldCache;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsFieldCacheMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 数据源服务：CRUD 支撑 + 连通测试 + 索引列表 + 字段探测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsDatasourceService {

    private final EsDatasourceMapper esDatasourceMapper;
    private final EsFieldCacheMapper esFieldCacheMapper;
    private final EsLogClient esLogClient;
    private final EsFieldProbe esFieldProbe;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aiops.security.aes-key}")
    private String aesKey;

    public EsDatasource create(EsDatasource ds) {
        encryptSensitive(ds);
        if (ds.getIsDefault() != null && ds.getIsDefault() == 1) {
            clearDefault();
        }
        esDatasourceMapper.insert(ds);
        return mask(ds);
    }

    public EsDatasource update(EsDatasource ds) {
        EsDatasource db = esDatasourceMapper.selectById(ds.getId());
        if (db == null) {
            throw new BizException("数据源不存在");
        }
        // 密码/apiKey 未传则保留
        if (ds.getPasswordEnc() != null && !ds.getPasswordEnc().isBlank()) {
            ds.setPasswordEnc(AesUtil.encrypt(ds.getPasswordEnc(), aesKey));
        } else {
            ds.setPasswordEnc(db.getPasswordEnc());
        }
        if (ds.getApiKey() != null && !ds.getApiKey().isBlank()) {
            ds.setApiKey(AesUtil.encrypt(ds.getApiKey(), aesKey));
        } else {
            ds.setApiKey(db.getApiKey());
        }
        if (ds.getIsDefault() != null && ds.getIsDefault() == 1) {
            clearDefault();
        }
        esDatasourceMapper.updateById(ds);
        return mask(esDatasourceMapper.selectById(ds.getId()));
    }

    /** 连通性测试：GET / 与 _cluster/health，结果写回 es_version/cluster_name/last_test_time/test_result */
    public Map<String, Object> test(Long id) {
        EsDatasource ds = esDatasourceMapper.selectById(id);
        if (ds == null) {
            throw new BizException("数据源不存在");
        }
        String baseUrl = ds.baseUrl();
        Map<String, Object> result;
        try {
            String rootResp = esLogClient.get(baseUrl, "/");
            JsonNode root = objectMapper.readTree(rootResp);
            String version = root.path("version").path("number").asText();
            String clusterName = root.path("cluster_name").asText();

            String healthResp = esLogClient.get(baseUrl, "/_cluster/health");
            JsonNode health = objectMapper.readTree(healthResp);
            int nodeCount = health.path("number_of_nodes").asInt();
            String status = health.path("status").asText();

            // 探测 IK 分词器（§9.3）
            String ikInfo = "unknown";
            try {
                String plugins = esLogClient.get(baseUrl, "/_cat/plugins");
                ikInfo = plugins.contains("analysis-ik") ? "ik-installed" : "ik-not-installed";
            } catch (Exception ignored) {
            }

            result = Map.of("version", version, "clusterName", clusterName,
                    "nodeCount", nodeCount, "status", status, "ik", ikInfo);

            EsDatasource upd = new EsDatasource();
            upd.setId(id);
            upd.setEsVersion(version);
            upd.setClusterName(clusterName);
            upd.setLastTestTime(LocalDateTime.now());
            upd.setTestResult("OK " + version + " nodes=" + nodeCount + " " + ikInfo);
            esDatasourceMapper.updateById(upd);
            log.info("[ES] 数据源连通测试通过: id={}, version={}, ik={}", id, version, ikInfo);
        } catch (Exception e) {
            EsDatasource upd = new EsDatasource();
            upd.setId(id);
            upd.setLastTestTime(LocalDateTime.now());
            upd.setTestResult("FAIL: " + e.getMessage());
            esDatasourceMapper.updateById(upd);
            log.error("[ES] 数据源连通测试失败: id={}, err={}", id, e.getMessage());
            throw new BizException("连通测试失败: " + e.getMessage());
        }
        return result;
    }

    public void setDefault(Long id) {
        clearDefault();
        EsDatasource upd = new EsDatasource();
        upd.setId(id);
        upd.setIsDefault(1);
        esDatasourceMapper.updateById(upd);
    }

    /** _cat/indices?format=json：返回索引名、文档数、大小 */
    public List<Map<String, Object>> indices(Long id) {
        EsDatasource ds = getOrThrow(id);
        String resp = esLogClient.get(ds.baseUrl(), "/_cat/indices?format=json");
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(resp);
            arr.forEach(n -> list.add(Map.of(
                    "index", n.path("index").asText(),
                    "docsCount", n.path("docs.count").asLong(0),
                    "storeSize", n.path("store.size").asText())));
        } catch (Exception e) {
            throw new BizException("索引列表解析失败");
        }
        return list;
    }

    /** 字段探测并写 es_field_cache */
    public List<EsFieldProbe.FieldInfo> fields(Long id, String indexPattern) {
        EsDatasource ds = getOrThrow(id);
        List<EsFieldProbe.FieldInfo> fields = esFieldProbe.probe(ds.baseUrl(), indexPattern);
        // 写缓存（先清旧）
        esFieldCacheMapper.delete(new LambdaQueryWrapper<EsFieldCache>()
                .eq(EsFieldCache::getDatasourceId, id)
                .eq(EsFieldCache::getIndexPattern, indexPattern));
        for (EsFieldProbe.FieldInfo f : fields) {
            EsFieldCache c = new EsFieldCache();
            c.setDatasourceId(id);
            c.setIndexPattern(indexPattern);
            c.setFieldName(f.name());
            c.setFieldType(f.type());
            c.setLastScanTime(LocalDateTime.now());
            esFieldCacheMapper.insert(c);
        }
        return fields;
    }

    public EsDatasource getOrThrow(Long id) {
        EsDatasource ds = esDatasourceMapper.selectById(id);
        if (ds == null) {
            throw new BizException("数据源不存在");
        }
        return ds;
    }

    public EsDatasource getDefault() {
        EsDatasource ds = esDatasourceMapper.selectOne(new LambdaQueryWrapper<EsDatasource>()
                .eq(EsDatasource::getIsDefault, 1)
                .eq(EsDatasource::getStatus, 1)
                .last("LIMIT 1"));
        if (ds == null) {
            throw new BizException("未配置默认 ES 数据源");
        }
        return ds;
    }

    private void encryptSensitive(EsDatasource ds) {
        if (ds.getPasswordEnc() != null && !ds.getPasswordEnc().isBlank()) {
            ds.setPasswordEnc(AesUtil.encrypt(ds.getPasswordEnc(), aesKey));
        }
        if (ds.getApiKey() != null && !ds.getApiKey().isBlank()) {
            ds.setApiKey(AesUtil.encrypt(ds.getApiKey(), aesKey));
        }
    }

    private EsDatasource mask(EsDatasource ds) {
        if (ds.getPasswordEnc() != null && !ds.getPasswordEnc().isBlank()) {
            ds.setPasswordEnc("****");
        }
        if (ds.getApiKey() != null && !ds.getApiKey().isBlank()) {
            ds.setApiKey("****");
        }
        return ds;
    }

    private void clearDefault() {
        new LambdaUpdateChainWrapper<>(esDatasourceMapper)
                .eq(EsDatasource::getIsDefault, 1)
                .set(EsDatasource::getIsDefault, 0)
                .update();
    }
}
