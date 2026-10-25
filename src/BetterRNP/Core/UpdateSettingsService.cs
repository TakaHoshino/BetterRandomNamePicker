namespace BetterRNP.Core;

public sealed class UpdateSettings
{
    public bool CheckOnStartup { get; set; } = true;
    public UpdateSource Source { get; set; } = UpdateSource.Official;
}

public sealed class UpdateSettingsService
{
    private readonly ISettingsStore _settings;

    public UpdateSettingsService(ISettingsStore settings)
    {
        _settings = settings;
    }

    public string SettingsPath
        => $@"{_settings.DisplayPath}\{SettingsSections.Update}";

    public UpdateSettings Load()
        => new()
        {
            CheckOnStartup = _settings.GetBoolean(
                SettingsSections.Update,
                "CheckOnStartup",
                fallback: true),
            Source = Enum.TryParse<UpdateSource>(
                _settings.GetString(SettingsSections.Update, "Source"),
                ignoreCase: true,
                out var source)
                ? source
                : UpdateSource.Official
        };

    public void Save(UpdateSettings settings)
    {
        try
        {
            _settings.SetBoolean(
                SettingsSections.Update,
                "CheckOnStartup",
                settings.CheckOnStartup);
            _settings.SetString(
                SettingsSections.Update,
                "Source",
                settings.Source.ToString());
        }
        catch
        {
            // Update preferences are non-critical.
        }
    }
}
