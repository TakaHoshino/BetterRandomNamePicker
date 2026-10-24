# BetterRNP 随机性分析插件

这个插件用于检查 BetterRNP 当前方案的抽取结果是否表现出明显的不均匀或前后关联。

## 使用方法

1. 构建解决方案。
2. 把输出目录中的 `com.betterrnp.randomness-analyzer.rnpp` 放到主程序 `plugins/` 目录。
3. 启动 BetterRNP，选择要分析的方案。
4. 打开“插件 → 分析抽取随机性…”。
5. 选择抽样次数并开始分析。

构建输出：

```text
examples\BetterRNP.RandomnessAnalyzerPlugin\bin\Release\net8.0-windows\com.betterrnp.randomness-analyzer.rnpp
```

## 分析内容

- **真实候选生成器**：直接调用宿主当前选中模式提供的候选函数，包括内置模式和插件自定义模式。
- **频率检验**：用 Pearson 卡方检验比较各类结果的实际次数与均匀抽取时的期望次数。
- **前后依赖检验**：检查上一次结果是否会影响下一次结果。
- **大范围数字**：候选值超过 500 个时自动归并为 20 个等宽区间，避免每个数字的期望次数过少。
- **可靠性提示**：期望次数低于 5 时会提示当前样本量不足。

默认显著性水平为 `0.01`。发现显著偏差时，建议增加样本量后复测。

## 结果解释

“未发现统计显著偏差”只表示当前样本中没有足够证据证明存在偏差，并不等于数学上证明了绝对随机。统计检验仍可能发生第一类错误和第二类错误。

## 宿主接口

插件优先通过新增的 `ICurrentPickerPluginContext` 获取当前模式和目标候选生成器；
如果宿主较旧，则回退到 `ISchemeCommandPluginContext` 的方案快照。
原有 `IPluginContext` 接口保持不变，旧插件无需修改。
