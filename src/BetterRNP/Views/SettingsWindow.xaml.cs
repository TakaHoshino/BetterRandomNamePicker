using System.Windows;
using BetterRNP.Core;
using Microsoft.Win32;

namespace BetterRNP.Views;

public partial class SettingsWindow : Window
{
    private readonly AppServices _services;
    private bool _loading;

    public SettingsWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _services.Theme.Attach(this, ThemeService.CategorySettings);
        LoadSettings();
    }

    private void LoadSettings()
    {
        _loading = true;
        try
        {
            AutoStartCheck.IsChecked = _services.Config.AutoStart;
            MinimizeToTrayCheck.IsChecked = _services.Config.MinimizeToTray;
            AutoShowBallCheck.IsChecked = _services.Config.AutoShowFloatingBall;
            ThemeEnabledCheck.IsChecked = _services.Theme.IsEnabled;

            var radius = _services.Config.FloatingBallRadius;
            RadiusSlider.Value = Math.Clamp(radius, (int)RadiusSlider.Minimum, (int)RadiusSlider.Maximum);
            RadiusValue.Text = radius > RadiusSlider.Maximum
                ? $"{radius} 像素（大于滑块上限，仅作显示）"
                : $"{radius} 像素";

            var opacity = _services.Config.FloatingBallOpacity;
            OpacitySlider.Value = Math.Clamp(opacity, (int)OpacitySlider.Minimum, (int)OpacitySlider.Maximum);
            OpacityValue.Text = OpacityText(opacity);
            UpdateLockButton();
            DefaultPasswordWarning.Visibility = _services.Passwords.IsUsingBuiltInDefaultPassword()
                ? Visibility.Visible
                : Visibility.Collapsed;
        }
        finally
        {
            _loading = false;
        }
    }

    private static string OpacityText(int opacity)
    {
        if (opacity < 100)
        {
            return $"{opacity}（高透明）";
        }
        if (opacity < 180)
        {
            return $"{opacity}（半透明）";
        }
        return $"{opacity}（低透明）";
    }

    private void AutoStart_Click(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        var enabled = AutoStartCheck.IsChecked == true;
        _services.Config.SetAutoStart(enabled);
        if (!StartupRegistration.SetEnabled(enabled))
        {
            MessageBox.Show(this, "修改开机启动注册表失败，请检查当前用户权限。", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void MinimizeToTray_Click(object sender, RoutedEventArgs e)
    {
        if (!_loading)
        {
            _services.Config.SetMinimizeToTray(MinimizeToTrayCheck.IsChecked == true);
        }
    }

    private void AutoShowBall_Click(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }
        var enabled = AutoShowBallCheck.IsChecked == true;
        _services.Config.SetAutoShowFloatingBall(enabled);
        if (enabled && Application.Current is App app && !app.IsFloatingBallVisible)
        {
            app.ShowFloatingBall();
        }
    }

    private void RadiusSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_loading || !IsLoaded || RadiusValue is null)
        {
            return;
        }
        var radius = (int)e.NewValue;
        RadiusValue.Text = $"{radius} 像素";
        _services.Config.SetFloatingBallRadius(radius);
    }

    private void OpacitySlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_loading || !IsLoaded || OpacityValue is null)
        {
            return;
        }
        var opacity = (int)e.NewValue;
        OpacityValue.Text = OpacityText(opacity);
        _services.Config.SetFloatingBallOpacity(opacity);
    }

    private void ThemeEnabled_Click(object sender, RoutedEventArgs e)
    {
        if (!_loading)
        {
            _services.Theme.IsEnabled = ThemeEnabledCheck.IsChecked == true;
        }
    }

    private void ThemeSettings_Click(object sender, RoutedEventArgs e)
    {
        new ThemeWindow(_services) { Owner = this }.ShowDialog();
        ThemeEnabledCheck.IsChecked = _services.Theme.IsEnabled;
    }

    private void ExportLog_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SaveFileDialog
        {
            Title = "导出日志",
            Filter = "文本文件 (*.txt)|*.txt",
            FileName = "BetterRNP_Modifylog.txt"
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }
        try
        {
            if (File.Exists(_services.Log.LogFile))
            {
                File.Copy(_services.Log.LogFile, dialog.FileName, true);
            }
            else
            {
                File.WriteAllText(dialog.FileName, "暂无日志记录");
            }
            MessageBox.Show(this, $"日志已成功导出到：\n{dialog.FileName}", "导出成功",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"导出失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Lock_Click(object sender, RoutedEventArgs e)
    {
        if (_services.Passwords.IsLocked)
        {
            var passwordWindow = new PasswordWindow(_services, "密码验证", "请输入配置解锁密码：")
            {
                Owner = this
            };
            passwordWindow.ShowDialog();
            if (passwordWindow.Verified)
            {
                MessageBox.Show(this, "已解锁！现在可以修改配置了。", "提示",
                    MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }
        else
        {
            var result = MessageBox.Show(this,
                "确定要锁定配置吗？\n锁定后需要密码才能修改配置。",
                "确认锁定", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (result == MessageBoxResult.Yes)
            {
                _services.Passwords.Lock();
                _services.Log.Write("配置已锁定", "LOCK_CHANGED");
                MessageBox.Show(this, "已锁定！修改配置需要密码验证。", "提示",
                    MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }
        UpdateLockButton();
    }

    private void UpdateLockButton()
    {
        LockButton.Content = _services.Passwords.IsLocked
            ? "🔒 当前已锁定（点击解锁）"
            : "🔓 当前已解锁（点击锁定）";
    }

    private void ChangePassword_Click(object sender, RoutedEventArgs e)
    {
        if (_services.Passwords.IsLocked)
        {
            MessageBox.Show(this, "请先解锁后再修改密码！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var verify = new PasswordWindow(_services, "验证原密码", "请输入原密码：")
        {
            Owner = this
        };
        verify.ShowDialog();
        if (!verify.Verified)
        {
            return;
        }
        new ChangePasswordWindow(_services) { Owner = this }.ShowDialog();
        DefaultPasswordWarning.Visibility = _services.Passwords.IsUsingBuiltInDefaultPassword()
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
