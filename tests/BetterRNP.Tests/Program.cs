using System.Text;
using System.IO.Compression;
using System.Windows;
using System.Windows.Controls;
using BetterRNP.Core;
using BetterRNP.Models;
using BetterRNP.Modes;
using BetterRNP.Plugins;
using BetterRNP.PluginSdk.Models;
using BetterRNP.PluginSdk.Packaging;
using BetterRNP.TimerPlugin.Timing;
using BetterRNP.TimerPlugin.Views;
using BetterRNP.Views;

namespace BetterRNP.Tests;

internal static class Program
{
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
            RunLogTests(root);
            RunPluginTests(root);
            RunTimerPluginTests(root);
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
        var services = AppServices.Create(root);

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

        Check("default password verifies",
            services.Passwords.VerifyAndUnlock(PasswordService.BuiltInDefaultPassword));
        Check("password change",
            services.Passwords.ChangePassword("better-pass-2026"));
        services.Passwords.Lock();
        Check("old password rejected after change",
            !services.Passwords.VerifyAndUnlock(PasswordService.BuiltInDefaultPassword));
        Check("new password accepted",
            services.Passwords.VerifyAndUnlock("better-pass-2026"));

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
        var application = new App();
        application.InitializeComponent();
        using (var trayService = new NativeTrayService(() => { }, () => { }, () => { }, () => { }))
        {
            trayService.Install();
            Check("native tray host becomes visible", trayService.IsInstalled);
        }
        var services = AppServices.Create(root);
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
            new CountdownSetupWindow()
        };

        foreach (var window in windows)
        {
            try
            {
                window.Show();
                window.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                window.Close();
                Check($"window constructs: {window.GetType().Name}", true);
            }
            catch (Exception ex)
            {
                Fail($"window constructs: {window.GetType().Name} ({ex.GetType().Name}: {ex.Message})");
            }
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
        CheckFloatingBallMenu(application, root);
        CheckCountdownDialog();
        application.Shutdown();
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

        var ball = new FloatingBallWindow(services, application);
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

        ball.Close();
        shell.ShutdownPlugins();
        shell.Close();
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

        var loaded = load.Info!;
        Check("plugin can be disabled", plugins.SetEnabled(loaded.Manifest.Id, false));
        Check("disabled plugin hides commands and modes",
            plugins.Commands.Count == 0 && plugins.Modes.Count == 0);
        Check("plugin can be enabled", plugins.SetEnabled(loaded.Manifest.Id, true));
        Check("enabled plugin restores commands and modes",
            plugins.Commands.Count == 1 && plugins.Modes.Count == 1);
        Check("external plugin removal", plugins.RemoveExternal(loaded.Manifest.Id));
        Check("removed plugin leaves only built-in",
            plugins.Plugins.Count == 1 && plugins.Plugins[0].BuiltIn);
    }
}
