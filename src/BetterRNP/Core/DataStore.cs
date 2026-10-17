using System.Text;

namespace BetterRNP.Core;

public enum DataKind
{
    Names,
    Number,
    Seat
}

public sealed class DataCorruptedException : Exception
{
    public DataCorruptedException(string message) : base(message) { }
}

/// <summary>
/// AES-GCM data file storage with three copies: data/schemes, data/backup and a disguised
/// copy under log/. The on-disk format is wire-compatible with the original Java application.
/// </summary>
public sealed class DataStore
{
    private sealed record FileData(string Encrypted, string Hash);

    private readonly AppPaths _paths;
    private readonly EncryptionService _encryption;
    private readonly LogService _log;

    public DataStore(AppPaths paths, EncryptionService encryption, LogService log)
    {
        _paths = paths;
        _encryption = encryption;
        _log = log;
        _paths.EnsureDirectories();
    }

    public void SaveNames(string schemeName, IEnumerable<string> names)
    {
        var content = new StringBuilder();
        foreach (var name in names)
        {
            content.Append(name).Append('\n');
        }
        Save(schemeName, DataKind.Names, content.ToString());
    }

    public List<string> LoadNames(string schemeName)
    {
        var content = Load(schemeName, DataKind.Names);
        if (content is null)
        {
            return new List<string>();
        }
        return content.Replace("\r\n", "\n").Replace('\r', '\n')
            .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .ToList();
    }

    public void SaveNumberRange(string schemeName, string content)
        => Save(schemeName, DataKind.Number, content);

    public string? LoadNumberRange(string schemeName)
        => Load(schemeName, DataKind.Number);

    public void SaveSeatConfig(string schemeName, string content)
        => Save(schemeName, DataKind.Seat, content);

    public string? LoadSeatConfig(string schemeName)
        => Load(schemeName, DataKind.Seat);

    public void DeleteAllCopies(string schemeName, DataKind kind)
    {
        var clean = CleanSchemeName(schemeName);
        var fileName = FileName(clean, kind);
        TryDelete(Path.Combine(_paths.SchemesDir, fileName));
        TryDelete(Path.Combine(_paths.BackupDir, clean + "_" + fileName));
        TryDelete(Path.Combine(_paths.LogDir, DisguiseFileName(clean, fileName)));
    }

    private void Save(string schemeName, DataKind kind, string plainText)
    {
        var clean = CleanSchemeName(schemeName);
        var fileName = FileName(clean, kind);
        var encrypted = _encryption.EncryptData(plainText, schemeName);
        var hash = _encryption.CalculateHash(plainText);

        WriteFile(Path.Combine(_paths.SchemesDir, fileName), encrypted, hash);
        WriteFile(Path.Combine(_paths.BackupDir, clean + "_" + fileName), encrypted, hash);
        WriteFile(Path.Combine(_paths.LogDir, DisguiseFileName(clean, fileName)), encrypted, hash);
        _log.Write($"保存方案[{schemeName}] {kind}", "SAVE_WITH_ENCRYPTION");
    }

    private string? Load(string schemeName, DataKind kind)
    {
        var clean = CleanSchemeName(schemeName);
        var fileName = FileName(clean, kind);
        var original = Path.Combine(_paths.SchemesDir, fileName);

        var data = ReadFile(original, schemeName);
        if (data is not null && TryDecrypt(data, schemeName, out var plain) && HashMatches(plain, data.Hash))
        {
            SyncCopiesIfPossible(schemeName, fileName, data);
            return plain;
        }

        var recovered = Recover(schemeName, fileName);
        if (recovered is null)
        {
            if (File.Exists(original) && new FileInfo(original).Length > 0)
            {
                _log.Write($"方案[{schemeName}]数据损坏且无法恢复", "RECOVERY_FAILED");
                throw new DataCorruptedException($"方案[{schemeName}]数据损坏且无法恢复");
            }
            return null;
        }

        if (TryDecrypt(recovered.Value.Data, schemeName, out var recoveredPlain))
        {
            _log.Write($"方案[{schemeName}]已从{recovered.Value.Source}恢复并同步", "DATA_RECOVERED_AND_SYNCED");
            WriteFile(original, recovered.Value.Data.Encrypted, recovered.Value.Data.Hash);
            WriteFile(Path.Combine(_paths.BackupDir, clean + "_" + fileName),
                recovered.Value.Data.Encrypted, recovered.Value.Data.Hash);
            WriteFile(Path.Combine(_paths.LogDir, DisguiseFileName(clean, fileName)),
                recovered.Value.Data.Encrypted, recovered.Value.Data.Hash);
            return recoveredPlain;
        }

        return null;
    }

