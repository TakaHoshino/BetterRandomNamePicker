namespace BetterRNP.Core;

public sealed class ConfigService
{
    public const string PasswordHashKey = "x7f9a2b1c4e8d3f6";
    public const int FloatingBallRadiusDefault = 50;
    public const int FloatingBallRadiusMinimum = 8;
    public const int FloatingBallOpacityDefault = 200;
    private const int FloatingBallOpacityMinimum = 0;
    private const int FloatingBallOpacityMaximum = 255;

    private readonly AppPaths _paths;
    private readonly LogService _log;
    private readonly string _backupPath;
    private JavaProperties _properties;

    public ConfigService(AppPaths paths, LogService log)
    {
        _paths = paths;
        _log = log;
        _backupPath = Path.Combine(paths.LogDir, "config.properties.bak");
        _properties = Load();
    }

    public string? PasswordHash => _properties.Get(PasswordHashKey);
    public bool AutoStart => GetBool("autoStart", false);
    public bool MinimizeToTray => GetBool("minimizeToTray", true);
    public bool AutoShowFloatingBall => GetBool("autoShowFloatingBall", false);
    public string LastScheme => _properties.Get("lastScheme", string.Empty) ?? string.Empty;

    public int FloatingBallRadius
    {
        get
        {
            var value = ParseInt(_properties.Get("floatingBallRadius"), FloatingBallRadiusDefault);
            return Math.Max(FloatingBallRadiusMinimum, value);
        }
    }

    public int FloatingBallOpacity
    {
        get
        {
            var value = ParseInt(_properties.Get("floatingBallOpacity"), FloatingBallOpacityDefault);
            return Math.Clamp(value, FloatingBallOpacityMinimum, FloatingBallOpacityMaximum);
        }
    }

    public void SetAutoStart(bool value) => SetBool("autoStart", value);
    public void SetMinimizeToTray(bool value) => SetBool("minimizeToTray", value);
    public void SetAutoShowFloatingBall(bool value) => SetBool("autoShowFloatingBall", value);

    public void SetLastScheme(string value)
    {
        _properties.Set("lastScheme", string.IsNullOrWhiteSpace(value) ? null : value);
        Save();
    }

    public void SetFloatingBallRadius(int value)
    {
        _properties.Set("floatingBallRadius", Math.Max(FloatingBallRadiusMinimum, value).ToString());
        Save();
    }

    public void SetFloatingBallOpacity(int value)
    {
        _properties.Set("floatingBallOpacity",
            Math.Clamp(value, FloatingBallOpacityMinimum, FloatingBallOpacityMaximum).ToString());
        Save();
    }

    public void SetPasswordHash(string hash)
    {
        _properties.Set(PasswordHashKey, hash);
        Save();
        File.Copy(_paths.ConfigFile, _backupPath, true);
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(_paths.LogDir);
            if (File.Exists(_paths.ConfigFile))
            {
                File.Copy(_paths.ConfigFile, _backupPath, true);
            }

            _properties.WriteWithSections(
                _paths.ConfigFile,
                new[]
                {
                    "BetterRNP 配置（UTF-8，可手工编辑）",
                    $"生成时间：{DateTime.Now:yyyy-MM-dd HH:mm:ss}",
                    $"上一版备份：{_backupPath}（主文件缺失时会自动从它恢复）",
                    "主题设置位于 data/themes/theme.properties。"
                },
                new (string, IEnumerable<string>)[]
                {
                    ("启动与窗口", new[]
                    {
                        "autoStart", "minimizeToTray", "autoShowFloatingBall", "lastScheme"
                    }),
                    ("悬浮球", new[] { "floatingBallRadius", "floatingBallOpacity" }),
                    ("安全（密码散列，请勿手工修改）", new[] { PasswordHashKey })
                });
        }
        catch (Exception ex)
        {
            _log.Write($"保存配置失败: {ex.Message}", "CONFIG_SAVE_ERROR");
        }
    }

    private JavaProperties Load()
    {
        try
        {
            if (!File.Exists(_paths.ConfigFile) && File.Exists(_backupPath))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_paths.ConfigFile)!);
                File.Copy(_backupPath, _paths.ConfigFile, true);
                _log.Write($"配置主文件缺失，已从备份恢复: {_backupPath}", "CONFIG_RESTORE");
            }

            var result = JavaProperties.Load(_paths.ConfigFile);
            if (!File.Exists(_paths.ConfigFile))
            {
                result.Set("autoStart", "false");
                result.Set("minimizeToTray", "true");
                result.Set("autoShowFloatingBall", "false");
                result.Set("floatingBallRadius", FloatingBallRadiusDefault.ToString());
                result.Set("floatingBallOpacity", FloatingBallOpacityDefault.ToString());
                _properties = result;
                Save();
                _log.Write("配置文件已自动创建", "CONFIG_CREATE");
            }
            return result;
        }
        catch (Exception ex)
        {
            _log.Write($"读取配置失败: {ex.Message}", "CONFIG_LOAD_ERROR");
            return new JavaProperties();
        }
    }

    private bool GetBool(string key, bool fallback)
        => bool.TryParse(_properties.Get(key), out var value) ? value : fallback;

    private void SetBool(string key, bool value)
    {
        _properties.Set(key, value.ToString().ToLowerInvariant());
        Save();
    }

    private static int ParseInt(string? value, int fallback)
        => int.TryParse(value, out var parsed) ? parsed : fallback;
}
