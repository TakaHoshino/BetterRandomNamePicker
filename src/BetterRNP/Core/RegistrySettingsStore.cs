using Microsoft.Win32;

namespace BetterRNP.Core;

public static class SettingsSections
{
    public const string General = "General";
    public const string FloatingBall = "FloatingBall";
    public const string Security = "Security";
    public const string Update = "Update";
    public const string Plugins = "Plugins";
    public const string DisabledPlugins = @"Plugins\Disabled";
    public const string Theme = "Theme";
}

public interface ISettingsStore
{
    string DisplayPath { get; }
    int SchemaVersion { get; }
    void MarkCurrentSchema();
    string? GetString(string section, string name);
    int? GetInt32(string section, string name);
    bool GetBoolean(string section, string name, bool fallback);
    void SetString(string section, string name, string? value);
    void SetInt32(string section, string name, int value);
    void SetBoolean(string section, string name, bool value);
    bool HasValue(string section, string name);
    IReadOnlyDictionary<string, int> GetInt32Values(string section);
    void ReplaceInt32Values(string section, IReadOnlyDictionary<string, int> values);
    void ClearSection(string section);
}

/// <summary>
/// Central application-settings store. All application preferences live below one current-user
/// registry key so they are independent from user-generated schemes, logs, and theme images.
/// </summary>
public sealed class RegistrySettingsStore : ISettingsStore
{
    public const int CurrentSchemaVersion = 1;

    private const string SchemaValueName = "SchemaVersion";
    private readonly string _keyPath;

    public RegistrySettingsStore(string keyPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(keyPath);
        _keyPath = keyPath.Trim().Trim('\\');
    }

    public string KeyPath => _keyPath;

    public string RegistryPath => $@"HKEY_CURRENT_USER\{_keyPath}";
    public string DisplayPath => RegistryPath;

    public int SchemaVersion
        => GetInt32(string.Empty, SchemaValueName) ?? 0;

    public void MarkCurrentSchema() => SetInt32(string.Empty, SchemaValueName, CurrentSchemaVersion);

    public string? GetString(string section, string name)
    {
        using var key = OpenSection(section, writable: false);
        return key?.GetValue(name) switch
        {
            string text => text,
            null => null,
            var value => value.ToString()
        };
    }

    public int? GetInt32(string section, string name)
    {
        using var key = OpenSection(section, writable: false);
        var value = key?.GetValue(name);
        return value switch
        {
            int number => number,
            long number when number is >= int.MinValue and <= int.MaxValue => (int)number,
            string text when int.TryParse(text, out var number) => number,
            _ => null
        };
    }

    public bool GetBoolean(string section, string name, bool fallback)
        => GetInt32(section, name) is { } value ? value != 0 : fallback;

    public void SetString(string section, string name, string? value)
    {
        if (value is null)
        {
            RemoveValue(section, name);
            return;
        }
        using var key = OpenSection(section, writable: true)!;
        key.SetValue(name, value, RegistryValueKind.String);
    }

    public void SetInt32(string section, string name, int value)
    {
        using var key = OpenSection(section, writable: true)!;
        key.SetValue(name, value, RegistryValueKind.DWord);
    }

    public void SetBoolean(string section, string name, bool value)
        => SetInt32(section, name, value ? 1 : 0);

    public void RemoveValue(string section, string name)
    {
        using var key = OpenSection(section, writable: true);
        key?.DeleteValue(name, throwOnMissingValue: false);
    }

    public bool HasSection(string section)
    {
        using var key = OpenSection(section, writable: false);
        return key is not null && key.ValueCount + key.SubKeyCount > 0;
    }

    public bool HasValue(string section, string name)
    {
        using var key = OpenSection(section, writable: false);
        return key?.GetValue(name) is not null;
    }

    public IReadOnlyDictionary<string, string> GetStringValues(string section)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        using var key = OpenSection(section, writable: false);
        if (key is null)
        {
            return values;
        }
        foreach (var name in key.GetValueNames())
        {
            if (key.GetValue(name) is { } value)
            {
                values[name] = value.ToString() ?? string.Empty;
            }
        }
        return values;
    }

    public IReadOnlyDictionary<string, int> GetInt32Values(string section)
    {
        var values = new Dictionary<string, int>(StringComparer.Ordinal);
        using var key = OpenSection(section, writable: false);
        if (key is null)
        {
            return values;
        }
        foreach (var name in key.GetValueNames())
        {
            if (GetInt32(section, name) is { } value)
            {
                values[name] = value;
            }
        }
        return values;
    }

    public void ReplaceStringValues(string section, IReadOnlyDictionary<string, string> values)
    {
        ClearSection(section);
        foreach (var pair in values)
        {
            SetString(section, pair.Key, pair.Value);
        }
    }

    public void ReplaceInt32Values(string section, IReadOnlyDictionary<string, int> values)
    {
        ClearSection(section);
        foreach (var pair in values)
        {
            SetInt32(section, pair.Key, pair.Value);
        }
    }

    public void ClearSection(string section)
    {
        using var key = OpenSection(section, writable: true);
        if (key is null)
        {
            return;
        }
        foreach (var name in key.GetValueNames())
        {
            key.DeleteValue(name, throwOnMissingValue: false);
        }
        foreach (var subKey in key.GetSubKeyNames())
        {
            key.DeleteSubKeyTree(subKey, throwOnMissingSubKey: false);
        }
    }

    public static void DeleteKeyTree(string keyPath)
    {
        if (string.IsNullOrWhiteSpace(keyPath))
        {
            return;
        }
        Registry.CurrentUser.DeleteSubKeyTree(keyPath.Trim().Trim('\\'), throwOnMissingSubKey: false);
    }

    private RegistryKey? OpenSection(string section, bool writable)
    {
        var path = string.IsNullOrWhiteSpace(section)
            ? _keyPath
            : $@"{_keyPath}\{section.Trim().Trim('\\')}";
        if (!writable)
        {
            return Registry.CurrentUser.OpenSubKey(path, writable: false);
        }

        try
        {
            return Registry.CurrentUser.CreateSubKey(path, writable: true);
        }
        catch (UnauthorizedAccessException)
        {
            using var currentUser = RegistryKey.OpenBaseKey(
                RegistryHive.CurrentUser,
                Environment.Is64BitProcess ? RegistryView.Registry64 : RegistryView.Registry32);
            return currentUser.CreateSubKey(path, writable: true);
        }
    }
}
