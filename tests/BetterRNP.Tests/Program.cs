using System.Text;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using BetterRNP.Core;
using BetterRNP.Models;
using BetterRNP.Modes;
using BetterRNP.Plugins;
using BetterRNP.PluginSdk.Models;
using BetterRNP.PluginSdk.Packaging;
using BetterRNP.RandomnessAnalyzerPlugin.Analysis;
using BetterRNP.RandomnessAnalyzerPlugin.Views;
using BetterRNP.TimerPlugin.Timing;
using BetterRNP.TimerPlugin.Views;
using BetterRNP.Views;

namespace BetterRNP.Tests;

internal static class Program
{
    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TOOLWINDOW = 0x00000080;
    private const long WS_EX_APPWINDOW = 0x00040000;
    private const long WS_EX_NOACTIVATE = 0x08000000;

    private const string JavaEncrypted =
        "aDx8MkpV81oAxN4CwTxqc+whVxg6wZnnexVhFmxR+2qhjTe92xB0aEO1";
    private const string JavaPlainHash =
        "Dt2apSkaUId9dBEGZIz1C/034BmNu5lR0JTPxJ3zXVA=";
    private const string JavaLegacyPasswordHash =
        "yOssv1MdodCUmnO3zMS0ilaumA6Zfr8vKSJZ07XhKRU=";
    private const string JavaPbkdfPasswordHash =
        "pbkdf2-sha256$210000$t0B6BOdHC86qLROrqos5PQ==$lKII4s3a6U+D81/K/vC8Jku0uHm5OFXgD27++zFzkeg=";

    private static int _passed;
    private static int _failed;

    [STAThread]
    private static int Main()
    {
        Console.OutputEncoding = Encoding.UTF8;
        var root = Path.Combine(
            AppContext.BaseDirectory,
            ".test-data",
            Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            RunCoreTests(root);
            RunLocalizationTests(root);
            RunLogTests(root);
            RunPluginTests(root);
            RunTimerPluginTests(root);
            RunRandomnessAnalyzerTests(root);
            RunTimerEngineTests();
            RunWindowConstructionTests(root);
        }
        catch (Exception ex)
        {
            Fail($"unexpected exception: {ex}");
        }
        finally
        {
            try
            {
                Directory.Delete(root, true);
            }
            catch
            {
                // Test cleanup is best effort.
            }
        }

        Console.WriteLine($"PASS={_passed} FAIL={_failed}");
        return _failed == 0 ? 0 : 1;
    }