    private (FileData Data, string Source)? Recover(string schemeName, string fileName)
    {
        var clean = CleanSchemeName(schemeName);
        var candidates = new (string Path, string Source)[]
        {
            (Path.Combine(_paths.LogDir, DisguiseFileName(clean, fileName)), "log"),
            (Path.Combine(_paths.BackupDir, clean + "_" + fileName), "backup"),
            (Path.Combine(_paths.SchemesDir, fileName), "data")
        };

        foreach (var candidate in candidates)
        {
            var data = ReadFile(candidate.Path, schemeName);
            if (data is not null && TryDecrypt(data, schemeName, out var plain)
                && HashMatches(plain, data.Hash))
            {
                return (data, candidate.Source);
            }
        }
        return null;
    }

    private void SyncCopiesIfPossible(string schemeName, string fileName, FileData data)
    {
        var clean = CleanSchemeName(schemeName);
        var copies = new[]
        {
            Path.Combine(_paths.BackupDir, clean + "_" + fileName),
            Path.Combine(_paths.LogDir, DisguiseFileName(clean, fileName))
        };

        foreach (var copy in copies)
        {
            var existing = ReadFile(copy, schemeName);
            if (existing is null || existing.Hash != data.Hash)
            {
                WriteFile(copy, data.Encrypted, data.Hash);
            }
        }
    }

    private bool TryDecrypt(FileData data, string schemeName, out string plain)
    {
        plain = string.Empty;
        try
        {
            plain = _encryption.DecryptData(data.Encrypted, schemeName);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private bool HashMatches(string plain, string expected)
        => string.Equals(_encryption.CalculateHash(plain), expected, StringComparison.Ordinal);

    private static FileData? ReadFile(string path, string schemeName)
    {
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            var lines = File.ReadAllLines(path, Encoding.UTF8);
            if (lines.Length < 3 || lines[0].Trim() != "#ENCRYPTED_DATA")
            {
                return null;
            }

            var encrypted = new StringBuilder();
            var hash = new StringBuilder();
            var readingHash = false;
            for (var i = 1; i < lines.Length; i++)
            {
                if (lines[i].Trim() == "#HASH")
                {
                    readingHash = true;
                    continue;
                }
                (readingHash ? hash : encrypted).Append(lines[i].Trim());
            }
            if (encrypted.Length == 0 || hash.Length == 0)
            {
                return null;
            }
            return new FileData(encrypted.ToString(), hash.ToString());
        }
        catch
        {
            return null;
        }
    }

    private static void WriteFile(string path, string encrypted, string hash)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var text = $"#ENCRYPTED_DATA\n{encrypted}\n#HASH\n{hash}";
        File.WriteAllText(path, text, new UTF8Encoding(false));
    }

    private static string FileName(string cleanSchemeName, DataKind kind) => kind switch
    {
        DataKind.Names => cleanSchemeName + "_names.txt",
        DataKind.Number => cleanSchemeName + "_number.txt",
        DataKind.Seat => cleanSchemeName + "_seat.txt",
        _ => throw new ArgumentOutOfRangeException(nameof(kind))
    };

    private static string DisguiseFileName(string cleanSchemeName, string originalFileName)
    {
        var hash = JavaStringHash(cleanSchemeName) & 0x7FFFFFFF;
        if (originalFileName.Contains("names", StringComparison.Ordinal))
        {
            return $"sys_cache_{hash}.dat";
        }
        if (originalFileName.Contains("number", StringComparison.Ordinal))
        {
            return $"usr_config_{hash}.tmp";
        }
        if (originalFileName.Contains("seat", StringComparison.Ordinal))
        {
            return $"app_data_{hash}.log";
        }
        return $"temp_{hash}.bak";
    }

    public static string CleanSchemeName(string schemeName)
        => string.Concat(schemeName.Select(c => c is '\\' or '/' or ':' or '*' or '?' or '"' or '<' or '>' or '|' ? '_' : c));

    private static int JavaStringHash(string value)
    {
        var hash = 0;
        foreach (var c in value)
        {
            hash = unchecked(31 * hash + c);
        }
        return hash;
    }

    private void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
                _log.Write($"已删除文件: {path}", "FILE_DELETED");
            }
        }
        catch (Exception ex)
        {
            _log.Write($"删除文件失败: {path} ({ex.Message})", "FILE_DELETE_FAILED");
        }
    }
}
