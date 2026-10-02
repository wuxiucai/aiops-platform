# T2 - E3 Drain 参数调优（真实 sweep 数据）

## 输入 （真实）

SELECT sample_log FROM log_template
WHERE sample_log IS NOT NULL
ORDER BY total_count DESC
LIMIT 25;

训练次数： 每参数组 5 次
总输入： 25 × 5 = 125 lines

## 输出 （真实， 从 surefire xml 提取）

depth=3, simTh=0.4 → templates=24, compression=0.80
depth=3, simTh=0.5 → templates=24, compression=0.80
depth=3, simTh=0.6 → templates=24, compression=0.80
depth=4, simTh=0.4 → templates=24, compression=0.80
depth=4, simTh=0.5 → templates=24, compression=0.80
depth=4, simTh=0.6 → templates=24, compression=0.80
depth=5, simTh=0.4 → templates=24, compression=0.80
depth=5, simTh=0.5 → templates=24, compression=0.80
depth=5, simTh=0.6 → templates=24, compression=0.80

## 观察

1. 各参数组结果完全相同（template_count=24, compression_rate=80.0 %)
2. 原因： 样本端 （25 行） 的搭配规则一致 (MyBatis SQL 日志 + 演示故障注入）,tree路径与 sim 阈值非敏感。
3. 建议： `depth=4, simTh=0.4`（平台默认值， Drain 算法任务书 §7.2)
4. 若需更细粒度优化会出现需指数token多样化 更大 N + 更多 token 多样化样本）

## 代码与测试

backend/src/test/java/com/aiops/module/log/drain/DrainParamSweepTest.java

mvn test -Dtest=DrainParamSweepTest -q
Tests run: 1, Failures: 0

## 局限

样本 25 行且 MyBatis SQL 日志 多， 不包括复杂年度univ的设计 range 统计 spend 更丰/更高样本量 (N>=200) 会会明显 快些差异.
