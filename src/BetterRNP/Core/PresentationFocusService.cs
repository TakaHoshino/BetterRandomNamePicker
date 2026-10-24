using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace BetterRNP.Core;

/// <summary>
/// Restores keyboard focus to a running presentation so presentation clickers
/// continue to send Page Up/Down to the slideshow after a picker interaction.
/// </summary>
public static class PresentationFocusService
{
    private const int SW_RESTORE = 9;
    private const uint MONITOR_DEFAULTTONEAREST = 2;
    private const int FullscreenTolerance = 2;

    private static readonly HashSet<string> PresentationProcesses = new(
        new[] { "POWERPNT", "wpp", "wps" },
        StringComparer.OrdinalIgnoreCase);

    public static bool TryRestore()
    {
        var target = FindPresentationWindow();
        if (target == IntPtr.Zero)
        {
            return false;
        }

        if (IsIconic(target))
        {
            ShowWindow(target, SW_RESTORE);
        }
        BringWindowToTop(target);
        return SetForegroundWindow(target);
    }

    private static IntPtr FindPresentationWindow()
    {
        var target = IntPtr.Zero;
        EnumWindows((window, _) =>
        {
            if (!IsSlideshowWindow(window))
            {
                return true;
            }

            target = window;
            return false;
        }, IntPtr.Zero);
        return target;
    }

    private static bool IsSlideshowWindow(IntPtr window)
    {
        if (!IsWindowVisible(window))
        {
            return false;
        }

        _ = GetWindowThreadProcessId(window, out var processId);
        if (processId == 0 || processId == Environment.ProcessId)
        {
            return false;
        }

        string processName;
        try
        {
            using var process = Process.GetProcessById((int)processId);
            processName = process.ProcessName;
        }
        catch
        {
            return false;
        }

        if (!PresentationProcesses.Contains(processName))
        {
            return false;
        }

        var className = new StringBuilder(128);
        _ = GetClassName(window, className, className.Capacity);
        if (processName.Equals("POWERPNT", StringComparison.OrdinalIgnoreCase)
            && className.ToString().Equals("screenClass", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return CoversMonitor(window);
    }

    private static bool CoversMonitor(IntPtr window)
    {
        if (!GetWindowRect(window, out var windowRect))
        {
            return false;
        }

        var monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
        if (monitor == IntPtr.Zero)
        {
            return false;
        }

        var monitorInfo = new MONITORINFO
        {
            Size = Marshal.SizeOf<MONITORINFO>()
        };
        if (!GetMonitorInfo(monitor, ref monitorInfo))
        {
            return false;
        }

        var bounds = monitorInfo.Monitor;
        return windowRect.Left <= bounds.Left + FullscreenTolerance
            && windowRect.Top <= bounds.Top + FullscreenTolerance
            && windowRect.Right >= bounds.Right - FullscreenTolerance
            && windowRect.Bottom >= bounds.Bottom - FullscreenTolerance;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO
    {
        public int Size;
        public RECT Monitor;
        public RECT WorkArea;
        public uint Flags;
    }

    private delegate bool EnumWindowsProc(IntPtr window, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr window);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr window, int command);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr window);

    [DllImport("user32.dll")]
    private static extern bool BringWindowToTop(IntPtr window);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr window, StringBuilder className, int capacity);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr window, out RECT rectangle);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr window, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
}