    private static void RunCoreTests(string root)
    {
        var legacyRoot = Path.Combine(root, "legacy-settings");
        var legacyPaths = new AppPaths(legacyRoot);
        legacyPaths.EnsureDirectories();
        File.WriteAllText(
            legacyPaths.ConfigFile,
            """
            language=ja-JP
            autoStart=true
            minimizeToTray=false
            autoShowFloatingBall=true
            lastScheme=旧方案
            floatingBallRadius=77
            floatingBallOpacity=143
            x7f9a2b1c4e8d3f6=legacy-password-hash
            """);
        File.WriteAllText(
            legacyPaths.ThemeConfigFile,
            """
            enabled=true
            mask=55
            g.image=legacy.png
            cat.main.mode=Crop
            """);
        File.WriteAllText(
            Path.Combine(legacyPaths.DataDir, "update.json"),
            """{"CheckOnStartup":false,"Source":"GhProxy"}""");
        File.WriteAllText(
            Path.Combine(legacyPaths.DataDir, "plugins.json"),
            """{"Disabled":{"com.example.legacy":true}}""");
        var migrated = AppServices.Create(legacyRoot);
        Check("legacy general settings migrate to registry store",
            migrated.Config.Language == LocalizationService.Japanese
            && migrated.Config.AutoStart
            && !migrated.Config.MinimizeToTray
            && migrated.Config.AutoShowFloatingBall
            && migrated.Config.LastScheme == "旧方案"
            && migrated.Config.FloatingBallRadius == 77
            && migrated.Config.FloatingBallOpacity == 143
            && migrated.Config.PasswordHash == "legacy-password-hash");
        Check("legacy update settings migrate to registry store",
            !migrated.UpdateSettings.Load().CheckOnStartup
            && migrated.UpdateSettings.Load().Source == UpdateSource.GhProxy);
        Check("legacy theme settings migrate to registry store",
            migrated.Theme.IsEnabled
            && migrated.Theme.MaskAlpha == 55
            && migrated.Theme.GlobalImageId == "legacy.png"
            && migrated.Theme.GetCategoryMode(ThemeService.CategoryMain) == BackdropMode.Crop);
        Check("legacy plugin settings migrate to registry store",
            migrated.Settings.GetInt32(SettingsSections.DisabledPlugins, "com.example.legacy") == 1);
        Check("legacy setting files are removed after migration",
            !File.Exists(legacyPaths.ConfigFile)
            && !File.Exists(legacyPaths.ThemeConfigFile)
            && !File.Exists(Path.Combine(legacyPaths.DataDir, "update.json"))
            && !File.Exists(Path.Combine(legacyPaths.DataDir, "plugins.json")));

        var legacyDefaultRoot = Path.Combine(root, "legacy-default-password");
        var legacyDefaultServices = AppServices.Create(legacyDefaultRoot);
        legacyDefaultServices.Config.SetPasswordHash(
            legacyDefaultServices.Encryption.HashPassword(
                "#include<bits/stdc++.h>usingnamespacestd;intmain(){return0;}"));
        var upgradedPasswordServices = AppServices.Create(legacyDefaultRoot);
        Check("obsolete built-in default password is removed on upgrade",
            !upgradedPasswordServices.Passwords.HasPassword
            && !upgradedPasswordServices.Passwords.IsLocked);

        var services = AppServices.Create(root);

        var staleCache = Path.Combine(services.Paths.PluginCacheDir, "stale-plugin");
        Directory.CreateDirectory(staleCache);
        File.WriteAllText(Path.Combine(staleCache, "plugin.dll"), "stale");
        var cacheClean = PluginCacheService.Clear(services.Paths);
        Check("startup cleanup removes stale plugin cache",
            cacheClean.Success
            && cacheClean.RemovedEntries == 1
            && !Directory.EnumerateFileSystemEntries(services.Paths.PluginCacheDir).Any());

        Check("Java AES-GCM ciphertext decrypts",
            services.Encryption.DecryptData(JavaEncrypted, "兼容测试") == "张三\n李四\n");
        Check("Java SHA-256 hash is compatible",
            services.Encryption.CalculateHash("张三\n李四\n") == JavaPlainHash);
        Check("legacy Java password hash verifies",
            services.Encryption.VerifyPassword("test-pass-2026", JavaLegacyPasswordHash));
        Check("Java PBKDF2 password hash verifies",
            services.Encryption.VerifyPassword("test-pass-2026", JavaPbkdfPasswordHash));

        var roundTrip = services.Encryption.EncryptData("第1名\n第2名\n", "roundtrip");
        Check("AES-GCM round trip",
            services.Encryption.DecryptData(roundTrip, "roundtrip") == "第1名\n第2名\n");

        Check("new installations start without a password",
            !services.Passwords.HasPassword && !services.Passwords.IsLocked);
        Check("recovery shortcut cannot be enabled without a password",
            !services.Passwords.SetRecoveryShortcutEnabled(true));
        Check("user can set an optional password",
            services.Passwords.SetPassword("better-pass-2026")
            && services.Passwords.HasPassword
            && !services.Passwords.IsLocked);
        Check("authenticated user can enable recovery shortcut",
            services.Passwords.SetRecoveryShortcutEnabled(true)
            && services.Passwords.RecoveryShortcutEnabled);
        services.Passwords.Lock();
        Check("wrong password rejected",
            !services.Passwords.VerifyAndUnlock("wrong-password"));
        Check("enabled recovery shortcut unlocks after ten Ctrl+L presses",
            Enumerable.Range(0, 9).All(_ => !services.Passwords.RegisterCtrlL())
            && services.Passwords.RegisterCtrlL()
            && !services.Passwords.IsLocked);
        services.Passwords.Lock();
        Check("new password accepted",
            services.Passwords.VerifyAndUnlock("better-pass-2026"));
        Check("authenticated user can remove password protection",
            services.Passwords.RemovePassword()
            && !services.Passwords.HasPassword
            && !services.Passwords.IsLocked
            && !services.Passwords.RecoveryShortcutEnabled);

        services.Names.SaveNames("默认方案", new[] { "张三", "李四", "王五" });
        Check("names persist through encrypted storage",
            services.Names.LoadNames("默认方案").SequenceEqual(new[] { "张三", "李四", "王五" }));

        var clean = DataStore.CleanSchemeName("默认方案");
        var mainFile = Path.Combine(services.Paths.SchemesDir, clean + "_names.txt");
        var backupFile = Path.Combine(services.Paths.BackupDir, clean + "_" + clean + "_names.txt");
        Check("three encrypted copies exist",
            File.Exists(mainFile)
            && File.Exists(backupFile)
            && Directory.EnumerateFiles(services.Paths.LogDir, "sys_cache_*.dat").Any());
        File.WriteAllText(mainFile, "corrupted");
        Check("data-store self-heals corrupted main copy",
            services.Names.LoadNames("默认方案").SequenceEqual(new[] { "张三", "李四", "王五" }));

        var javaDataFile = Path.Combine(services.Paths.SchemesDir, "兼容测试_names.txt");
        File.WriteAllText(javaDataFile,
            $"#ENCRYPTED_DATA\n{JavaEncrypted}\n#HASH\n{JavaPlainHash}",
            new UTF8Encoding(false));
        Check("Java encrypted data file is readable",
            services.Names.LoadNames("兼容测试").SequenceEqual(new[] { "张三", "李四" }));

        var importFile = Path.Combine(root, "import.txt");
        File.WriteAllLines(importFile, new[] { "赵六", "", "钱七" }, new UTF8Encoding(false));
        services.Names.ImportNames("默认方案", importFile);
        Check("name import appends records",
            services.Names.LoadNames("默认方案").SequenceEqual(new[] { "张三", "李四", "王五", "赵六", "钱七" }));

        Check("scheme creation", services.Schemes.AddScheme("六年级一班", Scheme.NumberType));
        Check("scheme index read",
            services.Schemes.GetAllSchemes().Any(s => s.Name == "六年级一班" && s.Type == Scheme.NumberType));
        Check("duplicate scheme rejected", !services.Schemes.AddScheme("六年级一班", Scheme.NumberType));

        services.Schemes.SaveNumberRange("六年级一班", new NumberRange(-10, 10));
        Check("number range persists",
            services.Schemes.GetNumberRange("六年级一班") == new NumberRange(-10, 10));

        services.Schemes.SaveSeatConfig("六年级一班",
            new SeatConfig(5, 6, new[] { new System.Windows.Point(1, 1), new System.Windows.Point(5, 6) }));
        var seatConfig = services.Schemes.GetSeatConfig("六年级一班");
        Check("seat configuration persists",
            seatConfig is { Rows: 5, Cols: 6 } && seatConfig.SelectedSeats.Count == 2);

        Check("scheme removal", services.Schemes.RemoveScheme("六年级一班"));
        Check("scheme removal updates index",
            !services.Schemes.GetAllSchemes().Any(s => s.Name == "六年级一班"));

        services.Config.SetFloatingBallRadius(12);
        services.Config.SetFloatingBallOpacity(143);
        services.Config.SetMinimizeToTray(false);
        var reloaded = AppServices.Create(root);
        Check("configuration persists",
            reloaded.Config.FloatingBallRadius == 12
            && reloaded.Config.FloatingBallOpacity == 143
            && !reloaded.Config.MinimizeToTray);

        var mutexName = $"Local\\BetterRNP.Tests.{Guid.NewGuid():N}";
        var eventName = $"Local\\BetterRNP.Tests.Show.{Guid.NewGuid():N}";
        using var primaryInstance = new SingleInstanceGuard(mutexName, eventName);
        using var secondaryInstance = new SingleInstanceGuard(mutexName, eventName);
        using var activationSignal = new ManualResetEventSlim(false);
        primaryInstance.StartListening(() => activationSignal.Set());
        Check("single instance identifies primary process", primaryInstance.IsPrimary);
        Check("single instance detects secondary process", !secondaryInstance.IsPrimary);
        Check("secondary process signals primary",
            secondaryInstance.SignalExisting()
            && activationSignal.Wait(TimeSpan.FromSeconds(3)));

        const string releaseJson = """
        {
          "tag_name": "v2.0.1",
          "html_url": "https://github.com/TakaHoshino/BetterRandomNamePicker/releases/tag/v2.0.1",
          "body": "## BetterRNP v2.0.1\n\n### 更新内容\n- test",
          "assets": [
            {
              "name": "BetterRNP_win-x64_self-contained_v2.0.1.zip",
              "size": 104857600,
              "browser_download_url": "https://github.com/TakaHoshino/BetterRandomNamePicker/releases/download/v2.0.1/BetterRNP_win-x64_self-contained_v2.0.1.zip"
            },
            {
              "name": "BetterRNP_win-x64_framework-dependent_v2.0.1.zip",
              "size": 20971520,
              "browser_download_url": "https://github.com/TakaHoshino/BetterRandomNamePicker/releases/download/v2.0.1/BetterRNP_win-x64_framework-dependent_v2.0.1.zip"
            }
          ]
        }
        """;
        var update = UpdateService.EvaluateReleaseJson(releaseJson, new Version(2, 0, 0));
        Check("update checker detects newer release", update.UpdateAvailable);
        Check("update checker parses latest tag", update.LatestTag == "v2.0.1");
        Check("update checker finds both package assets",
            update.SelfContainedAsset?.SizeBytes == 104857600
            && update.FrameworkDependentAsset?.SizeBytes == 20971520);
        Check("update checker keeps release notes",
            update.ReleaseNotes.Contains("### 更新内容", StringComparison.Ordinal));
        Check("update checker proxy URL",
            services.Update.ResolveDownloadUri(update.SelfContainedAsset!, UpdateSource.GhProxy)
                .AbsoluteUri.StartsWith(UpdateService.GhProxyPrefix, StringComparison.Ordinal));
        Check("same release is not newer",
            !UpdateService.EvaluateReleaseJson(releaseJson, new Version(2, 0, 1)).UpdateAvailable);
        Check("detects framework-dependent installation",
            UpdateService.DetectInstallationKind(
                @"C:\Program Files\dotnet\shared\Microsoft.NETCore.App\8.0.0\System.Private.CoreLib.dll",
                @"C:\Apps\BetterRNP") == UpdateAssetKind.FrameworkDependent);
        Check("detects self-contained installation",
            UpdateService.DetectInstallationKind(
                @"C:\Apps\BetterRNP\System.Private.CoreLib.dll",
                @"C:\Apps\BetterRNP") == UpdateAssetKind.SelfContained);
        Check("single-file layout detects self-contained",
            UpdateService.DetectInstallationKind(string.Empty, @"C:\Apps\BetterRNP")
                == UpdateAssetKind.SelfContained);

        services.UpdateSettings.Save(new UpdateSettings
        {
            CheckOnStartup = false,
            Source = UpdateSource.GhProxy
        });
        var updateSettings = services.UpdateSettings.Load();
        Check("update settings persist",
            !updateSettings.CheckOnStartup && updateSettings.Source == UpdateSource.GhProxy);

        var pngPath = Path.Combine(root, "theme-preview.png");
        File.WriteAllBytes(pngPath, Convert.FromBase64String(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        var imageId = services.Theme.ImportImage(pngPath);
        var themeImage = services.Theme.ListImages().First(image => image.Id == imageId);
        Check("theme image library exposes thumbnail preview", themeImage.HasPreview);
        services.Theme.SetGlobalImage(imageId);
        services.Theme.SetGlobalCrop(new Rect(0.1, 0.2, 0.5, 0.4));
        services.Theme.SetGlobalMode(BackdropMode.Crop);
        services.Theme.IsEnabled = true;
        var cropStyle = services.Theme.ResolveStyle(ThemeService.CategoryMain);
        Check("theme crop persists and resolves",
            cropStyle is { Mode: BackdropMode.Crop }
            && Math.Abs(cropStyle.Crop.X - 0.1) < 0.001
            && Math.Abs(cropStyle.Crop.Y - 0.2) < 0.001
            && Math.Abs(cropStyle.Crop.Width - 0.5) < 0.001
            && Math.Abs(cropStyle.Crop.Height - 0.4) < 0.001);
        services.Theme.SetCategoryCrop(ThemeService.CategoryConfig, new Rect(0.2, 0.2, 0.6, 0.6));
        Check("category crop resolves independently",
            Math.Abs(services.Theme.GetCategoryCrop(ThemeService.CategoryConfig).Width - 0.6) < 0.001);
        services.Theme.IsEnabled = false;

        var defaultScheme = new Scheme(Scheme.BuiltInDefaultName, Scheme.NameListType, true);
        var nameMode = new NameListMode(services, () => defaultScheme);
        var namesNow = services.Names.LoadNames(defaultScheme.Name);
        var nameCandidate = nameMode.CanPick() is null
            ? nameMode.CreateCandidateSupplier()()
            : string.Empty;
        Check("name mode produces a list candidate", namesNow.Contains(nameCandidate));

        services.Schemes.SaveNumberRange(defaultScheme.Name, new NumberRange(-2, 2));
        var numberMode = new NumberMode(services, () => defaultScheme);
        var numberCandidate = numberMode.CanPick() is null
            ? int.Parse(numberMode.CreateCandidateSupplier()())
            : int.MinValue;
        Check("number mode stays inside configured range", numberCandidate is >= -2 and <= 2);

        services.Schemes.SaveSeatConfig(defaultScheme.Name,
            new SeatConfig(2, 2, new[] { new System.Windows.Point(1, 2), new System.Windows.Point(2, 1) }));
        var seatMode = new SeatMode(services, () => defaultScheme);
        var seatCandidate = seatMode.CanPick() is null
            ? seatMode.CreateCandidateSupplier()()
            : string.Empty;
        Check("seat mode produces a selected seat",
            seatCandidate is "(1, 2)" or "(2, 1)");
    }

    private static void RunLocalizationTests(string root)
    {
        LocalizationService.Initialize(LocalizationService.SimplifiedChinese);
        Check("all six interface languages are available",
            LocalizationService.SupportedLanguages.Count == 6);
        Check("Simplified Chinese remains the source language",
            LocalizationService.T("设置") == "设置");
        LocalizationService.SetLanguage(LocalizationService.English);
        Check("English translation lookup works",
            LocalizationService.T("设置") == "Settings");
        LocalizationService.SetLanguage(LocalizationService.Russian);
        Check("Russian translation lookup works",
            LocalizationService.T("设置") == "Настройки");
        LocalizationService.SetLanguage(LocalizationService.Japanese);
        Check("Japanese translation lookup works",
            LocalizationService.T("设置") == "設定");
        var localizedDefaultScheme = new Scheme(
            Scheme.BuiltInDefaultName,
            Scheme.NameListType,
            isBuiltIn: true);
        Check("built-in scheme labels use translated mode names",
            localizedDefaultScheme.DisplayName == "既定のプリセット（組み込み · 名前リスト）");
        Check("new dialog resources are translated",
            LocalizationService.Get(UiText.ExitConfirmationTitle) == "終了の確認"
            && LocalizationService.Get(UiText.ImportFailureFormat).Contains("インポート", StringComparison.Ordinal));
        LocalizationService.SetLanguage(LocalizationService.TraditionalChinese);
        Check("Traditional Chinese translation lookup works",
            LocalizationService.T("设置") == "設定");
        LocalizationService.SetLanguage(LocalizationService.Cantonese);
        Check("Cantonese translation overrides Traditional Chinese",
            LocalizationService.T("悬浮球") == "浮動球"
            && LocalizationService.T("开始抽取") == "開始抽籤");

        LocalizationService.SetLanguage(LocalizationService.English);
        var text = new TextBlock { Text = "设置" };
        LocalizationService.ApplyTo(text);
        Check("localization updates an existing visual element",
            text.Text == "Settings");
        LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);
        LocalizationService.ApplyTo(text);
        Check("localization restores the source language",
            text.Text == "设置");

        var expectedThemes = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            [LocalizationService.SimplifiedChinese] = "主题",
            [LocalizationService.TraditionalChinese] = "主題",
            [LocalizationService.Cantonese] = "主題",
            [LocalizationService.English] = "Theme",
            [LocalizationService.Russian] = "Тема",
            [LocalizationService.Japanese] = "テーマ"
        };
        var expectedSettings = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            [LocalizationService.SimplifiedChinese] = "设置",
            [LocalizationService.TraditionalChinese] = "設定",
            [LocalizationService.Cantonese] = "設定",
            [LocalizationService.English] = "Settings",
            [LocalizationService.Russian] = "Настройки",
            [LocalizationService.Japanese] = "設定"
        };
        var themeText = new TextBlock { Text = "主题" };
        var settingsContent = new ContentControl { Content = "设置" };
        var transitionFailures = new List<string>();
        foreach (var sourcePair in expectedThemes)
        {
            LocalizationService.SetLanguage(sourcePair.Key);
            LocalizationService.ApplyTo(themeText);
            LocalizationService.ApplyTo(settingsContent);
            foreach (var targetPair in expectedThemes)
            {
                LocalizationService.SetLanguage(targetPair.Key);
                LocalizationService.ApplyTo(themeText);
                LocalizationService.ApplyTo(settingsContent);
                if (themeText.Text != targetPair.Value
                    || (settingsContent.Content as string) != expectedSettings[targetPair.Key])
                {
                    transitionFailures.Add($"{sourcePair.Key}->{targetPair.Key}");
                }
            }
        }
        Check(
            "all language transitions replace previous language text",
            transitionFailures.Count == 0);

