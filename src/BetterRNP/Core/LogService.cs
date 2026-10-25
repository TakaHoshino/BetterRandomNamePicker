using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace BetterRNP.Core;

/// <summary>日志级别。</summary>
public enum LogLevel
{
    Info,
    Warn,
    Error
}

/// <summary>一条已解析的日志记录。</summary>
public sealed record LogEntry(DateTime Timestamp, LogLevel Level, string Operation, string Detail)
{
    public string TimeText => Timestamp == DateTime.MinValue
        ? string.Empty
        : Timestamp.ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture);

    public string LevelText => Level switch
    {
        LogLevel.Warn => LocalizationService.Get(UiText.WarningLevel),
        LogLevel.Error => LocalizationService.Get(UiText.ErrorLevel),
        _ => LocalizationService.Get(UiText.InformationLevel)
    };
}

/// <summary>
/// 操作日志：按级别写入 <c>log\Modifylog.txt</c>，超过上限自动滚动，
/// 并提供结构化读取供日志查看器使用。
/// </summary>
public sealed class LogService
{
    /// <summary>单个日志文件的默认大小上限（1 MiB）。</summary>
    public const long DefaultMaxFileBytes = 1024 * 1024;

    /// <summary>默认保留的历史日志数量。</summary>
    public const int DefaultMaxArchives = 5;

    private const int MaxDetailLength = 4000;

    private static readonly UTF8Encoding Utf8NoBom = new(false);

    private static readonly Regex StructuredLine = new(
        @"^\[(?<time>[^\]]+)\]\s*\[(?<level>INFO|WARN|ERROR)\s*\]\s*【(?<operation>[^】]*)】(?<detail>.*)$",
        RegexOptions.Compiled);

    // 兼容旧格式：[时间] 【详情】【操作】。
    private static readonly Regex LegacyLine = new(
        @"^\[(?<time>[^\]]+)\]\s*【(?<detail>[^】]*)】【(?<operation>[^】]*)】$",
        RegexOptions.Compiled);

    private static readonly string[] TimeFormats =
    {
        "yyyy-MM-dd HH:mm:ss.fff",
        "yyyy-MM-dd HH:mm:ss"
    };

    private readonly object _sync = new();
    private readonly string _logFile;

    public LogService(AppPaths paths)
    {
        _logFile = paths.LogFile;
        Directory.CreateDirectory(Path.GetDirectoryName(_logFile)!);
    }

    /// <summary>写入一条新日志后触发，参数为已写入的记录。</summary>
    public event EventHandler<LogEntry>? EntryWritten;

    public string LogFile => _logFile;

    /// <summary>当前日志文件超过该大小时滚动。</summary>
    public long MaxFileBytes { get; init; } = DefaultMaxFileBytes;

    /// <summary>保留的历史日志数量，<c>0</c> 表示不滚动。</summary>
    public int MaxArchives { get; init; } = DefaultMaxArchives;

    public void Write(string detail, string operation) => Write(LogLevel.Info, detail, operation);

