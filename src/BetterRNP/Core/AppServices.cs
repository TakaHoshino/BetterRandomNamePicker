namespace BetterRNP.Core;

public sealed class AppServices
{
    private AppServices(AppPaths paths)
    {
        Paths = paths;
        Paths.EnsureDirectories();
        Log = new LogService(paths);
        Settings = paths.IsExplicitRoot
            ? MemorySettingsStore.GetOrCreate(paths.RegistrySettingsKey)
            : new RegistrySettingsStore(paths.RegistrySettingsKey);
        LegacySettingsMigrator.Migrate(paths, Settings, Log);
        Encryption = new EncryptionService();
        Config = new ConfigService(Settings, Log);
        Passwords = new PasswordService(Config, Encryption, Log);
        Data = new DataStore(paths, Encryption, Log);
        Names = new NameService(Data, Log);
        Schemes = new SchemeService(paths, Data, Log);
        Theme = new ThemeService(paths, Settings, Log);
        UpdateSettings = new UpdateSettingsService(Settings);
        Update = new UpdateService(Log, paths);
        FloatingBallState = new FloatingBallStateService();
    }

    public AppPaths Paths { get; }
    public LogService Log { get; }
    public ISettingsStore Settings { get; }
    public EncryptionService Encryption { get; }
    public ConfigService Config { get; }
    public PasswordService Passwords { get; }
    public DataStore Data { get; }
    public NameService Names { get; }
    public SchemeService Schemes { get; }
    public ThemeService Theme { get; }
    public UpdateSettingsService UpdateSettings { get; }
    public UpdateService Update { get; }
    public FloatingBallStateService FloatingBallState { get; }

    public static AppServices Create(string? root = null) => new(new AppPaths(root));
}
