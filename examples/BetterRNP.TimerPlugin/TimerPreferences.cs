using Microsoft.Win32;

namespace BetterRNP.TimerPlugin;

internal static class TimerPreferences
{
    private const string SettingsKey =
        @"Software\BetterRNP\Settings\Plugins\Timer";
    private const string TransparencyValueName = "TransparencyPercent";
    private const int DefaultTransparencyPercent = 0;

    public static double LoadTransparencyPercent()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(SettingsKey, writable: false);
            return key?.GetValue(TransparencyValueName) switch
            {
                int value => Math.Clamp(value, 0, 80),
                string text when int.TryParse(text, out var value) => Math.Clamp(value, 0, 80),
                _ => DefaultTransparencyPercent
            };
        }
        catch
        {
            return DefaultTransparencyPercent;
        }
    }

    public static void SaveTransparencyPercent(double value)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(SettingsKey, writable: true);
            key?.SetValue(
                TransparencyValueName,
                (int)Math.Round(Math.Clamp(value, 0, 80)),
                RegistryValueKind.DWord);
        }
        catch
        {
            // Appearance persistence is best effort and must not affect the timer.
        }
    }
}