    public void Write(LogLevel level, string detail, string operation)
    {
        var entry = new LogEntry(DateTime.Now, level, Normalize(operation, 200), Normalize(detail, MaxDetailLength));
        try
        {
            lock (_sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_logFile)!);
                RotateIfNeeded();
                File.AppendAllText(_logFile, Format(entry), Utf8NoBom);
            }
        }
        catch
        {
            // 记录日志失败不能影响抽取流程。
            return;
        }

        try
        {
            EntryWritten?.Invoke(this, entry);
        }
        catch
        {
            // 订阅方异常同样不能影响主流程。
        }
    }

    /// <summary>记录完整异常信息（类型、消息和堆栈）。</summary>
    public void WriteException(string operation, Exception exception)
    {
        var detail = new StringBuilder()
            .Append(exception.GetType().Name)
            .Append(": ")
            .Append(exception.Message);
        if (!string.IsNullOrWhiteSpace(exception.StackTrace))
        {
            detail.Append(Environment.NewLine).Append(exception.StackTrace);
        }
        Write(LogLevel.Error, detail.ToString(), operation);
    }

    /// <summary>按写入顺序读取全部可解析记录；<paramref name="maxCount"/> 只保留最后若干条。</summary>
    public IReadOnlyList<LogEntry> ReadEntries(int maxCount = 0)
    {
        List<LogEntry> entries;
        try
        {
            if (!File.Exists(_logFile))
            {
                return Array.Empty<LogEntry>();
            }

            entries = new List<LogEntry>();
            using var stream = new FileStream(
                _logFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
            while (reader.ReadLine() is { } line)
            {
                if (TryParse(line, out var entry))
                {
                    entries.Add(entry);
                }
            }
        }
        catch
        {
            return Array.Empty<LogEntry>();
        }

        if (maxCount > 0 && entries.Count > maxCount)
        {
            entries = entries.Skip(entries.Count - maxCount).ToList();
        }
        return entries;
    }

    public string ReadAll()
    {
        try
        {
            return File.Exists(_logFile)
                ? File.ReadAllText(_logFile, Encoding.UTF8)
                : LocalizationService.Get(UiText.NoLogEntries);
        }
        catch (Exception ex)
        {
            return LocalizationService.Format(UiText.ReadLogFailureFormat, ex.Message);
        }
    }

    /// <summary>当前日志文件大小；不存在时为 0。</summary>
    public long GetFileSize()
    {
        try
        {
            var info = new FileInfo(_logFile);
            return info.Exists ? info.Length : 0;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>删除当前日志文件；历史归档保留。</summary>
    public bool Clear()
    {
        try
        {
            lock (_sync)
            {
                if (File.Exists(_logFile))
                {
                    File.Delete(_logFile);
                }
            }
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>把一条记录格式化为日志文件中的一行。</summary>
    public static string Format(LogEntry entry)
    {
        var tag = entry.Level switch
        {
            LogLevel.Warn => "WARN ",
            LogLevel.Error => "ERROR",
            _ => "INFO "
        };
        return $"[{entry.TimeText}] [{tag}] 【{entry.Operation}】{entry.Detail}{Environment.NewLine}";
    }

    private static string Normalize(string? value, int maxLength)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }

        // 保证一条记录只占一行，避免多行异常堆栈破坏日志解析。
        var text = value
            .Replace("\r\n", " ⏎ ")
            .Replace('\r', ' ')
            .Replace('\n', ' ')
            .Trim();
        return text.Length <= maxLength ? text : text[..maxLength] + "…";
    }

    private static bool TryParse(string line, out LogEntry entry)
    {
        if (string.IsNullOrWhiteSpace(line))
        {
            entry = default!;
            return false;
        }

        var match = StructuredLine.Match(line);
        if (match.Success)
        {
            entry = new LogEntry(
                ParseTime(match.Groups["time"].Value),
                ParseLevel(match.Groups["level"].Value),
                match.Groups["operation"].Value,
                match.Groups["detail"].Value);
            return true;
        }

        match = LegacyLine.Match(line);
        if (match.Success)
        {
            entry = new LogEntry(
                ParseTime(match.Groups["time"].Value),
                LogLevel.Info,
                match.Groups["operation"].Value,
                match.Groups["detail"].Value);
            return true;
        }

        // 无法识别的行也保留在查看器中，避免隐藏历史日志。
        entry = new LogEntry(DateTime.MinValue, LogLevel.Info, string.Empty, line.Trim());
        return true;
    }

    private static LogLevel ParseLevel(string value) => value switch
    {
        "WARN " or "WARN" or "WARNING" => LogLevel.Warn,
        "ERROR" => LogLevel.Error,
        _ => LogLevel.Info
    };

    private static DateTime ParseTime(string value)
        => DateTime.TryParseExact(
                value,
                TimeFormats,
                CultureInfo.InvariantCulture,
                DateTimeStyles.None,
                out var timestamp)
            ? timestamp
            : DateTime.MinValue;

    private void RotateIfNeeded()
    {
        if (MaxArchives <= 0)
        {
            return;
        }

        var info = new FileInfo(_logFile);
        if (!info.Exists || info.Length < MaxFileBytes)
        {
            return;
        }

        var directory = info.DirectoryName!;
        var oldest = ArchivePath(directory, MaxArchives);
        if (File.Exists(oldest))
        {
            File.Delete(oldest);
        }
        for (var index = MaxArchives - 1; index >= 1; index--)
        {
            var source = ArchivePath(directory, index);
            if (File.Exists(source))
            {
                File.Move(source, ArchivePath(directory, index + 1), overwrite: true);
            }
        }
        File.Move(_logFile, ArchivePath(directory, 1), overwrite: true);
    }

    private static string ArchivePath(string directory, int index)
        => Path.Combine(directory, $"Modifylog.{index}.txt");
}
