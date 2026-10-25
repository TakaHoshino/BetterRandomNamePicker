using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Windows;
using BetterRNP.Core;

namespace BetterRNP.Views;

public static class UpdatePresenter
{
    public static async Task<UpdateCheckResult?> CheckAsync(
        Window owner,
        AppServices services,
        bool userInitiated)
    {
        try
        {
            var result = await services.Update.CheckAsync();
            if (!result.UpdateAvailable)
            {
                services.Log.Write($"当前已是最新版本 {UpdateService.GetCurrentTag()}", "UPDATE_CHECK");
                if (userInitiated)
                {
                    MessageBox.Show(
                        owner,
                        LocalizationService.Format(
                            UiText.LatestVersionMessageFormat,
                            UpdateService.GetCurrentTag()),
                        UiText.CheckForUpdates,
                        MessageBoxButton.OK,
                        MessageBoxImage.Information);
                }
                return result;
            }

            services.Log.Write($"发现新版本 {result.LatestTag}", "UPDATE_FOUND");
            var prompt = new UpdatePromptWindow(result) { Owner = owner };
            if (prompt.ShowDialog() != true || prompt.SelectedAsset is null)
            {
                return result;
            }

            new UpdateWindow(services, result, prompt.SelectedAsset) { Owner = owner }.ShowDialog();
            return result;
        }
        catch (Exception ex) when (ex is HttpRequestException
                                   || ex is TaskCanceledException
                                   || ex is OperationCanceledException)
        {
            services.Log.Write(LogLevel.Error, $"检查更新失败: {ex.Message}", "UPDATE_CHECK_ERROR");
            if (userInitiated)
            {
                MessageBox.Show(owner,
                    UiText.UpdateCheckNetworkFailure,
                    UiText.CheckForUpdates,
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning);
            }
            return null;
        }
        catch (Exception ex)
        {
            services.Log.Write(LogLevel.Error, $"检查更新失败: {ex}", "UPDATE_CHECK_ERROR");
            if (userInitiated)
            {
                var detail = ex is HttpRequestException { StatusCode: HttpStatusCode.NotFound }
                    ? LocalizationService.Get(UiText.NoPublishedRelease)
                    : ex.Message;
                MessageBox.Show(
                    owner,
                    LocalizationService.Format(UiText.UpdateCheckFailureFormat, detail),
                    UiText.CheckForUpdates,
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
            return null;
        }
    }

    public static void OpenReleasePage(UpdateCheckResult result)
    {
        try
        {
            Process.Start(new ProcessStartInfo(result.ReleasePageUri.AbsoluteUri)
            {
                UseShellExecute = true
            });
        }
        catch
        {
            // Opening the browser is best effort.
        }
    }
}
