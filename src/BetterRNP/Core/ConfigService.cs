namespace BetterRNP.Core;

public sealed class ConfigService
{
    public const string PasswordHashKey = "x7f9a2b1c4e8d3f6";
    public const int FloatingBallRadiusDefault = 50;
    public const int FloatingBallRadiusMinimum = 8;
    public const int FloatingBallOpacityDefault = 200;
    private const int FloatingBallOpacityMinimum = 0;
    private const int FloatingBallOpacityMaximum = 255;

    private readonly ISettingsStore _settings;
    private readonly LogService _log;

    public ConfigService(ISettingsStore settings, LogService log)
    {
        _settings = settings;
        _log = log;
    }

    public string? PasswordHash => _settings.GetString(SettingsSections.Security, "PasswordHash");

    public string Language => LocalizationService.NormalizeLanguage(
        _settings.GetString(SettingsSections.General, "language")
        ?? System.Globalization.CultureInfo.CurrentUICulture.Name);

    public bool AutoStart => _settings.GetBoolean(
        SettingsSections.General,
        "autoStart",
        fallback: false);

    public bool MinimizeToTray => _settings.GetBoolean(
        SettingsSections.General,
        "minimizeToTray",
        fallback: true);

    public bool AutoShowFloatingBall => _settings.GetBoolean(
        SettingsSections.General,
        "autoShowFloatingBall",
        fallback: false);

    public string LastScheme
        => _settings.GetString(SettingsSections.General, "lastScheme") ?? string.Empty;

    public int FloatingBallRadius => Math.Max(
        FloatingBallRadiusMinimum,
        _settings.GetInt32(
            SettingsSections.FloatingBall,
            "floatingBallRadius") ?? FloatingBallRadiusDefault);

    public int FloatingBallOpacity => Math.Clamp(
        _settings.GetInt32(
            SettingsSections.FloatingBall,
            "floatingBallOpacity") ?? FloatingBallOpacityDefault,
        FloatingBallOpacityMinimum,
        FloatingBallOpacityMaximum);

    public void SetAutoStart(bool value)
        => SetBoolean(SettingsSections.General, "autoStart", value);

    public void SetMinimizeToTray(bool value)
        => SetBoolean(SettingsSections.General, "minimizeToTray", value);

    public void SetAutoShowFloatingBall(bool value)
        => SetBoolean(SettingsSections.General, "autoShowFloatingBall", value);

    public void SetLanguage(string languageCode)
        => Persist(() => _settings.SetString(
            SettingsSections.General,
            "language",
            LocalizationService.NormalizeLanguage(languageCode)));

    public void SetLastScheme(string value)
        => Persist(() => _settings.SetString(
            SettingsSections.General,
            "lastScheme",
            string.IsNullOrWhiteSpace(value) ? null : value));

    public void SetFloatingBallRadius(int value)
        => Persist(() => _settings.SetInt32(
            SettingsSections.FloatingBall,
            "floatingBallRadius",
            Math.Max(FloatingBallRadiusMinimum, value)));

    public void SetFloatingBallOpacity(int value)
        => Persist(() => _settings.SetInt32(
            SettingsSections.FloatingBall,
            "floatingBallOpacity",
            Math.Clamp(value, FloatingBallOpacityMinimum, FloatingBallOpacityMaximum)));

    public void SetPasswordHash(string hash)
        => Persist(() => _settings.SetString(
            SettingsSections.Security,
            "PasswordHash",
            hash));

    public void Save()
        => Persist(_settings.MarkCurrentSchema);

    private void SetBoolean(string section, string name, bool value)
        => Persist(() => _settings.SetBoolean(section, name, value));

    private void Persist(Action write)
    {
        try
        {
            write();
        }
        catch (Exception ex)
        {
            _log.Write(LogLevel.Error, $"保存配置失败: {ex.Message}", "CONFIG_SAVE_ERROR");
        }
    }
}
