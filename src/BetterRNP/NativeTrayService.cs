using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using Forms = System.Windows.Forms;
using Microsoft.Win32;

namespace BetterRNP;

/// <summary>
/// Notification-area host using Windows Forms NotifyIcon for reliable icon display.
/// The right-click menu is still created and shown with native Win32 menu APIs.
/// </summary>
public sealed class NativeTrayService : IDisposable
{
    private const uint TPM_RIGHTBUTTON = 0x0002;
    private const uint TPM_RETURNCMD = 0x0100;
    private const uint TPM_NONOTIFY = 0x0080;
    private const uint CommandShow = 1001;
    private const uint CommandHide = 1002;
    private const uint CommandBall = 1003;
    private const uint CommandExit = 1004;
    private const int MF_STRING = 0x00000000;
    private const int MF_SEPARATOR = 0x00000800;
    private const int WM_NULL = 0x0000;

    private readonly Action _showMain;
    private readonly Action _hideMain;
    private readonly Action _toggleBall;
    private readonly Action _exit;
    private readonly Action<string>? _log;
    private readonly Forms.NotifyIcon _notifyIcon;
    private readonly Icon _icon;
    private readonly HwndSource _menuOwner;
    private int _taskbarCreatedMessage;
    private bool _disposed;

    public NativeTrayService(
        Action showMain,
        Action hideMain,
        Action toggleBall,
        Action exit,
        Action<string>? log = null)
    {
        _showMain = showMain;
        _hideMain = hideMain;
        _toggleBall = toggleBall;
        _exit = exit;
        _log = log;

        var parameters = new HwndSourceParameters("BetterRNP.TrayMenuOwner")
        {
            WindowStyle = 0
        };
        _menuOwner = new HwndSource(parameters);
        _menuOwner.AddHook(MenuOwnerWndProc);
        _taskbarCreatedMessage = unchecked((int)RegisterWindowMessage("TaskbarCreated"));

        _icon = LoadApplicationIcon();
        _notifyIcon = new Forms.NotifyIcon
        {
            Text = "BetterRNP",
            Icon = _icon,
            Visible = false
        };
        _notifyIcon.MouseUp += NotifyIcon_MouseUp;
        _notifyIcon.MouseDoubleClick += NotifyIcon_MouseDoubleClick;
    }

    public bool IsInstalled => !_disposed && _notifyIcon.Visible;

    public void Install()
    {
        if (_disposed)
        {
            return;
        }
        _notifyIcon.Visible = true;
        _notifyIcon.BalloonTipTitle = "BetterRNP";
        _notifyIcon.BalloonTipText =
            "托盘图标已启用。如果任务栏右下角没有显示，请点击 ^ 查看隐藏图标。";
        _notifyIcon.BalloonTipIcon = Forms.ToolTipIcon.Info;
        _notifyIcon.ShowBalloonTip(3000);
        _log?.Invoke(_notifyIcon.Visible
            ? "NotifyIcon 托盘图标已启用"
            : "NotifyIcon 托盘图标启用失败");
        _ = PromoteTrayIconAsync();
    }

    private IntPtr MenuOwnerWndProc(
        IntPtr window,
        int message,
        IntPtr wParam,
        IntPtr lParam,
        ref bool handled)
    {
        if (_taskbarCreatedMessage != 0 && message == _taskbarCreatedMessage)
        {
            ReAddNotifyIcon();
            handled = true;
        }
        return IntPtr.Zero;
    }

