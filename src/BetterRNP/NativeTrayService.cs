using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace BetterRNP;

/// <summary>
/// Native Windows notification-area integration. The right-click menu is created with
/// CreatePopupMenu/TrackPopupMenu, not a WPF, WinForms, or Swing popup.
/// </summary>
public sealed class NativeTrayService : IDisposable
{
    private const uint NIF_MESSAGE = 0x00000001;
    private const uint NIF_ICON = 0x00000002;
    private const uint NIF_TIP = 0x00000004;
    private const uint NIM_ADD = 0x00000000;
    private const uint NIM_DELETE = 0x00000002;
    private const int WM_APP = 0x8000;
    private const int TrayCallbackMessage = WM_APP + 101;
    private const int WM_NULL = 0x0000;
    private const int WM_RBUTTONUP = 0x0205;
    private const int WM_CONTEXTMENU = 0x007B;
    private const int WM_LBUTTONDBLCLK = 0x0203;
    private const int MF_STRING = 0x00000000;
    private const int MF_SEPARATOR = 0x00000800;
    private const uint TPM_RIGHTBUTTON = 0x0002;
    private const uint TPM_RETURNCMD = 0x0100;
    private const uint TPM_NONOTIFY = 0x0080;
    private const uint CommandShow = 1001;
    private const uint CommandHide = 1002;
    private const uint CommandBall = 1003;
    private const uint CommandExit = 1004;
    private const int IDI_APPLICATION = 32512;

    private readonly Action _showMain;
    private readonly Action _hideMain;
    private readonly Action _toggleBall;
    private readonly Action _exit;
    private HwndSource? _source;
    private NOTIFYICONDATA _iconData;
    private bool _installed;

    public NativeTrayService(Action showMain, Action hideMain, Action toggleBall, Action exit)
    {
        _showMain = showMain;
        _hideMain = hideMain;
        _toggleBall = toggleBall;
        _exit = exit;
    }

    public void Install()
    {
        if (_installed)
        {
            return;
        }

        var parameters = new HwndSourceParameters("BetterRNP.TrayMessageWindow")
        {
            ParentWindow = new IntPtr(-3),
            WindowStyle = 0
        };
        _source = new HwndSource(parameters);
        _source.AddHook(WndProc);

        _iconData = new NOTIFYICONDATA
        {
            cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
            hWnd = _source.Handle,
            uID = 1,
            uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP,
            uCallbackMessage = TrayCallbackMessage,
            hIcon = LoadIcon(IntPtr.Zero, new IntPtr(IDI_APPLICATION)),
            szTip = "BetterRNP"
        };
        _installed = Shell_NotifyIcon(NIM_ADD, ref _iconData);
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != TrayCallbackMessage)
        {
            return IntPtr.Zero;
        }

        var notification = lParam.ToInt32() & 0xFFFF;
        if (notification is WM_RBUTTONUP or WM_CONTEXTMENU)
        {
            ShowNativeContextMenu(hwnd);
            handled = true;
        }
        else if (notification == WM_LBUTTONDBLCLK)
        {
            _showMain();
            handled = true;
        }
        return IntPtr.Zero;
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

    public void Dispose()
    {
        if (_installed)
        {
            Shell_NotifyIcon(NIM_DELETE, ref _iconData);
            _installed = false;
        }
        if (_source is not null)
        {
            _source.RemoveHook(WndProc);
            _source.Dispose();
            _source = null;
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize;
        public IntPtr hWnd;
        public uint uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public uint dwState;
        public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public uint uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Shell_NotifyIcon(uint message, ref NOTIFYICONDATA data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadIcon(IntPtr instance, IntPtr iconName);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool AppendMenu(IntPtr menu, int flags, UIntPtr itemId, string? text);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyMenu(IntPtr menu);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int TrackPopupMenu(
        IntPtr menu, uint flags, int x, int y, int reserved, IntPtr owner, IntPtr rect);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetForegroundWindow(IntPtr window);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool PostMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);
}
