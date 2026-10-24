using System.Globalization;
using BetterRNP.PluginSdk.Abstractions;

namespace BetterRNP.RandomnessAnalyzerPlugin.Analysis;

public sealed record PopulationCategory(
    string Label,
    long Weight,
    long? LowerBound = null,
    long? UpperBound = null);

public sealed record PopulationDefinition(
    string SchemeName,
    string ModeLabel,
    bool IsBinned,
    IReadOnlyList<PopulationCategory> Categories)
{
    public long TotalWeight => Categories.Sum(category => category.Weight);
}

public sealed record CategoryResult(
    string Outcome,
    long Observed,
    double Expected,
    double ObservedPercent,
    double ExpectedPercent,
    double Deviation);

public sealed record RandomnessReport(
    PopulationDefinition Population,
    int SampleCount,
    double UniformityChiSquare,
    int UniformityDegreesOfFreedom,
    double UniformityPValue,
    double? IndependenceChiSquare,
    int? IndependenceDegreesOfFreedom,
    double? IndependencePValue,
    double MinimumExpectedCount,
    bool FrequencyTestReliable,
    bool IndependenceTestReliable,
    bool HasSignificantDeviation,
    string Conclusion,
    IReadOnlyList<CategoryResult> Categories);

public static class RandomnessAnalyzer
{
    public const int ExactNumberLimit = 500;
    public const int NumberBucketCount = 20;
    public const int MaxIndependenceCategories = 64;
    public const double SignificanceLevel = 0.01;

    private const double GammaEpsilon = 1e-12;
    private const double GammaMinimum = 1e-300;

    public static PopulationDefinition CreatePopulation(
        IPluginModeContext context,
        string? modeId = null)
    {
        var effectiveMode = modeId is "name_list" or "number" or "seat"
            ? modeId
            : context.SchemeType;
        return effectiveMode switch
        {
            "number" => CreateNumberPopulation(context),
            "seat" => CreateSeatPopulation(context),
            _ => CreateNamePopulation(context)
        };
    }

    public static RandomnessReport Analyze(
        PopulationDefinition population,
        int sampleCount,
        Random? random = null,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        if (sampleCount <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(sampleCount), "抽样次数必须大于 0。");
        }
        if (population.Categories.Count == 0 || population.TotalWeight <= 0)
        {
            throw new InvalidOperationException("当前方案没有可分析的候选结果。");
        }

