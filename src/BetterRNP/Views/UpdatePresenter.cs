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
                    MessageBox.Show(owner,
                        $"当前已是最新版本（{UpdateService.GetCurrentTag()}）。",
                        "检查更新", MessageBoxButton.OK, MessageBoxImage.Information);
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
                    "检查更新失败：网络异常或请求超时。\n可在设置中切换为 gh-proxy 代理源后重试。",
                    "检查更新", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            return null;
        }
        catch (Exception ex)
        {
            services.Log.Write(LogLevel.Error, $"检查更新失败: {ex}", "UPDATE_CHECK_ERROR");
            if (userInitiated)
            {
                var detail = ex is HttpRequestException { StatusCode: HttpStatusCode.NotFound }
                    ? "GitHub 仓库尚未发布正式 Release。"
                    : ex.Message;
                MessageBox.Show(owner, $"检查更新失败：{detail}", "检查更新",
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
