namespace BetterRNP.Core;

/// <summary>
/// Isolated settings store for tests and headless smoke runs. The desktop application uses
/// <see cref="RegistrySettingsStore"/> unless an explicit test root is supplied.
/// </summary>
internal sealed class MemorySettingsStore : ISettingsStore
{
    private static readonly Dictionary<string, MemorySettingsStore> Instances =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, Dictionary<string, RegistryValue>> _sections =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly string _key;
    private int _schemaVersion;

    private MemorySettingsStore(string key)
    {
        _key = key;
    }

    public static MemorySettingsStore GetOrCreate(string key)
    {
        lock (Instances)
        {
            if (!Instances.TryGetValue(key, out var store))
            {
                store = new MemorySettingsStore(key);
                Instances[key] = store;
            }
            return store;
        }
    }

    public string DisplayPath => $"memory://{_key}";
    public int SchemaVersion => _schemaVersion;

    public void MarkCurrentSchema()
        => _schemaVersion = RegistrySettingsStore.CurrentSchemaVersion;

    public string? GetString(string section, string name)
        => Get(section, name) is { Kind: ValueKind.String } value ? value.Text : null;

    public int? GetInt32(string section, string name)
        => Get(section, name) is { Kind: ValueKind.Int32 } value ? value.Number : null;

    public bool GetBoolean(string section, string name, bool fallback)
        => GetInt32(section, name) is { } value ? value != 0 : fallback;

    public void SetString(string section, string name, string? value)
    {
        if (value is null)
        {
            Remove(section, name);
            return;
        }
        Set(section, name, new RegistryValue(ValueKind.String, value, 0));
    }

    public void SetInt32(string section, string name, int value)
        => Set(section, name, new RegistryValue(ValueKind.Int32, null, value));

    public void SetBoolean(string section, string name, bool value)
        => SetInt32(section, name, value ? 1 : 0);

    public bool HasValue(string section, string name)
        => Get(section, name) is not null;

    public IReadOnlyDictionary<string, int> GetInt32Values(string section)
        => GetSection(section)
            .Where(pair => pair.Value.Kind == ValueKind.Int32)
            .ToDictionary(pair => pair.Key, pair => pair.Value.Number, StringComparer.Ordinal);

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
        if (_sections.TryGetValue(section, out var values))
        {
            values.Clear();
        }
    }

    private RegistryValue? Get(string section, string name)
        => _sections.TryGetValue(section, out var values)
           && values.TryGetValue(name, out var value)
            ? value
            : null;

    private IReadOnlyDictionary<string, RegistryValue> GetSection(string section)
        => _sections.TryGetValue(section, out var values)
            ? values
            : new Dictionary<string, RegistryValue>();

    private void Set(string section, string name, RegistryValue value)
    {
        if (!_sections.TryGetValue(section, out var values))
        {
            values = new Dictionary<string, RegistryValue>(StringComparer.Ordinal);
            _sections[section] = values;
        }
        values[name] = value;
    }

    private void Remove(string section, string name)
    {
        if (_sections.TryGetValue(section, out var values))
        {
            values.Remove(name);
        }
    }

    private enum ValueKind
    {
        String,
        Int32
    }

    private sealed record RegistryValue(ValueKind Kind, string? Text, int Number);
}
