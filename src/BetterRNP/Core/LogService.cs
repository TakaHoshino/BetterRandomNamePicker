using System.Text;

namespace BetterRNP.Core;

public sealed class LogService
{
    private readonly object _sync = new();
    private readonly string _logFile;

    public LogService(AppPaths paths)
    {
        _logFile = paths.LogFile;
        Directory.CreateDirectory(Path.GetDirectoryName(_logFile)!);
    }

    public string LogFile => _logFile;

    public void Write(string detail, string operation)
    {
        try
        {
            lock (_sync)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_logFile)!);
                var line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] 【{detail}】【{operation}】{Environment.NewLine}";
                File.AppendAllText(_logFile, line, new UTF8Encoding(false));
            }
        }
        catch
        {
            // Logging must never break the picker.
        }
    }

    public string ReadAll()
    {
        try
        {
            return File.Exists(_logFile) ? File.ReadAllText(_logFile, Encoding.UTF8) : "暂无日志记录";
        }
        catch (Exception ex)
        {
            return $"读取日志失败：{ex.Message}";
        }
    }
}