        var source = random ?? Random.Shared;
        var prefixWeights = BuildPrefixWeights(population.Categories);
        return SampleAndAnalyze(
            population,
            sampleCount,
            () => FindCategory(prefixWeights, source.NextInt64(population.TotalWeight)),
            progress,
            cancellationToken);
    }

    public static RandomnessReport AnalyzeSupplier(
        PopulationDefinition population,
        Func<string> candidateSupplier,
        int sampleCount,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(candidateSupplier);
        if (sampleCount <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(sampleCount), "抽样次数必须大于 0。");
        }
        if (population.Categories.Count == 0)
        {
            throw new InvalidOperationException("当前抽取模式没有可分析的候选结果。");
        }

        var exactLookup = population.IsBinned
            ? null
            : population.Categories
                .Select((category, index) => (category.Label, index))
                .ToDictionary(pair => pair.Label, pair => pair.index, StringComparer.Ordinal);
        return SampleAndAnalyze(
            population,
            sampleCount,
            () =>
            {
                var result = candidateSupplier();
                if (string.IsNullOrWhiteSpace(result))
                {
                    throw new InvalidOperationException("当前抽取模式返回了空结果。");
                }
                if (exactLookup is not null)
                {
                    if (!exactLookup.TryGetValue(result, out var exactIndex))
                    {
                        throw new InvalidOperationException($"抽取结果无法映射到当前候选集合：{result}");
                    }
                    return exactIndex;
                }
                if (!long.TryParse(result, NumberStyles.Integer, CultureInfo.InvariantCulture, out var number))
                {
                    throw new InvalidOperationException($"分箱数字模式返回了非数字结果：{result}");
                }
                return FindBinnedCategory(population.Categories, number);
            },
            progress,
            cancellationToken);
    }

    public static RandomnessReport AnalyzeCounts(
        PopulationDefinition population,
        IReadOnlyList<long> counts,
        long[,]? transitions,
        long sampleCount)
    {
        if (counts.Count != population.Categories.Count)
        {
            throw new ArgumentException("观察次数与候选类别数量不一致。", nameof(counts));
        }

        var expected = population.Categories
            .Select(category => sampleCount * category.Weight / (double)population.TotalWeight)
            .ToArray();
        var minimumExpected = expected.Min();
        var uniformity = ChiSquare(
            counts.Select((observed, index) =>
            {
                var difference = observed - expected[index];
                return difference * difference / expected[index];
            }).Sum(),
            counts.Count - 1);

        double? independenceChi = null;
        int? independenceDegrees = null;
        double? independenceP = null;
        var independenceReliable = false;
        if (transitions is not null
            && counts.Count > 1
            && counts.Count <= MaxIndependenceCategories)
        {
            double statistic = 0;
            var transitionExpectedMinimum = double.MaxValue;
            for (var from = 0; from < counts.Count; from++)
            {
                var rowTotal = Enumerable.Range(0, counts.Count)
                    .Sum(to => transitions[from, to]);
                for (var to = 0; to < counts.Count; to++)
                {
                    var cellExpected = rowTotal
                        * population.Categories[to].Weight
                        / (double)population.TotalWeight;
                    if (cellExpected <= 0)
                    {
                        continue;
                    }
                    transitionExpectedMinimum = Math.Min(transitionExpectedMinimum, cellExpected);
                    var difference = transitions[from, to] - cellExpected;
                    statistic += difference * difference / cellExpected;
                }
            }

            independenceDegrees = counts.Count * (counts.Count - 1);
            independenceChi = statistic;
            independenceP = ChiSquare(statistic, independenceDegrees.Value).PValue;
            independenceReliable = transitionExpectedMinimum >= 5;
        }

        var frequencyReliable = minimumExpected >= 5;
        var hasSignificantDeviation = uniformity.PValue < SignificanceLevel
            || independenceP is < SignificanceLevel;
        var conclusion = BuildConclusion(
            counts.Count,
            hasSignificantDeviation,
            frequencyReliable,
            independenceReliable,
            independenceP is not null);

        var categoryResults = new List<CategoryResult>(counts.Count);
        for (var index = 0; index < counts.Count; index++)
        {
            categoryResults.Add(new CategoryResult(
                population.Categories[index].Label,
                counts[index],
                expected[index],
                counts[index] / (double)sampleCount,
                population.Categories[index].Weight / (double)population.TotalWeight,
                counts[index] - expected[index]));
        }

        return new RandomnessReport(
            population,
            checked((int)sampleCount),
            uniformity.Statistic,
            counts.Count - 1,
            uniformity.PValue,
            independenceChi,
            independenceDegrees,
            independenceP,
            minimumExpected,
            frequencyReliable,
            independenceReliable,
            hasSignificantDeviation,
            conclusion,
            categoryResults);
    }

    public static double ChiSquarePValue(double statistic, int degreesOfFreedom)
    {
        if (statistic <= 0 || degreesOfFreedom <= 0)
        {
            return 1;
        }
        return Math.Clamp(
            RegularizedGammaQ(degreesOfFreedom / 2.0, statistic / 2.0),
            0,
            1);
    }

    private static PopulationDefinition CreateNamePopulation(IPluginModeContext context)
    {
        var categories = context.Names
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .GroupBy(name => name, StringComparer.Ordinal)
            .Select(group => new PopulationCategory(group.Key, group.LongCount()))
            .ToList();
        return new PopulationDefinition(context.SchemeName, "名字列表模式", false, categories);
    }

    private static PopulationDefinition CreateSeatPopulation(IPluginModeContext context)
    {
        var categories = context.Seats
            .GroupBy(seat => seat)
            .Select(group => new PopulationCategory(
                $"({group.Key.Row}, {group.Key.Column})",
                group.LongCount()))
            .ToList();
        return new PopulationDefinition(context.SchemeName, "座位模式", false, categories);
    }

    private static PopulationDefinition CreateNumberPopulation(IPluginModeContext context)
    {
        if (context.NumberRange is not { } range || range.Max < range.Min)
        {
            return new PopulationDefinition(context.SchemeName, "数字模式", false, Array.Empty<PopulationCategory>());
        }

        var total = (long)range.Max - range.Min + 1;
        if (total <= ExactNumberLimit)
        {
            var categories = Enumerable.Range(0, checked((int)total))
                .Select(offset => new PopulationCategory(
                    ((long)range.Min + offset).ToString(CultureInfo.InvariantCulture),
                    1,
                    (long)range.Min + offset,
                    (long)range.Min + offset))
                .ToList();
            return new PopulationDefinition(context.SchemeName, "数字模式", false, categories);
        }

        var bucketCategories = new List<PopulationCategory>(NumberBucketCount);
        for (var bucket = 0; bucket < NumberBucketCount; bucket++)
        {
            var startOffset = total * bucket / NumberBucketCount;
            var endOffset = total * (bucket + 1) / NumberBucketCount;
            if (endOffset <= startOffset)
            {
                continue;
            }

            var start = (long)range.Min + startOffset;
            var end = (long)range.Min + endOffset - 1;
            var label = start == end
                ? start.ToString(CultureInfo.InvariantCulture)
                : $"{start.ToString(CultureInfo.InvariantCulture)}–{end.ToString(CultureInfo.InvariantCulture)}";
            bucketCategories.Add(new PopulationCategory(
                label,
                endOffset - startOffset,
                start,
                end));
        }
        return new PopulationDefinition(context.SchemeName, "数字模式", true, bucketCategories);
    }

    private static long[] BuildPrefixWeights(IReadOnlyList<PopulationCategory> categories)
    {
        var prefixes = new long[categories.Count];
        long total = 0;
        for (var index = 0; index < categories.Count; index++)
        {
            total += categories[index].Weight;
            prefixes[index] = total;
        }
        return prefixes;
    }

    private static int FindCategory(IReadOnlyList<long> prefixWeights, long draw)
    {
        var low = 0;
        var high = prefixWeights.Count - 1;
        while (low < high)
        {
            var middle = low + (high - low) / 2;
            if (draw < prefixWeights[middle])
            {
                high = middle;
            }
            else
            {
                low = middle + 1;
            }
        }
        return low;
    }

    private static RandomnessReport SampleAndAnalyze(
        PopulationDefinition population,
        int sampleCount,
        Func<int> nextCategory,
        IProgress<double>? progress,
        CancellationToken cancellationToken)
    {
        var counts = new long[population.Categories.Count];
        long[,]? transitions = population.Categories.Count is > 1 and <= MaxIndependenceCategories
            ? new long[population.Categories.Count, population.Categories.Count]
            : null;
        var previous = -1;
        var progressStep = Math.Max(1000, sampleCount / 100);

        for (var sample = 0; sample < sampleCount; sample++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var category = nextCategory();
            counts[category]++;
            if (transitions is not null && previous >= 0)
            {
                transitions[previous, category]++;
            }
            previous = category;

            if (progress is not null && ((sample + 1) % progressStep == 0 || sample + 1 == sampleCount))
            {
                progress.Report((sample + 1) / (double)sampleCount);
            }
        }

        return AnalyzeCounts(population, counts, transitions, sampleCount);
    }

    private static int FindBinnedCategory(
        IReadOnlyList<PopulationCategory> categories,
        long value)
    {
        var low = 0;
        var high = categories.Count - 1;
        while (low <= high)
        {
            var middle = low + (high - low) / 2;
            if (value < categories[middle].LowerBound)
            {
                high = middle - 1;
            }
            else if (value > categories[middle].UpperBound)
            {
                low = middle + 1;
            }
            else
            {
                return middle;
            }
        }
        throw new InvalidOperationException($"数字 {value} 不在当前分析范围内。");
    }

    private static (double Statistic, double PValue) ChiSquare(double statistic, int degrees)
        => (statistic, ChiSquarePValue(statistic, degrees));

    private static string BuildConclusion(
        int categoryCount,
        bool significant,
        bool frequencyReliable,
        bool independenceReliable,
        bool independencePerformed)
    {
        if (categoryCount < 2)
        {
            return "候选结果只有一种，无法进行随机性判断。";
        }
        if (significant)
        {
            return "发现统计显著偏差（α = 0.01）。建议增加样本量后复测；若仍显著，应检查候选列表、范围或插件逻辑。";
        }
        if (!frequencyReliable)
        {
            return "未发现显著偏差，但部分类别期望次数低于 5，当前样本量不足以作出可靠结论。";
        }
        if (independencePerformed && !independenceReliable)
        {
            return "未发现频率偏差，但前后依赖检验的期望次数偏小，建议增加样本量。";
        }
        return "未发现统计显著偏差。该结果表示当前样本没有明显异常，并不构成“绝对随机”的数学证明。";
    }

    private static double RegularizedGammaQ(double a, double x)
    {
        if (x < 0 || a <= 0)
        {
            return double.NaN;
        }
        if (x == 0)
        {
            return 1;
        }
        if (x < a + 1)
        {
            return 1 - RegularizedGammaSeries(a, x);
        }
        return RegularizedGammaContinuedFraction(a, x);
    }

    private static double RegularizedGammaSeries(double a, double x)
    {
        var sum = 1.0 / a;
        var term = sum;
        var currentA = a;
        for (var iteration = 1; iteration <= 1000; iteration++)
        {
            currentA++;
            term *= x / currentA;
            sum += term;
            if (Math.Abs(term) < Math.Abs(sum) * GammaEpsilon)
            {
                break;
            }
        }
        return sum * Math.Exp(-x + a * Math.Log(x) - LogGamma(a));
    }

    private static double RegularizedGammaContinuedFraction(double a, double x)
    {
        var b = x + 1 - a;
        var c = 1 / GammaMinimum;
        var d = 1 / Math.Max(b, GammaMinimum);
        var h = d;
        for (var iteration = 1; iteration <= 1000; iteration++)
        {
            var an = -iteration * (iteration - a);
            b += 2;
            d = an * d + b;
            if (Math.Abs(d) < GammaMinimum)
            {
                d = GammaMinimum;
            }
            c = b + an / c;
            if (Math.Abs(c) < GammaMinimum)
            {
                c = GammaMinimum;
            }
            d = 1 / d;
            var delta = d * c;
            h *= delta;
            if (Math.Abs(delta - 1) < GammaEpsilon)
            {
                break;
            }
        }
        return Math.Exp(-x + a * Math.Log(x) - LogGamma(a)) * h;
    }

    private static double LogGamma(double value)
    {
        double[] coefficients =
        {
            76.18009172947146,
            -86.50532032941677,
            24.01409824083091,
            -1.231739572450155,
            0.1208650973866179e-2,
            -0.5395239384953e-5
        };
        var x = value;
        var y = value;
        var temporary = x + 5.5;
        temporary -= (x + 0.5) * Math.Log(temporary);
        var series = 1.000000000190015;
        foreach (var coefficient in coefficients)
        {
            y += 1;
            series += coefficient / y;
        }
        return -temporary + Math.Log(2.5066282746310005 * series / x);
    }
}