    private void ReAddNotifyIcon()
    {
        try
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Icon = _icon;
            _notifyIcon.Visible = true;
            _log?.Invoke("Explorer 重启后已重新添加托盘图标");
            _ = PromoteTrayIconAsync();
        }
        catch (Exception ex)
        {
            _log?.Invoke($"重新添加托盘图标失败: {ex.Message}");
        }
    }

    private async Task PromoteTrayIconAsync()
    {
        for (var attempt = 0; attempt < 10 && !_disposed; attempt++)
        {
            await Task.Delay(300);
            if (TryPromoteTrayIcon())
            {
                _log?.Invoke("托盘图标已设置为常规显示");
                return;
            }
        }
    }

    private bool TryPromoteTrayIcon()
    {
        try
        {
            var executable = Environment.ProcessPath;
            if (string.IsNullOrWhiteSpace(executable))
            {
                return false;
            }

            using var settings = Registry.CurrentUser.OpenSubKey(
                @"Control Panel\NotifyIconSettings", writable: true);
            if (settings is null)
            {
                return false;
            }

            foreach (var name in settings.GetSubKeyNames())
            {
                using var item = settings.OpenSubKey(name, writable: true);
                if (item?.GetValue("ExecutablePath") is not string path
                    || !string.Equals(
                        Path.GetFullPath(path),
                        Path.GetFullPath(executable),
                        StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                item.SetValue("IsPromoted", 1, RegistryValueKind.DWord);
                return true;
            }
        }
        catch
        {
            // Notification area settings are best-effort.
        }
        return false;
    }

    private void NotifyIcon_MouseUp(object? sender, Forms.MouseEventArgs e)
    {
        if (e.Button == Forms.MouseButtons.Right)
        {
            ShowNativeContextMenu(_menuOwner.Handle);
        }
    }

    private void NotifyIcon_MouseDoubleClick(object? sender, Forms.MouseEventArgs e)
    {
        if (e.Button == Forms.MouseButtons.Left)
        {
            _showMain();
        }
    }

    private void ShowNativeContextMenu(IntPtr owner)
    {
        var menu = CreatePopupMenu();
        if (menu == IntPtr.Zero)
        {
            return;
        }

        try
        {
            AppendMenu(menu, MF_STRING, new UIntPtr(CommandShow), "显示主窗口");
            AppendMenu(menu, MF_STRING, new UIntPtr(CommandHide), "隐藏主窗口");
            AppendMenu(menu, MF_SEPARATOR, UIntPtr.Zero, null);
            AppendMenu(menu, MF_STRING, new UIntPtr(CommandBall), "显示/隐藏悬浮球");
            AppendMenu(menu, MF_SEPARATOR, UIntPtr.Zero, null);
            AppendMenu(menu, MF_STRING, new UIntPtr(CommandExit), "退出程序");

            SetForegroundWindow(owner);
            if (!GetCursorPos(out var point))
            {
                return;
            }

            var command = TrackPopupMenu(
                menu,
                TPM_RIGHTBUTTON | TPM_RETURNCMD | TPM_NONOTIFY,
                point.X,
                point.Y,
                0,
                owner,
                IntPtr.Zero);
            PostMessage(owner, WM_NULL, IntPtr.Zero, IntPtr.Zero);

            switch ((uint)command)
            {
                case CommandShow:
                    _showMain();
                    break;
                case CommandHide:
                    _hideMain();
                    break;
                case CommandBall:
                    _toggleBall();
                    break;
                case CommandExit:
                    _exit();
                    break;
            }
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    private static Icon LoadApplicationIcon()
    {
        try
        {
            var executable = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(executable) && File.Exists(executable))
            {
                return Icon.ExtractAssociatedIcon(executable) ?? SystemIcons.Application;
            }
        }
        catch
        {
            // Fall back to the standard Windows application icon.
        }
        return SystemIcons.Application;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;

        _notifyIcon.Visible = false;
        _notifyIcon.MouseUp -= NotifyIcon_MouseUp;
        _notifyIcon.MouseDoubleClick -= NotifyIcon_MouseDoubleClick;
        _notifyIcon.Dispose();
        _icon.Dispose();
        _menuOwner.RemoveHook(MenuOwnerWndProc);
        _menuOwner.Dispose();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool AppendMenu(IntPtr menu, int flags, UIntPtr itemId, string? text);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyMenu(IntPtr menu);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int TrackPopupMenu(
        IntPtr menu,
        uint flags,
        int x,
        int y,
        int reserved,
        IntPtr owner,
        IntPtr rect);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetForegroundWindow(IntPtr window);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool PostMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint RegisterWindowMessage(string message);
}
