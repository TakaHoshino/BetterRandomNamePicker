using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Navigation;
using BetterRNP.Core;

namespace BetterRNP.Views;

public partial class AboutWindow : Window
{
    private readonly Version _version;
    private readonly string _productVersion;
    private readonly DateTime _buildDate;
    private readonly string _installType;
    private string _aboutText = string.Empty;

    public AboutWindow()
    {
        InitializeComponent();

        _version = typeof(UpdateService).Assembly.GetName().Version
                   ?? UpdateService.GetCurrentVersion();
        _productVersion = UpdateService.GetCurrentTag();
        _buildDate = GetBuildTime();
        _installType = UpdateService.DetectCurrentInstallationKind()
            == UpdateAssetKind.SelfContained
                ? UiText.SelfContained
                : UiText.FrameworkDependent;
        UpdateTexts();
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += (_, _) =>
            LocalizationService.LanguageChanged -= OnLanguageChanged;
    }

    private void UpdateTexts()
    {
        ProductTitle.Text =
            LocalizationService.Format(UiText.Betterrnp0Release, _productVersion.TrimStart('v'));
        InternalVersionText.Text =
            LocalizationService.Format(
                UiText.InternalVersionFormat,
                _version,
                _buildDate);
        RuntimeText.Text = LocalizationService.Format(
            UiText.Runtime01,
            RuntimeInformation.FrameworkDescription,
            RuntimeInformation.ProcessArchitecture);
        EnvironmentText.Text = LocalizationService.Format(
            UiText.InterfaceSystemFormat,
            Environment.OSVersion.VersionString);
        InstallTypeText.Text = LocalizationService.Format(
            UiText.InstallationType0,
            LocalizationService.Get(_installType));

        _aboutText = new StringBuilder()
            .AppendLine(ProductTitle.Text)
            .AppendLine(InternalVersionText.Text)
            .AppendLine()
            .AppendLine(RuntimeText.Text)
            .AppendLine(EnvironmentText.Text)
            .AppendLine(InstallTypeText.Text)
            .AppendLine()
            .AppendLine(LocalizationService.Get(UiText.Repository) + UpdateService.RepositoryUrl)
            .AppendLine(LocalizationService.Get(
                UiText.Copyright2026BetterrnpContributorsApacheLicense20))
            .ToString();
    }

    private void OnLanguageChanged(object? sender, EventArgs e) => UpdateTexts();

    private static DateTime GetBuildTime()
    {
        try
        {
            var executable = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(executable) && File.Exists(executable))
            {
                return File.GetLastWriteTime(executable);
            }
        }
        catch
        {
            // Build time is informational only.
        }
        return DateTime.Today;
    }

    private void RepositoryLink_RequestNavigate(
        object sender,
        RequestNavigateEventArgs e)
    {
        Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri)
        {
            UseShellExecute = true
        });
        e.Handled = true;
    }

    private void CopyAndClose_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(_aboutText);
        }
        catch
        {
            MessageBox.Show(this, UiText.CopyVersionInfoFailed, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        Close();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
