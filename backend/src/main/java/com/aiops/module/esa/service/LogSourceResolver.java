package com.aiops.module.esa.service;

import com.aiops.common.BizException;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 日志数据源解析：把"本次请求用哪个 ES 数据源 + 哪个索引映射"收敛到一处。
 * <p>
 * 解析顺序：
 * <ol>
 *   <li>显式传入 indexConfigId → 用该映射，数据源取映射所属的 datasource_id（两者必须一致）</li>
 *   <li>只传 datasourceId → 取该数据源下第一个启用的索引映射</li>
 *   <li>都不传 → 默认数据源（is_default=1 且启用）下第一个启用的索引映射</li>
 * </ol>
 * 取代此前散落在 Nl2DslService / LogTemplateJobService / LogSearchService 的 selectById(1L)。
 */
@Component
@RequiredArgsConstructor
public class LogSourceResolver {

    private final EsDatasourceMapper esDatasourceMapper;
    private final EsIndexConfigMapper esIndexConfigMapper;

    /** 一次解析的结果：数据源与索引映射总是成对出现 */
    public record LogSource(EsDatasource datasource, EsIndexConfig indexConfig) {
        public Long datasourceId() {
            return datasource.getId();
        }

        public Long indexConfigId() {
            return indexConfig.getId();
        }
    }

    public LogSource resolve(Long datasourceId, Long indexConfigId) {
        if (indexConfigId != null) {
            EsIndexConfig idx = esIndexConfigMapper.selectById(indexConfigId);
            if (idx == null) {
                throw new BizException("索引映射不存在：" + indexConfigId);
            }
            if (datasourceId != null && !datasourceId.equals(idx.getDatasourceId())) {
                throw new BizException("索引映射 " + indexConfigId + " 不属于数据源 " + datasourceId);
            }
            return new LogSource(requireDatasource(idx.getDatasourceId()), idx);
        }
        EsDatasource ds = datasourceId != null ? requireDatasource(datasourceId) : defaultDatasource();
        return new LogSource(ds, firstEnabledIndex(ds.getId()));
    }

    /** 所有"启用的数据源 × 启用的索引映射"，供定时任务逐个处理 */
    public List<LogSource> listEnabled() {
        List<EsDatasource> dsList = esDatasourceMapper.selectList(new LambdaQueryWrapper<EsDatasource>()
                .eq(EsDatasource::getStatus, 1));
        return dsList.stream()
                .flatMap(ds -> esIndexConfigMapper.selectList(new LambdaQueryWrapper<EsIndexConfig>()
                                .eq(EsIndexConfig::getDatasourceId, ds.getId())
                                .eq(EsIndexConfig::getEnabled, 1))
                        .stream()
                        .map(idx -> new LogSource(ds, idx)))
                .toList();
    }

    private EsDatasource requireDatasource(Long id) {
        EsDatasource ds = esDatasourceMapper.selectById(id);
        if (ds == null) {
            throw new BizException("ES 数据源不存在：" + id);
        }
        return ds;
    }

    private EsDatasource defaultDatasource() {
        EsDatasource ds = esDatasourceMapper.selectOne(new LambdaQueryWrapper<EsDatasource>()
                .eq(EsDatasource::getIsDefault, 1)
                .eq(EsDatasource::getStatus, 1)
                .last("LIMIT 1"));
        if (ds == null) {
            throw new BizException("未配置默认 ES 数据源，请在【数据源管理】中设置");
        }
        return ds;
    }

    private EsIndexConfig firstEnabledIndex(Long datasourceId) {
        EsIndexConfig idx = esIndexConfigMapper.selectOne(new LambdaQueryWrapper<EsIndexConfig>()
                .eq(EsIndexConfig::getDatasourceId, datasourceId)
                .eq(EsIndexConfig::getEnabled, 1)
                .orderByAsc(EsIndexConfig::getId)
                .last("LIMIT 1"));
        if (idx == null) {
            throw new BizException("数据源 " + datasourceId + " 下没有启用的索引映射，请在日志检索页新增");
        }
        return idx;
    }
}
