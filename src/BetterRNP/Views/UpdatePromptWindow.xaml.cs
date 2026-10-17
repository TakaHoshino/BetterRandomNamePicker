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
        SummaryText.Text =
            $"发现新版本 {result.LatestTag}（当前 {UpdateService.GetCurrentTag()}），是否更新？";
        ReleaseNotesBox.Text = string.IsNullOrWhiteSpace(result.ReleaseNotes)
            ? "本次 Release 未提供说明。"
            : result.ReleaseNotes;
        SelfContainedSizeText.Text = FormatSize(result.SelfContainedAsset?.SizeBytes);
        FrameworkSizeText.Text = FormatSize(result.FrameworkDependentAsset?.SizeBytes);
        var detectedKind = UpdateService.DetectCurrentInstallationKind();
        DetectedTypeText.Text = detectedKind == UpdateAssetKind.SelfContained
            ? "已检测到当前为自包含版，默认选择自包含更新包。"
            : "已检测到当前为框架依赖版，默认选择框架依赖更新包。";

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
        => bytes is > 0 ? $"（约 {bytes.Value / 1048576.0:F0} MB）" : string.Empty;

    private void UpdateButton_Click(object sender, RoutedEventArgs e)
    {
        SelectedKind = SelfContainedRadio.IsChecked == true
            ? UpdateAssetKind.SelfContained
            : UpdateAssetKind.FrameworkDependent;
        if (SelectedAsset is null)
        {
            MessageBox.Show(this, "所选安装包尚未上传到 Release。", "检查更新",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        DialogResult = true;
    }
}
