using System.Text;
using System.IO.Compression;
using System.Windows;
using BetterRNP.Core;
using BetterRNP.Models;
using BetterRNP.Modes;
using BetterRNP.Plugins;
using BetterRNP.PluginSdk.Packaging;
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
        var root = Path.Combine(Path.GetTempPath(), "BetterRNP.Tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            RunCoreTests(root);
            RunPluginTests(root);
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
        var services = AppServices.Create(root);
        using var pluginManager = new PluginManager(services);
        var windows = new Window[]
        {
            new NameConfigWindow(services),
            new SchemeManagerWindow(services),
            new NumberSettingsWindow(services, "默认方案"),
            new SeatSettingsWindow(services, "默认方案"),
            new SettingsWindow(services),
            new ThemeWindow(services),
            new PluginManagerWindow(pluginManager),
            new PasswordWindow(services, "密码验证", "请输入密码："),
            new ChangePasswordWindow(services)
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
        application.Shutdown();
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
        using (var archive = ZipFile.Open(packagePath, ZipArchiveMode.Create))
        {
            var manifestEntry = archive.CreateEntry(RnpPackager.ManifestFileName);
            using (var writer = new StreamWriter(manifestEntry.Open(), new UTF8Encoding(false)))
            {
                writer.Write(manifest);
            }
            archive.CreateEntryFromFile(pluginAssembly, "BetterRNP.DemoPlugin.dll");
        }

        Check("rnpp manifest validation",
            RnpPackager.ReadManifest(packagePath) is { Success: true, Manifest.Id: "com.betterrnp.demo" });
        var renamedFep = Path.ChangeExtension(packagePath, ".fep");
        File.Copy(packagePath, renamedFep);
        Check("fep extension is rejected", !RnpPackager.ReadManifest(renamedFep).Success);

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
