package com.aiops.module.esa.service;

import com.aiops.common.BizException;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LogSourceResolver：确认"用哪个数据源 + 哪个索引映射"的解析规则，
 * 防止回退到改造前固定 id=1 的行为。
 */
class LogSourceResolverTest {

    private EsDatasourceMapper dsMapper;
    private EsIndexConfigMapper idxMapper;
    private LogSourceResolver resolver;

    private final EsDatasource ds1 = ds(1L, 0);
    private final EsDatasource ds3 = ds(3L, 1);   // 默认数据源不是 1 号
    private final EsIndexConfig idx1 = idx(1L, 1L, "aiops-log-*");
    private final EsIndexConfig idx2 = idx(2L, 3L, "pam");

    @BeforeEach
    void setUp() {
        dsMapper = mock(EsDatasourceMapper.class);
        idxMapper = mock(EsIndexConfigMapper.class);
        resolver = new LogSourceResolver(dsMapper, idxMapper);
        when(dsMapper.selectById(1L)).thenReturn(ds1);
        when(dsMapper.selectById(3L)).thenReturn(ds3);
        when(idxMapper.selectById(1L)).thenReturn(idx1);
        when(idxMapper.selectById(2L)).thenReturn(idx2);
    }

    @Test
    void 显式索引映射_数据源取映射所属() {
        LogSourceResolver.LogSource src = resolver.resolve(null, 2L);
        assertEquals(3L, src.datasourceId());
        assertEquals(2L, src.indexConfigId());
    }

    @Test
    void 索引映射与数据源不匹配_拒绝() {
        assertThrows(BizException.class, () -> resolver.resolve(1L, 2L));
    }

    @Test
    void 都不传_用默认数据源而不是1号() {
        when(dsMapper.selectOne(any())).thenReturn(ds3);
        when(idxMapper.selectOne(any())).thenReturn(idx2);
        LogSourceResolver.LogSource src = resolver.resolve(null, null);
        assertEquals(3L, src.datasourceId());
        assertEquals(2L, src.indexConfigId());
    }

    @Test
    void 未配置默认数据源_明确报错() {
        when(dsMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> resolver.resolve(null, null));
    }

    @Test
    void 数据源下没有启用的索引映射_明确报错() {
        when(idxMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> resolver.resolve(1L, null));
    }

    @Test
    void listEnabled_每个数据源的每个映射都列出() {
        when(dsMapper.selectList(any())).thenReturn(List.of(ds1, ds3));
        when(idxMapper.selectList(any())).thenReturn(List.of(idx1)).thenReturn(List.of(idx2));
        List<LogSourceResolver.LogSource> all = resolver.listEnabled();
        assertEquals(2, all.size());
        assertEquals(1L, all.get(0).datasourceId());
        assertEquals(3L, all.get(1).datasourceId());
    }

    private static EsDatasource ds(Long id, int isDefault) {
        EsDatasource d = new EsDatasource();
        d.setId(id);
        d.setStatus(1);
        d.setIsDefault(isDefault);
        return d;
    }

    private static EsIndexConfig idx(Long id, Long dsId, String pattern) {
        EsIndexConfig c = new EsIndexConfig();
        c.setId(id);
        c.setDatasourceId(dsId);
        c.setIndexPattern(pattern);
        c.setEnabled(1);
        return c;
    }
}
