using System.Windows;
using BetterRNP.Core;

namespace BetterRNP.Views;

public partial class UpdatePromptWindow : Window
{
    private readonly UpdateCheckResult _result;

    public UpdatePromptWindow(UpdateCheckResult result)
    {
        _result = result;
        InitializeComponent();
        SummaryText.Text = LocalizationService.Format(
            UiText.UpdateAvailableQuestionFormat,
            result.LatestTag,
            UpdateService.GetCurrentTag());
        ReleaseNotesBox.Text = string.IsNullOrWhiteSpace(result.ReleaseNotes)
            ? LocalizationService.Get(UiText.NoReleaseNotes)
            : result.ReleaseNotes;
        SelfContainedSizeText.Text = FormatSize(result.SelfContainedAsset?.SizeBytes);
        FrameworkSizeText.Text = FormatSize(result.FrameworkDependentAsset?.SizeBytes);
        var detectedKind = UpdateService.DetectCurrentInstallationKind();
        DetectedTypeText.Text = detectedKind == UpdateAssetKind.SelfContained
            ? LocalizationService.Get(UiText.SelfContainedDetected)
            : LocalizationService.Get(UiText.FrameworkDependentDetected);

        SelfContainedRadio.IsEnabled = result.SelfContainedAsset is not null;
        FrameworkRadio.IsEnabled = result.FrameworkDependentAsset is not null;
        if (detectedKind == UpdateAssetKind.SelfContained
            && result.SelfContainedAsset is not null)
        {
            SelfContainedRadio.IsChecked = true;
            SelectedKind = UpdateAssetKind.SelfContained;
        }
        else if (result.FrameworkDependentAsset is not null)
        {
            FrameworkRadio.IsChecked = true;
            SelectedKind = UpdateAssetKind.FrameworkDependent;
        }
        else if (result.SelfContainedAsset is not null)
        {
            SelfContainedRadio.IsChecked = true;
            SelectedKind = UpdateAssetKind.SelfContained;
        }
        UpdateButton.IsEnabled = result.SelfContainedAsset is not null
                                 || result.FrameworkDependentAsset is not null;
    }

    public UpdateAssetKind SelectedKind { get; private set; }

    public UpdateAsset? SelectedAsset => SelectedKind == UpdateAssetKind.SelfContained
        ? _result.SelfContainedAsset
        : _result.FrameworkDependentAsset;

    private static string FormatSize(long? bytes)
        => bytes is > 0
            ? LocalizationService.Format(UiText.EstimatedSizeFormat, bytes.Value / 1048576.0)
            : string.Empty;

    private void UpdateButton_Click(object sender, RoutedEventArgs e)
    {
        SelectedKind = SelfContainedRadio.IsChecked == true
            ? UpdateAssetKind.SelfContained
            : UpdateAssetKind.FrameworkDependent;
        if (SelectedAsset is null)
        {
            MessageBox.Show(this, UiText.SelectedInstallerNotUploaded, UiText.CheckForUpdates,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        DialogResult = true;
    }
}