        var services = AppServices.Create(Path.Combine(root, "localization"));
        services.Config.SetLanguage(LocalizationService.Japanese);
        var reloaded = AppServices.Create(Path.Combine(root, "localization"));
        Check("language selection persists in configuration",
            reloaded.Config.Language == LocalizationService.Japanese);
        LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);
    }

    private static void RunTimerPluginTests(string root)
    {
        const string manifest = """
        {
          "id": "com.betterrnp.timer",
          "name": "BetterRNP 计时插件",
          "version": "1.0.0",
          "author": "BetterRNP",
          "type": "command",
          "assembly": "BetterRNP.TimerPlugin.dll",
          "entryType": "BetterRNP.TimerPlugin.TimerPlugin",
          "apiVersion": "1.0",
          "description": "test"
        }
        """;

        var pluginAssembly = Path.Combine(AppContext.BaseDirectory, "BetterRNP.TimerPlugin.dll");
        Check("timer plugin assembly is available", File.Exists(pluginAssembly));
        var packagePath = Path.Combine(root, "timer.rnpp");
        WritePluginPackage(packagePath, manifest, pluginAssembly);
        Check("timer rnpp manifest validation",
            RnpPackager.ReadManifest(packagePath) is { Success: true, Manifest.Id: "com.betterrnp.timer" });

        var services = AppServices.Create(Path.Combine(root, "timer-services"));
        using var plugins = new PluginManager(services);
        var load = plugins.LoadFromPackage(packagePath, copyIntoPluginDirectory: false);
        Check("timer rnpp loads", load.Success);
        Check("timer plugin registers two floating ball commands",
            plugins.FloatingBallCommands.Count == 2);
        Check("floating ball command titles are 正计时 and 倒计时",
            plugins.FloatingBallCommands.Select(command => command.Title)
                .SequenceEqual(new[] { "正计时", "倒计时" }));
        Check("timer plugin adds no main menu commands", plugins.Commands.Count == 0);
        Check("timer menu titles start in Simplified Chinese",
            plugins.FloatingBallCommands[0].DisplayTitle == "正计时"
            && plugins.FloatingBallCommands[1].DisplayTitle == "倒计时");
        LocalizationService.SetLanguage(LocalizationService.English);
        Check("timer menu titles switch language dynamically",
            plugins.FloatingBallCommands[0].DisplayTitle == "Count up"
            && plugins.FloatingBallCommands[1].DisplayTitle == "Countdown");
        var timerTitleExpectations = new Dictionary<string, (string CountUp, string CountDown)>(
            StringComparer.Ordinal)
        {
            ["zh-Hans"] = ("正计时", "倒计时"),
            ["zh-Hant"] = ("正計時", "倒計時"),
            ["zh-HK"] = ("正計時", "倒數"),
            ["en-US"] = ("Count up", "Countdown"),
            ["ru-RU"] = ("Прямой отсчёт", "Обратный отсчёт"),
            ["ja-JP"] = ("カウントアップ", "カウントダウン")
        };
        var timerLanguageFailures = new List<string>();
        foreach (var (language, expected) in timerTitleExpectations)
        {
            LocalizationService.SetLanguage(language);
            if (plugins.FloatingBallCommands[0].DisplayTitle != expected.CountUp
                || plugins.FloatingBallCommands[1].DisplayTitle != expected.CountDown)
            {
                timerLanguageFailures.Add(language);
            }
        }
        Check("all timer plugin languages replace previous titles",
            timerLanguageFailures.Count == 0);
        LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);

        var loaded = load.Info;
        Check("disabled timer plugin hides floating ball commands",
            loaded is not null
            && plugins.SetEnabled(loaded.Manifest.Id, false)
            && plugins.FloatingBallCommands.Count == 0);
        Check("re-enabled timer plugin restores floating ball commands",
            loaded is not null
            && plugins.SetEnabled(loaded.Manifest.Id, true)
            && plugins.FloatingBallCommands.Count == 2);
    }

    private static void RunTimerEngineTests()
    {
        var clock = TimeSpan.Zero;

        var up = new TimerEngine(TimerMode.CountUp, TimeSpan.Zero, () => clock);
        Check("count-up starts at 00:00",
            up.State == TimerState.Idle && up.DisplayText == "00:00" && !up.IsWarning);

        up.Start();
        clock = TimeSpan.FromSeconds(1.4);
        Check("count-up advances the display", !up.Tick() && up.DisplayText == "00:01");

        up.Pause();
        clock = TimeSpan.FromSeconds(9);
        Check("paused count-up freezes its time", !up.Tick() && up.DisplayText == "00:01");

        up.Start();
        clock = TimeSpan.FromSeconds(10);
        Check("resumed count-up continues from the pause", !up.Tick() && up.DisplayText == "00:02");

        up.Reset();
        Check("reset returns count-up to zero",
            up.State == TimerState.Idle && up.DisplayText == "00:00");

        clock = TimeSpan.Zero;
        var down = new TimerEngine(TimerMode.CountDown, TimeSpan.FromSeconds(30), () => clock);
        Check("countdown starts at its configured duration",
            down.DisplayText == "00:30" && !down.IsWarning);

        down.Start();
        clock = TimeSpan.FromSeconds(19);
        Check("countdown counts down",
            !down.Tick() && down.DisplayText == "00:11" && !down.IsWarning);

        clock = TimeSpan.FromSeconds(20);
        Check("countdown turns red in the last 10 seconds",
            !down.Tick() && down.IsWarning && down.DisplayText == "00:10");

        clock = TimeSpan.FromSeconds(30);
        Check("countdown finishes at zero",
            down.Tick() && down.State == TimerState.Finished && down.DisplayText == "00:00");
        Check("finished countdown reports completion only once", !down.Tick());

        down.Reset();
        Check("reset restores the countdown duration",
            down.State == TimerState.Idle
            && down.DisplayText == "00:30"
            && !down.IsWarning);

        down.Start();
        clock = TimeSpan.FromSeconds(31);
        Check("restarted countdown uses the full duration again",
            !down.Tick() && down.DisplayText == "00:29");

        Check("time formatting uses MM:SS",
            TimerEngine.FormatTime(TimeSpan.FromSeconds(59.7)) == "00:59");
        Check("countdown formatting rounds up",
            TimerEngine.FormatTime(TimeSpan.FromSeconds(59.7), roundUp: true) == "01:00");
        Check("time formatting adds hours when needed",
            TimerEngine.FormatTime(TimeSpan.FromSeconds(3661)) == "1:01:01");
    }

    private static void RunRandomnessAnalyzerTests(string root)
    {
        var categories = Enumerable.Range(0, 10)
            .Select(index => new PopulationCategory($"结果 {index}", 1))
            .ToList();
        var population = new PopulationDefinition("测试方案", "测试模式", false, categories);

        var uniform = RandomnessAnalyzer.Analyze(
            population,
            100_000,
            new Random(20261024));
        Check("uniform random samples do not show significant frequency bias",
            !uniform.HasSignificantDeviation && uniform.UniformityPValue > 0.01);
        Check("uniform random samples pass the independence test",
            uniform.IndependencePValue is > 0.01);

        var biasedCounts = new long[] { 5000, 500, 500, 500, 500, 500, 500, 500, 500, 500 };
        var biased = RandomnessAnalyzer.AnalyzeCounts(
            population,
            biasedCounts,
            null,
            biasedCounts.Sum());
        Check("frequency analysis detects an intentionally biased distribution",
            biased.HasSignificantDeviation && biased.UniformityPValue < 0.01);

        var supplierIndex = 0;
        var biasedSupplier = RandomnessAnalyzer.AnalyzeSupplier(
            population,
            () => $"结果 {(supplierIndex++ % 10 == 0 ? 1 : 0)}",
            checked((int)biasedCounts.Sum()));
        Check("supplier analysis tests the exact current picker implementation",
            biasedSupplier.HasSignificantDeviation);

        var uniformCounts = Enumerable.Repeat(1000L, 10).ToArray();
        var dependentTransitions = new long[10, 10];
        for (var from = 0; from < 10; from++)
        {
            dependentTransitions[from, 0] = 1000;
        }
        var dependent = RandomnessAnalyzer.AnalyzeCounts(
            population,
            uniformCounts,
            dependentTransitions,
            uniformCounts.Sum());
        Check("independence analysis detects deterministic next results",
            dependent.IndependencePValue is < 0.01 && dependent.HasSignificantDeviation);

        var numberContext = new TestModeContext("数字方案", "number", numberRange: (1, 1000));
        var binnedPopulation = RandomnessAnalyzer.CreatePopulation(numberContext);
        Check("large number ranges are grouped into equal-width buckets",
            binnedPopulation.IsBinned
            && binnedPopulation.Categories.Count == RandomnessAnalyzer.NumberBucketCount
            && binnedPopulation.TotalWeight == 1000);
        var binnedSupplier = RandomnessAnalyzer.AnalyzeSupplier(
            binnedPopulation,
            () => Random.Shared.Next(1, 1001).ToString(),
            10_000);
        Check("binned supplier results map back into their number buckets",
            binnedSupplier.SampleCount == 10_000
            && binnedSupplier.Categories.Sum(category => category.Observed) == 10_000);

        var mixedContext = new TestModeContext(
            "混合方案",
            "name_list",
            names: new[] { "甲", "乙" },
            numberRange: (1, 1000));
        var currentNumberMode = RandomnessAnalyzer.CreatePopulation(mixedContext, "number");
        Check("analysis population follows the selected mode rather than scheme type",
            currentNumberMode.ModeLabel == "数字模式" && currentNumberMode.IsBinned);

        Check("chi-square p-value stays in the probability range",
            RandomnessAnalyzer.ChiSquarePValue(0, 1) == 1
            && RandomnessAnalyzer.ChiSquarePValue(10, 4) is > 0 and < 1);
        Check("chi-square p-value matches the 5% critical value",
            Math.Abs(RandomnessAnalyzer.ChiSquarePValue(3.841, 1) - 0.05) < 0.001);

        const string manifest = """
        {
          "id": "com.betterrnp.randomness-analyzer",
          "name": "BetterRNP 随机性分析插件",
          "version": "1.0.0",
          "author": "BetterRNP",
          "type": "command",
          "assembly": "BetterRNP.RandomnessAnalyzerPlugin.dll",
          "entryType": "BetterRNP.RandomnessAnalyzerPlugin.RandomnessAnalyzerPlugin",
          "apiVersion": "1.0",
          "description": "test"
        }
        """;
        var pluginAssembly = Path.Combine(
            AppContext.BaseDirectory,
            "BetterRNP.RandomnessAnalyzerPlugin.dll");
        Check("randomness analyzer plugin assembly is available", File.Exists(pluginAssembly));
        var packagePath = Path.Combine(root, "randomness-analyzer.rnpp");
        WritePluginPackage(packagePath, manifest, pluginAssembly);
        Check("randomness analyzer rnpp manifest validation",
            RnpPackager.ReadManifest(packagePath) is
            {
                Success: true,
                Manifest.Id: "com.betterrnp.randomness-analyzer"
            });

        var pluginServices = AppServices.Create(Path.Combine(root, "randomness-plugin-services"));
        using var plugins = new PluginManager(pluginServices);
        var load = plugins.LoadFromPackage(packagePath, copyIntoPluginDirectory: false);
        Check("randomness analyzer rnpp loads", load.Success);
        Check("randomness analyzer registers one current-picker command",
            plugins.Commands.Count == 1
            && plugins.Commands[0].Title == "分析抽取随机性…");
        LocalizationService.SetLanguage(LocalizationService.Japanese);
        Check("randomness analyzer command title switches language dynamically",
            plugins.Commands[0].DisplayTitle == "抽選のランダム性を分析…");
        var analyzerTitleExpectations = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["zh-Hans"] = "分析抽取随机性…",
            ["zh-Hant"] = "分析抽取隨機性…",
            ["zh-HK"] = "分析抽籤隨機性…",
            ["en-US"] = "Analyze picker randomness…",
            ["ru-RU"] = "Анализировать случайность выбора…",
            ["ja-JP"] = "抽選のランダム性を分析…"
        };
        var analyzerLanguageFailures = new List<string>();
        foreach (var (language, expected) in analyzerTitleExpectations)
        {
            LocalizationService.SetLanguage(language);
            if (plugins.Commands[0].DisplayTitle != expected)
            {
                analyzerLanguageFailures.Add(language);
            }
        }
        Check("all randomness analyzer languages replace previous titles",
            analyzerLanguageFailures.Count == 0);
        LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);
    }

    private sealed class TestModeContext : BetterRNP.PluginSdk.Abstractions.IPluginModeContext
    {
        public TestModeContext(
            string schemeName,
            string schemeType,
            IReadOnlyList<string>? names = null,
            (int Min, int Max)? numberRange = null,
            IReadOnlyList<BetterRNP.PluginSdk.Abstractions.PluginSeat>? seats = null)
        {
            SchemeName = schemeName;
            SchemeType = schemeType;
            Names = names ?? Array.Empty<string>();
            NumberRange = numberRange;
            Seats = seats ?? Array.Empty<BetterRNP.PluginSdk.Abstractions.PluginSeat>();
        }

        public string SchemeName { get; }
        public string SchemeType { get; }
        public IReadOnlyList<string> Names { get; }
        public (int Min, int Max)? NumberRange { get; }
        public IReadOnlyList<BetterRNP.PluginSdk.Abstractions.PluginSeat> Seats { get; }
        public void ShowMessage(string message)
        {
        }
    }

    private sealed class TestPickerSnapshot
        : BetterRNP.PluginSdk.Abstractions.IPluginPickerSnapshot
    {
        private readonly Func<string> _supplier;

        public TestPickerSnapshot(
            BetterRNP.PluginSdk.Abstractions.IPluginModeContext scheme,
            string modeId,
            string modeDisplayName,
            Func<string> supplier)
        {
            Scheme = scheme;
            ModeId = modeId;
            ModeDisplayName = modeDisplayName;
            _supplier = supplier;
        }

        public BetterRNP.PluginSdk.Abstractions.IPluginModeContext Scheme { get; }
        public string ModeId { get; }
        public string ModeDisplayName { get; }
        public string? ValidationError => null;
        public Func<string> CreateCandidateSupplier() => _supplier;
    }

    private static void WritePluginPackage(string packagePath, string manifest, string assemblyPath)
    {
        using var archive = ZipFile.Open(packagePath, ZipArchiveMode.Create);
        var manifestEntry = archive.CreateEntry(RnpPackager.ManifestFileName);
        using (var writer = new StreamWriter(manifestEntry.Open(), new UTF8Encoding(false)))
        {
            writer.Write(manifest);
        }
        archive.CreateEntryFromFile(assemblyPath, Path.GetFileName(assemblyPath));
    }

    private static void RunLogTests(string root)
    {
        var services = AppServices.Create(Path.Combine(root, "log-tests"));
        var log = services.Log;

        log.Write("信息内容", "TEST_INFO");
        log.Write(LogLevel.Warn, "警告内容", "TEST_WARN");
        log.Write(LogLevel.Error, "错误内容", "TEST_ERROR");

        var entries = log.ReadEntries();
        Check("structured log records operation and level",
            entries.Any(entry => entry.Operation == "TEST_INFO" && entry.Level == LogLevel.Info)
            && entries.Any(entry => entry.Operation == "TEST_WARN" && entry.Level == LogLevel.Warn)
            && entries.Any(entry => entry.Operation == "TEST_ERROR" && entry.Level == LogLevel.Error));
        Check("log entries keep their detail and timestamp",
            entries.Last(entry => entry.Operation == "TEST_WARN").Detail == "警告内容"
            && entries.Last(entry => entry.Operation == "TEST_WARN").Timestamp > DateTime.Now.AddMinutes(-5));
        Check("log level displays in Chinese",
            entries.Last(entry => entry.Operation == "TEST_WARN").LevelText == "警告");

        log.Write(LogLevel.Error, "第一行\r\n第二行", "TEST_MULTILINE");
        Check("multi-line details stay on one line",
            log.ReadEntries().Last(entry => entry.Operation == "TEST_MULTILINE").Detail
                == "第一行 ⏎ 第二行");

        try
        {
            throw new InvalidOperationException("boom");
        }
        catch (Exception ex)
        {
            log.WriteException("TEST_EXCEPTION", ex);
        }
        var exceptionEntry = log.ReadEntries().Last(entry => entry.Operation == "TEST_EXCEPTION");
        Check("exceptions are logged as errors with their type",
            exceptionEntry.Level == LogLevel.Error
            && exceptionEntry.Detail.Contains("InvalidOperationException: boom", StringComparison.Ordinal));

        File.AppendAllText(
            log.LogFile,
            "[2024-01-02 03:04:05] 【旧详情】【旧操作】" + Environment.NewLine,
            new UTF8Encoding(false));
        var legacyEntry = log.ReadEntries().Last(entry => entry.Operation == "旧操作");
        Check("legacy log lines are still readable",
            legacyEntry.Level == LogLevel.Info && legacyEntry.Detail == "旧详情");

        LogEntry? captured = null;
        void OnWritten(object? sender, LogEntry entry) => captured = entry;
        log.EntryWritten += OnWritten;
        log.Write("事件内容", "TEST_EVENT");
        log.EntryWritten -= OnWritten;
        Check("EntryWritten reports new entries",
            captured is { Operation: "TEST_EVENT", Detail: "事件内容" });

        Check("log reports a non-zero file size", log.GetFileSize() > 0);
        Check("log file can be cleared", log.Clear() && log.ReadEntries().Count == 0);
        log.Write("清空后重新记录", "TEST_AFTER_CLEAR");
        Check("logging works after a clear", log.ReadEntries().Count == 1);

        services.Schemes.AddScheme("日志方案", Scheme.NumberType);
        services.Schemes.SaveNumberRange("日志方案", new NumberRange(3, 9));
        var schemeLog = log.ReadEntries().Last(entry => entry.Operation == "SAVE_NUMBER_SUCCESS");
        Check("scheme saves log detailed context",
            schemeLog.Detail.Contains("方案=日志方案", StringComparison.Ordinal)
            && schemeLog.Detail.Contains("范围=[3,9]", StringComparison.Ordinal));

        services.Passwords.SetPassword("log-password");
        services.Passwords.Lock();
        services.Passwords.VerifyAndUnlock("wrong-password");
        var failedPassword = log.ReadEntries().Last(entry => entry.Operation == "PASSWORD_FAILED");
        Check("failed password attempts are logged as warnings",
            failedPassword.Level == LogLevel.Warn);

        var rotatePaths = new AppPaths(Path.Combine(root, "log-rotate"));
        rotatePaths.EnsureDirectories();
        var rotating = new LogService(rotatePaths) { MaxFileBytes = 600, MaxArchives = 2 };
        for (var index = 0; index < 40; index++)
        {
            rotating.Write(new string('x', 40), $"ROTATE_{index}");
        }

        Check("log rotates when it reaches the size limit",
            File.Exists(Path.Combine(rotatePaths.LogDir, "Modifylog.1.txt")));
        Check("rotation keeps only the configured archive count",
            File.Exists(Path.Combine(rotatePaths.LogDir, "Modifylog.2.txt"))
            && !File.Exists(Path.Combine(rotatePaths.LogDir, "Modifylog.3.txt")));
        Check("rotated log keeps the current file small",
            new FileInfo(rotatePaths.LogFile).Length < 1200);
        Check("rotated log still reads back entries",
            rotating.ReadEntries().Count is > 0 and < 40);
    }

    private static void Check(string name, bool condition)
    {
        if (condition)
        {
            _passed++;
            Console.WriteLine($"PASS {name}");
        }
        else
        {
            Fail(name);
        }
    }

    private static void Fail(string name)
    {
        _failed++;
        Console.WriteLine($"FAIL {name}");
    }

    private static void RunWindowConstructionTests(string root)
    {
        var application = new App(suppressStartup: true);
        application.InitializeComponent();
        try
        {
            RunWindowTestsCore(application, root);
        }
        catch (Exception ex)
        {
            Fail($"window test host ({ex.GetType().Name}: {ex.Message})");
        }
        finally
        {
            application.Shutdown();
        }
    }

    private static void RunWindowTestsCore(App application, string root)
    {
        var services = AppServices.Create(root);
        LocalizationService.SetLanguage(LocalizationService.English);
        var localizationWindow = new AboutWindow();
        try
        {
            Check("localization applies to newly loaded windows",
                localizationWindow.Title == "About BetterRNP"
                && ((TextBlock)localizationWindow.FindName("RepositoryLabel")).Text
                    == "Repository:");
        }
        finally
        {
            LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);
        }
        using var pluginManager = new PluginManager(services);
        var updatePrompt = UpdateService.EvaluateReleaseJson("""
        {
          "tag_name": "v2.0.1",
          "html_url": "https://github.com/TakaHoshino/BetterRandomNamePicker/releases/tag/v2.0.1",
          "body": "test",
          "assets": []
        }
        """, new Version(2, 0, 0));
        var windows = new Window[]
        {
            new NameConfigWindow(services),
            new SchemeManagerWindow(services),
            new NumberSettingsWindow(services, "默认方案"),
            new SeatSettingsWindow(services, "默认方案"),
            new SettingsWindow(services),
            new LogWindow(services),
            new ThemeWindow(services),
            new PluginManagerWindow(pluginManager),
            new UpdatePromptWindow(updatePrompt),
            new PasswordWindow(services, "密码验证", "请输入密码："),
            new ChangePasswordWindow(services),
            new TimerWindow(TimerMode.CountUp, TimeSpan.Zero),
            new CountdownSetupWindow(),
            new AboutWindow()
        };

        foreach (var window in windows)
        {
            try
            {
                window.Show();
                window.Dispatcher.Invoke(
                    () => { },
                    System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                window.Close();
                Check($"window constructs: {window.GetType().Name}", true);
            }
            catch (Exception ex)
            {
                Fail($"window constructs: {window.GetType().Name} ({ex.GetType().Name}: {ex.Message})");
            }
        }

        var settingsServices = AppServices.Create(Path.Combine(root, "settings-layout"));
        using var settingsPlugins = new PluginManager(settingsServices);
        var settingsDemoPackage = Path.Combine(root, "settings-demo.rnpp");
        WritePluginPackage(
            settingsDemoPackage,
            """
            {
              "id": "com.betterrnp.demo",
              "name": "BetterRNP 示例插件",
              "version": "1.0.0",
              "author": "BetterRNP",
              "type": "mixed",
              "assembly": "BetterRNP.DemoPlugin.dll",
              "entryType": "BetterRNP.DemoPlugin.DemoPlugin",
              "apiVersion": "1.0",
              "description": "test"
            }
            """,
            Path.Combine(AppContext.BaseDirectory, "BetterRNP.DemoPlugin.dll"));
        settingsPlugins.LoadFromPackage(settingsDemoPackage, copyIntoPluginDirectory: false);
        var settingsWindow = new SettingsWindow(settingsServices, settingsPlugins);
        try
        {
            settingsWindow.Show();
            settingsWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            var pluginsNavigation = (TreeViewItem)settingsWindow.FindName("PluginsNavigation");
            var languageCombo = (ComboBox)settingsWindow.FindName("LanguageCombo");
            Check("settings expose all supported languages",
                languageCombo.Items.Count == LocalizationService.SupportedLanguages.Count);
            var pluginSettingNavigation = pluginsNavigation.Items
                .OfType<TreeViewItem>()
                .FirstOrDefault();
            Check("settings navigation lists plugin settings", pluginSettingNavigation is not null);
            if (pluginSettingNavigation is not null)
            {
                pluginSettingNavigation.IsSelected = true;
                settingsWindow.Dispatcher.Invoke(
                    () => { },
                    System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                var host = (ContentControl)settingsWindow.FindName("PluginSettingsHost");
                Check("plugin settings view is embedded in settings window",
                    host.Visibility == Visibility.Visible && host.Content is FrameworkElement);
            }
            var interfaceNavigation = (TreeViewItem)settingsWindow.FindName("InterfaceNavigation");
            interfaceNavigation.IsSelected = true;
            settingsWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Check("settings navigation switches core pages",
                ((TextBlock)settingsWindow.FindName("SettingsPageTitle")).Text == "界面");
            languageCombo.SelectedItem = languageCombo.Items
                .OfType<ComboBoxItem>()
                .First(item => item.Tag?.ToString() == LocalizationService.English);
            settingsWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            var localizedPageTitle = ((TextBlock)settingsWindow.FindName("SettingsPageTitle")).Text;
            Check("language change updates the open settings window immediately",
                localizedPageTitle == "Interface");
            languageCombo.SelectedItem = languageCombo.Items
                .OfType<ComboBoxItem>()
                .First(item => item.Tag?.ToString() == LocalizationService.SimplifiedChinese);
            settingsWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            var securityNavigation = (TreeViewItem)settingsWindow.FindName("SecurityNavigation");
            securityNavigation.IsSelected = true;
            settingsWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Check("security settings link to README instructions",
                ((TextBlock)settingsWindow.FindName("SecurityDocumentationText"))
                    .Text.Contains("README.md", StringComparison.Ordinal)
                && ((Button)settingsWindow.FindName("OpenReadmeButton")).IsVisible);
            Check("security settings show optional-password state",
                ((TextBlock)settingsWindow.FindName("PasswordStatusText"))
                    .Text.Contains("未设置密码", StringComparison.Ordinal)
                && ((CheckBox)settingsWindow.FindName("RecoveryShortcutCheck")).IsEnabled == false
                && ((Button)settingsWindow.FindName("LockButton")).Visibility == Visibility.Collapsed
                && ((Button)settingsWindow.FindName("RemovePasswordButton")).Visibility == Visibility.Collapsed);
            var radiusSlider = (Slider)settingsWindow.FindName("RadiusSlider");
            var opacitySlider = (Slider)settingsWindow.FindName("OpacitySlider");
            Check("floating ball sliders are continuous",
                !radiusSlider.IsSnapToTickEnabled
                && !opacitySlider.IsSnapToTickEnabled);
            radiusSlider.Value = 70;
            opacitySlider.Value = 120;
            var resetButton = (Button)settingsWindow.FindName("ResetBallDefaultsButton");
            resetButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            settingsWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Check("reset restores floating ball defaults",
                radiusSlider.Value == ConfigService.FloatingBallRadiusDefault
                && opacitySlider.Value == ConfigService.FloatingBallOpacityDefault
                && settingsServices.Config.FloatingBallRadius == ConfigService.FloatingBallRadiusDefault
                && settingsServices.Config.FloatingBallOpacity == ConfigService.FloatingBallOpacityDefault);
            settingsWindow.Close();
        }
        catch (Exception ex)
        {
            settingsWindow.Close();
            Fail($"settings navigation integration ({ex.GetType().Name}: {ex.Message})");
        }

        var cropSource = services.Theme.ListImages().FirstOrDefault();
        if (cropSource is not null)
        {
            var cropWindow = new CropImageWindow(cropSource.Path, new Rect(0.1, 0.1, 0.5, 0.5));
            try
            {
                cropWindow.Show();
                cropWindow.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                Check("crop window constructs with normalized selection",
                    Math.Abs(cropWindow.SelectedCrop.Width - 0.5) < 0.001);
                cropWindow.Close();
            }
            catch (Exception ex)
            {
                Fail($"crop window constructs ({ex.GetType().Name}: {ex.Message})");
            }
        }

        var analysisWindow = new RandomnessAnalysisWindow(
            new TestModeContext(
                "随机性测试方案",
                "name_list",
                names: new[] { "张三", "李四", "王五" }));
        try
        {
            analysisWindow.Show();
            analysisWindow.Dispatcher.Invoke(
                () => { },
                System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Check("window constructs: RandomnessAnalysisWindow",
                analysisWindow.Title == "随机性分析");
            analysisWindow.Close();
        }
        catch (Exception ex)
        {
            Fail($"window constructs: RandomnessAnalysisWindow ({ex.GetType().Name}: {ex.Message})");
        }

        var schemeContext = new TestModeContext(
            "插件随机性方案",
            "name_list",
            names: new[] { "甲", "乙", "丙", "丁" });
        var pickerSnapshot = new TestPickerSnapshot(
            schemeContext,
            "name_list",
            "名字列表模式",
            () => schemeContext.Names[Random.Shared.Next(schemeContext.Names.Count)]);
        using var analyzerPlugins = new PluginManager(
            services,
            () => schemeContext,
            () => pickerSnapshot);
        var analyzerLoad = analyzerPlugins.LoadFromPackage(
            Path.Combine(root, "randomness-analyzer.rnpp"),
            copyIntoPluginDirectory: false);
        if (analyzerLoad.Success && analyzerPlugins.Commands.Count == 1)
        {
            LocalizationService.SetLanguage(LocalizationService.English);
            analyzerPlugins.ExecuteCommand(analyzerPlugins.Commands[0], analysisWindow);
            var openedAnalysis = (Application.Current?.Windows.Cast<Window>() ?? [])
                .Where(window => window.GetType().FullName
                    == "BetterRNP.RandomnessAnalyzerPlugin.Views.RandomnessAnalysisWindow")
                .ToList();
            Check("current-picker command opens randomness analysis",
                openedAnalysis.Count == 1);
            Check("randomness analyzer window follows the host language",
                openedAnalysis.Count == 1
                && openedAnalysis[0].Title == "Randomness analysis");
            foreach (var opened in openedAnalysis)
            {
                opened.Close();
            }
            LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);
        }
        else
        {
            Fail("current-picker command opens randomness analysis (plugin load failed)");
        }

        CheckCountdownDialog();
        using (var trayService = new NativeTrayService(() => { }, () => { }, () => { }, () => { }))
        {
            trayService.Install();
            Check("native tray host becomes visible", trayService.IsInstalled);
        }
        CheckFloatingBallMenu(application, root);
    }

    private static void CheckCountdownDialog()
    {
        var setup = new CountdownSetupWindow();
        setup.Dispatcher.BeginInvoke(
            new Action(() =>
            {
                typeof(CountdownSetupWindow)
                    .GetMethod("Confirm", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!
                    .Invoke(setup, null);
            }),
            System.Windows.Threading.DispatcherPriority.Normal);

        var picked = setup.PickDuration();
        Check("countdown dialog confirms the default five minutes",
            picked == TimeSpan.FromMinutes(5));
    }

    private static void CheckFloatingBallMenu(App application, string root)
    {
        var services = AppServices.Create(Path.Combine(root, "ball-menu"));
        Directory.CreateDirectory(services.Paths.PluginsDir);
        File.Copy(
            Path.Combine(root, "timer.rnpp"),
            Path.Combine(services.Paths.PluginsDir, "timer.rnpp"),
            overwrite: true);

        var shell = new MainWindow(services);
        typeof(App).GetProperty(nameof(App.ShellWindow))!.SetValue(application, shell);
        var noPasswordWarning = (TextBlock)shell.FindName("NoPasswordWarningText");
        Check("main window warns when no password is configured",
            noPasswordWarning.Visibility == Visibility.Visible
            && noPasswordWarning.FontSize == 11);
        services.Passwords.SetPassword("ball-menu-password");
        shell.RefreshLanguage();
        Check("main window hides password warning after password is set",
            noPasswordWarning.Visibility == Visibility.Collapsed);
        services.Passwords.RemovePassword();
        shell.RefreshLanguage();
        Check("main window restores warning after password removal",
            noPasswordWarning.Visibility == Visibility.Visible);

        var ball = new FloatingBallWindow(services, application);
        ball.Show();
        ball.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        var ballStyle = GetWindowLongPtr(new WindowInteropHelper(ball).Handle, GWL_EXSTYLE).ToInt64();
        Check("floating ball is hidden from Alt+Tab",
            (ballStyle & WS_EX_TOOLWINDOW) != 0 && (ballStyle & WS_EX_APPWINDOW) == 0);
        Check("floating ball does not steal window focus",
            (ballStyle & WS_EX_NOACTIVATE) != 0);
        FloatingBallRuntimeState? publishedState = null;
        EventHandler<FloatingBallRuntimeState> stateHandler = (_, state) => publishedState = state;
        services.FloatingBallState.StateChanged += stateHandler;
        ball.UpdateAppearance(64, 128);
        ball.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        services.FloatingBallState.StateChanged -= stateHandler;
        Check("floating ball publishes live geometry and color",
            publishedState is
            {
                Width: > 0,
                Height: > 0,
                ColorHex: "#0078D7",
                IsVisible: true
            });

        var items = ball.ContextMenu.Items.OfType<MenuItem>().ToList();
        var headers = items.Select(item => item.Header?.ToString()).ToList();
        Check("floating ball menu lists plugin commands",
            headers.Contains("正计时") && headers.Contains("倒计时"));
        Check("plugin commands sit before the ball settings entry",
            headers.IndexOf("正计时") < headers.IndexOf("悬浮球设置")
            && headers.IndexOf("倒计时") < headers.IndexOf("悬浮球设置"));

        var countUp = items.First(item => (string?)item.Header == "正计时");
        countUp.RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent));
        // 插件运行在独立的 AssemblyLoadContext 中，其 TimerWindow 类型与测试程序集的不同，
        // 因此按类型全名查找。
        var opened = Application.Current.Windows
            .Cast<Window>()
            .Where(window => window.GetType().FullName == "BetterRNP.TimerPlugin.Views.TimerWindow")
            .ToList();
        Check("floating ball command opens the timer window", opened.Count == 1);
        foreach (var window in opened)
        {
            window.Close();
        }

        LocalizationService.SetLanguage(LocalizationService.English);
        shell.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        var englishTheme = FindMenuItem(shell, "Theme");
        var englishThemeSubmenu = FindMenuItem(shell, "Enable photo background");
        LocalizationService.SetLanguage(LocalizationService.Russian);
        shell.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
        var russianTheme = FindMenuItem(shell, "Тема");
        var russianThemeSubmenu = FindMenuItem(shell, "Включить фоновое изображение");
        Check("runtime language switch updates static menu items",
            englishTheme is not null
            && englishThemeSubmenu is not null
            && russianTheme is not null
            && russianThemeSubmenu is not null);
        LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);

        ball.Close();
        shell.ShutdownPlugins();
        shell.Close();
    }

    private static MenuItem? FindMenuItem(DependencyObject root, string header)
    {
        if (root is MenuItem item && string.Equals(item.Header as string, header, StringComparison.Ordinal))
        {
            return item;
        }
        foreach (var child in LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>())
        {
            var match = FindMenuItem(child, header);
            if (match is not null)
            {
                return match;
            }
        }
        if (root is Visual or System.Windows.Media.Media3D.Visual3D)
        {
            for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            {
                var match = FindMenuItem(VisualTreeHelper.GetChild(root, index), header);
                if (match is not null)
                {
                    return match;
                }
            }
        }
        return null;
    }

    private static void RunPluginTests(string root)
    {
        const string manifest = """
        {
          "id": "com.betterrnp.demo",
          "name": "BetterRNP 示例插件",
          "version": "1.0.0",
          "author": "BetterRNP",
          "type": "mixed",
          "assembly": "BetterRNP.DemoPlugin.dll",
          "entryType": "BetterRNP.DemoPlugin.DemoPlugin",
          "apiVersion": "1.0",
          "description": "test"
        }
        """;

        var pluginAssembly = Path.Combine(AppContext.BaseDirectory, "BetterRNP.DemoPlugin.dll");
        Check("demo plugin assembly is available", File.Exists(pluginAssembly));
        var packagePath = Path.Combine(root, "demo.rnpp");
        WritePluginPackage(packagePath, manifest, pluginAssembly);

        Check("rnpp manifest validation",
            RnpPackager.ReadManifest(packagePath) is { Success: true, Manifest.Id: "com.betterrnp.demo" });
        var pickerManifestValidation = ManifestValidator.TryParse("""
        {
          "id": "com.example.picker",
          "name": "Picker",
          "type": "pickerMode",
          "assembly": "Picker.dll",
          "entryType": "Picker.Entry",
          "apiVersion": "1.0"
        }
        """, out var pickerManifest);
        Check("pickerMode manifest type is accepted",
            pickerManifestValidation.IsValid && pickerManifest?.Type == PluginType.PickerMode);
        var wrongExtensionPackage = Path.ChangeExtension(packagePath, ".zip");
        File.Copy(packagePath, wrongExtensionPackage);
        Check("wrong plugin extension is rejected",
            !RnpPackager.ReadManifest(wrongExtensionPackage).Success);

        var services = AppServices.Create(root);
        using var plugins = new PluginManager(services);
        Check("built-in core plugin is listed",
            plugins.Plugins.Any(p => p.BuiltIn && p.Manifest.Id == "betterrnp.core"));

        var load = plugins.LoadFromPackage(packagePath, copyIntoPluginDirectory: false);
        Check("demo rnpp loads", load.Success);
        Check("plugin command registers", plugins.Commands.Count == 1);
        Check("plugin mode registers", plugins.Modes.Count == 1);
        Check("plugin mode display name",
            plugins.Modes[0].Mode.DisplayName == "插件示例模式");
        Check("plugin registers an embeddable settings item",
            plugins.SettingsItems.Count == 1
            && plugins.SettingsItems[0].Title == "示例插件设置"
            && plugins.SettingsItems[0].ViewFactory() is FrameworkElement);

        var localizedSettingsView = (StackPanel)plugins.SettingsItems[0].ViewFactory();
        LocalizationService.SetLanguage(LocalizationService.English);
        Check("plugin command titles switch language dynamically",
            plugins.Commands[0].DisplayTitle == "Demo plugin information");
        Check("plugin settings title switches language dynamically",
            plugins.SettingsItems[0].DisplayTitle == "Demo plugin settings");
        Check("plugin mode names switch language dynamically",
            plugins.Modes[0].Mode.DisplayName == "Demo picker mode");
        var pluginTitleExpectations = new Dictionary<string, (string Command, string Settings, string Mode)>(
            StringComparer.Ordinal)
        {
            ["zh-Hans"] = ("示例插件说明", "示例插件设置", "插件示例模式"),
            ["zh-Hant"] = ("外掛範例說明", "範例外掛設定", "外掛範例模式"),
            ["zh-HK"] = ("外掛例子說明", "例子外掛設定", "外掛例子模式"),
            ["en-US"] = ("Demo plugin information", "Demo plugin settings", "Demo picker mode"),
            ["ru-RU"] = ("Информация о демо-плагине", "Настройки демо-плагина", "Демонстрационный режим"),
            ["ja-JP"] = ("プラグインの説明", "デモプラグイン設定", "デモ抽選モード")
        };
        var pluginLanguageFailures = new List<string>();
        foreach (var (language, expected) in pluginTitleExpectations)
        {
            LocalizationService.SetLanguage(language);
            if (plugins.Commands[0].DisplayTitle != expected.Command
                || plugins.SettingsItems[0].DisplayTitle != expected.Settings
                || plugins.Modes[0].Mode.DisplayName != expected.Mode)
            {
                pluginLanguageFailures.Add(language);
            }
        }
        Check(
            "all plugin languages replace previous plugin titles",
            pluginLanguageFailures.Count == 0);
        LocalizationService.SetLanguage(LocalizationService.English);
        Check("plugin manager row metadata switches language",
            plugins.Plugins[0].TypeLabel == "Core"
            && plugins.Plugins[0].SourceLabel == "Built-in"
            && plugins.Plugins[1].TypeLabel == "Mixed"
            && plugins.Plugins[1].StateLabel == "Enabled"
            && plugins.Plugins[1].ToggleText == "Disable");
        var rowExpectations = new Dictionary<string, (string Core, string BuiltIn, string Mixed, string Enabled, string Disable)>(
            StringComparer.Ordinal)
        {
            ["zh-Hans"] = ("核心", "内置", "混合", "已启用", "禁用"),
            ["zh-Hant"] = ("核心", "內建", "混合", "已啟用", "停用"),
            ["zh-HK"] = ("核心", "內建", "混合", "已啟用", "停用"),
            ["en-US"] = ("Core", "Built-in", "Mixed", "Enabled", "Disable"),
            ["ru-RU"] = ("Ядро", "Встроенный", "Смешанный", "Включён", "Отключить"),
            ["ja-JP"] = ("コア", "組み込み", "複合", "有効", "無効化")
        };
        var rowLanguageFailures = new List<string>();
        foreach (var (language, expected) in rowExpectations)
        {
            LocalizationService.SetLanguage(language);
            if (plugins.Plugins[0].TypeLabel != expected.Core
                || plugins.Plugins[0].SourceLabel != expected.BuiltIn
                || plugins.Plugins[1].TypeLabel != expected.Mixed
                || plugins.Plugins[1].StateLabel != expected.Enabled
                || plugins.Plugins[1].ToggleText != expected.Disable)
            {
                rowLanguageFailures.Add(language);
            }
        }
        Check("all languages replace previous plugin manager row metadata",
            rowLanguageFailures.Count == 0);
        LocalizationService.SetLanguage(LocalizationService.English);
        Check("plugin settings view receives language change notifications",
            localizedSettingsView.Children
                .OfType<TextBlock>()
                .Any(text => text.Text.Contains("This settings page", StringComparison.Ordinal)));
        if (localizedSettingsView is IDisposable disposableView)
        {
            disposableView.Dispose();
        }
        LocalizationService.SetLanguage(LocalizationService.SimplifiedChinese);

        var loaded = load.Info!;
        Check("plugin can be disabled", plugins.SetEnabled(loaded.Manifest.Id, false));
        Check("disabled plugin hides commands and modes",
            plugins.Commands.Count == 0
            && plugins.Modes.Count == 0
            && plugins.SettingsItems.Count == 0);
        Check("plugin can be enabled", plugins.SetEnabled(loaded.Manifest.Id, true));
        Check("enabled plugin restores commands and modes",
            plugins.Commands.Count == 1
            && plugins.Modes.Count == 1
            && plugins.SettingsItems.Count == 1);
        Check("external plugin removal", plugins.RemoveExternal(loaded.Manifest.Id));
        Check("removed plugin leaves only built-in",
            plugins.Plugins.Count == 1 && plugins.Plugins[0].BuiltIn);
    }

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr GetWindowLongPtr(IntPtr window, int index);
}
