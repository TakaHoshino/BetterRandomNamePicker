using System.Text.Json;

namespace BetterRNP.Core;

internal static class LegacySettingsMigrator
{
    public static void Migrate(
        AppPaths paths,
        ISettingsStore settings,
        LogService log)
    {
        if (settings.SchemaVersion >= RegistrySettingsStore.CurrentSchemaVersion)
        {
            return;
        }

        var imported = 0;
        var failed = false;
        var configFile = paths.ConfigFile;
        var configBackup = Path.Combine(paths.LogDir, "config.properties.bak");
        var themeFile = paths.ThemeConfigFile;
        var themeBackup = Path.Combine(paths.LogDir, "theme.properties.bak");
        var updateFile = Path.Combine(paths.DataDir, "update.json");
        var pluginsFile = Path.Combine(paths.DataDir, "plugins.json");

        ImportFile(configFile, configBackup, "常规设置", () =>
        {
            var properties = JavaProperties.Load(File.Exists(configFile) ? configFile : configBackup);
            foreach (var pair in properties.Values)
            {
                switch (pair.Key)
                {
                    case "autoStart":
                    case "minimizeToTray":
                    case "autoShowFloatingBall":
                        if (bool.TryParse(pair.Value, out var boolean))
                        {
                            settings.SetBoolean(SettingsSections.General, pair.Key, boolean);
                        }
                        break;
                    case "floatingBallRadius":
                        if (int.TryParse(pair.Value, out var radius))
                        {
                            settings.SetInt32(
                                SettingsSections.FloatingBall,
                                pair.Key,
                                radius);
                        }
                        break;
                    case "floatingBallOpacity":
                        if (int.TryParse(pair.Value, out var opacity))
                        {
                            settings.SetInt32(
                                SettingsSections.FloatingBall,
                                pair.Key,
                                opacity);
                        }
                        break;
                    case ConfigService.PasswordHashKey:
                        settings.SetString(
                            SettingsSections.Security,
                            "PasswordHash",
                            pair.Value);
                        break;
                    default:
                        settings.SetString(SettingsSections.General, pair.Key, pair.Value);
                        break;
                }
            }
        }, ref imported, ref failed, log);

        ImportFile(themeFile, themeBackup, "主题设置", () =>
        {
            var properties = JavaProperties.Load(File.Exists(themeFile) ? themeFile : themeBackup);
            foreach (var pair in properties.Values)
            {
                settings.SetString(SettingsSections.Theme, pair.Key, pair.Value);
            }
        }, ref imported, ref failed, log);

        ImportJsonFile(updateFile, "更新设置", root =>
        {
            if (TryGetProperty(root, "CheckOnStartup", out var check)
                && TryReadBoolean(check, out var checkOnStartup))
            {
                settings.SetBoolean(SettingsSections.Update, "CheckOnStartup", checkOnStartup);
            }
            if (TryGetProperty(root, "Source", out var source)
                && source.ValueKind == JsonValueKind.String)
            {
                settings.SetString(SettingsSections.Update, "Source", source.GetString());
            }
        }, ref imported, ref failed, log);

        ImportJsonFile(pluginsFile, "插件设置", root =>
        {
            if (!TryGetProperty(root, "Disabled", out var disabled)
                || disabled.ValueKind != JsonValueKind.Object)
            {
                return;
            }
            foreach (var plugin in disabled.EnumerateObject())
            {
                if (TryReadBoolean(plugin.Value, out var isDisabled) && isDisabled)
                {
                    settings.SetInt32(SettingsSections.DisabledPlugins, plugin.Name, 1);
                }
            }
        }, ref imported, ref failed, log);

        if (failed)
        {
            log.Write(
                "部分旧设置无法迁移，将保留旧文件并在下次启动时重试",
                "SETTINGS_MIGRATION_FAILED");
            return;
        }

        try
        {
            settings.MarkCurrentSchema();
        }
        catch (Exception ex)
        {
            log.WriteException("SETTINGS_MIGRATION_SCHEMA", ex);
            return;
        }

        foreach (var file in new[]
                 {
                     configFile, configBackup, themeFile, themeBackup,
                     updateFile, pluginsFile
                 })
        {
            TryDelete(file, log);
        }

        log.Write(
            $"{settings.DisplayPath}｜导入 {imported} 个来源文件",
            "SETTINGS_MIGRATED_TO_REGISTRY");
    }

    private static void ImportFile(
        string primaryPath,
        string backupPath,
        string description,
        Action import,
        ref int imported,
        ref bool failed,
        LogService log)
    {
        if (!File.Exists(primaryPath) && !File.Exists(backupPath))
        {
            return;
        }
        try
        {
            import();
            imported++;
        }
        catch (Exception ex)
        {
            failed = true;
            log.WriteException($"SETTINGS_MIGRATION_{description}", ex);
        }
    }

    private static void ImportJsonFile(
        string path,
        string description,
        Action<JsonElement> import,
        ref int imported,
        ref bool failed,
        LogService log)
    {
        if (!File.Exists(path))
        {
            return;
        }
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            import(document.RootElement);
            imported++;
        }
        catch (Exception ex)
        {
            failed = true;
            log.WriteException($"SETTINGS_MIGRATION_{description}", ex);
        }
    }

    private static bool TryGetProperty(JsonElement root, string name, out JsonElement value)
    {
        if (root.ValueKind == JsonValueKind.Object)
        {
            foreach (var property in root.EnumerateObject())
            {
                if (property.Name.Equals(name, StringComparison.OrdinalIgnoreCase))
                {
                    value = property.Value;
                    return true;
                }
            }
        }
        value = default;
        return false;
    }

    private static bool TryReadBoolean(JsonElement value, out bool result)
    {
        if (value.ValueKind is JsonValueKind.True or JsonValueKind.False)
        {
            result = value.GetBoolean();
            return true;
        }
        return bool.TryParse(value.ToString(), out result);
    }

    private static void TryDelete(string path, LogService log)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception ex)
        {
            log.Write(
                LogLevel.Warn,
                $"{path}: {ex.Message}",
                "SETTINGS_LEGACY_FILE_DELETE_FAILED");
        }
    }
}
