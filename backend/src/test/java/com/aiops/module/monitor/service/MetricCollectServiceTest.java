package com.aiops.module.monitor.service;

import com.aiops.datasource.metric.ActuatorCollector;
import com.aiops.datasource.metric.MetricCollector;
import com.aiops.datasource.metric.OshiCollector;
import com.aiops.datasource.metric.PrometheusCollector;
import com.aiops.module.monitor.entity.CollectTask;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.CollectTaskMapper;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorAgentMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * host 目标的 OSHI 采集与 agent 推送互斥：agent 正在推送时平台不再按该主机 id 写入本机采样。
 */
class MetricCollectServiceTest {

    private CollectTaskMapper taskMapper;
    private MetricDataMapper dataMapper;
    private MonitorAgentMapper agentMapper;
    private OshiCollector oshi;
    private MetricCollectService service;
    private final CollectTask task = new CollectTask();

    @BeforeEach
    void setUp() {
        taskMapper = mock(CollectTaskMapper.class);
        MonitorTargetMapper targetMapper = mock(MonitorTargetMapper.class);
        dataMapper = mock(MetricDataMapper.class);
        agentMapper = mock(MonitorAgentMapper.class);
        oshi = mock(OshiCollector.class);
        service = new MetricCollectService(taskMapper, targetMapper, dataMapper, agentMapper, oshi,
                mock(ActuatorCollector.class), mock(PrometheusCollector.class));

        MonitorTarget host = new MonitorTarget();
        host.setId(7L);
        host.setTargetType("host");
        host.setStatus(1);
        when(targetMapper.selectById(7L)).thenReturn(host);
        when(oshi.supports("host")).thenReturn(true);
        when(oshi.collect(anyLong(), anyList())).thenReturn(List.of(
                new MetricCollector.MetricPoint("cpu.usage", 2.46),
                new MetricCollector.MetricPoint("mem.usage", 81.31)));

        task.setId(4L);
        task.setTargetId(7L);
        task.setMetricKeys("[\"cpu.usage\",\"mem.usage\"]");
        task.setFailCount(3);
    }

    @Test
    void agent正在推送_不写平台本机采样_也不计失败() {
        when(agentMapper.selectCount(any())).thenReturn(1L);

        assertEquals(0, service.runTask(task));

        verify(oshi, never()).collect(anyLong(), anyList());
        verify(dataMapper, never()).insert(any(com.aiops.module.monitor.entity.MetricData.class));
        CollectTask upd = capturedTaskUpdate();
        assertNotNull(upd.getLastRunTime(), "跳过也要记 last_run_time，否则 1s 节拍每秒重新提交");
        assertEquals(0, upd.getFailCount());
    }

    @Test
    void 没有正在推送的agent_照常采集平台本机() {
        when(agentMapper.selectCount(any())).thenReturn(0L);

        assertEquals(2, service.runTask(task));

        verify(dataMapper, times(2)).insert(any(com.aiops.module.monitor.entity.MetricData.class));
        assertEquals(0, capturedTaskUpdate().getFailCount());
    }

    private CollectTask capturedTaskUpdate() {
        ArgumentCaptor<CollectTask> cap = ArgumentCaptor.forClass(CollectTask.class);
        verify(taskMapper).updateById(cap.capture());
        return cap.getValue();
    }
}
