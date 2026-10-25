using BetterRNP.PluginSdk.Abstractions;

namespace BetterRNP.TimerPlugin;

internal static class TimerLocalization
{
    public static string T(this IPluginLocalizationContext? localization, string source)
        => localization?.Translate(source) ?? source;

    public static void RegisterTimerTranslations(this IPluginLocalizationContext localization)
    {
        localization.RegisterTranslations("en-US", Strings(
            "Count up", "Countdown", "Start", "Reset", "Close", "Pause", "Resume",
            "Restart", "Set countdown", "1 min", "3 min", "5 min", "10 min",
            "Hours", "Minutes", "Seconds", "Cancel", "Start countdown",
            "Increase", "Decrease", "Enter a countdown duration greater than 0",
            "Countdown finished", "Countdown finished."));
        localization.RegisterTranslations("zh-Hant", Strings(
            "正計時", "倒計時", "開始", "重設", "關閉", "暫停", "繼續",
            "重新開始", "設定倒計時", "1 分鐘", "3 分鐘", "5 分鐘", "10 分鐘",
            "時", "分", "秒", "取消", "開始倒計時",
            "增加", "減少", "請輸入大於 0 的倒計時時長",
            "倒計時結束", "倒計時結束。"));
        localization.RegisterTranslations("zh-HK", Strings(
            "正計時", "倒數", "開始", "重設", "閂", "暫停", "繼續",
            "重新開始", "設定倒數", "1 分鐘", "3 分鐘", "5 分鐘", "10 分鐘",
            "時", "分", "秒", "取消", "開始倒數",
            "加", "減", "請輸入大過 0 嘅倒數時間",
            "倒數完", "倒數完喇。"));
        localization.RegisterTranslations("ja-JP", Strings(
            "カウントアップ", "カウントダウン", "開始", "リセット", "閉じる", "一時停止", "再開",
            "最初からやり直す", "カウントダウン設定", "1 分", "3 分", "5 分", "10 分",
            "時", "分", "秒", "キャンセル", "カウントダウン開始",
            "増やす", "減らす", "0 より長いカウントダウン時間を入力してください",
            "カウントダウン終了", "カウントダウンが終了しました。"));
        localization.RegisterTranslations("ru-RU", Strings(
            "Прямой отсчёт", "Обратный отсчёт", "Старт", "Сброс", "Закрыть", "Пауза", "Продолжить",
            "Начать заново", "Настройка таймера", "1 мин", "3 мин", "5 мин", "10 мин",
            "Часы", "Минуты", "Секунды", "Отмена", "Запустить",
            "Увеличить", "Уменьшить", "Введите длительность больше 0",
            "Таймер завершён", "Обратный отсчёт завершён."));
        localization.RegisterTranslations("en-US", Identity("BetterRNP Timer plugin"));
        localization.RegisterTranslations("zh-Hant", Identity("BetterRNP 計時外掛"));
        localization.RegisterTranslations("zh-HK", Identity("BetterRNP 計時外掛"));
        localization.RegisterTranslations("ja-JP", Identity("BetterRNP タイマープラグイン"));
        localization.RegisterTranslations("ru-RU", Identity("Плагин таймера BetterRNP"));
    }

    private static Dictionary<string, string> Identity(string pluginName)
        => new(StringComparer.Ordinal)
        {
            ["BetterRNP 计时插件"] = pluginName
        };

    private static Dictionary<string, string> Strings(
        string countUp,
        string countdown,
        string start,
        string reset,
        string close,
        string pause,
        string resume,
        string restart,
        string setupCountdown,
        string minute1,
        string minute3,
        string minute5,
        string minute10,
        string hours,
        string minutes,
        string seconds,
        string cancel,
        string startCountdown,
        string increase,
        string decrease,
        string invalidDuration,
        string finishedTitle,
        string finishedBody)
        => new(StringComparer.Ordinal)
        {
            ["正计时"] = countUp,
            ["倒计时"] = countdown,
            ["开始"] = start,
            ["重置"] = reset,
            ["关闭"] = close,
            ["暂停"] = pause,
            ["继续"] = resume,
            ["重新开始"] = restart,
            ["设置倒计时"] = setupCountdown,
            ["1 分钟"] = minute1,
            ["3 分钟"] = minute3,
            ["5 分钟"] = minute5,
            ["10 分钟"] = minute10,
            ["时"] = hours,
            ["分"] = minutes,
            ["秒"] = seconds,
            ["取消"] = cancel,
            ["开始倒计时"] = startCountdown,
            ["增加"] = increase,
            ["减少"] = decrease,
            ["请输入大于 0 的倒计时时长"] = invalidDuration,
            ["倒计时结束"] = finishedTitle,
            ["倒计时结束。"] = finishedBody,
            ["暂停（{0}）"] = $"{pause}（{{0}}）",
            ["继续（{0}）"] = $"{resume}（{{0}}）",
            ["{0}开始（{1}）"] = $"{start}（{{0}} {{1}}）",
            ["重置（{0}）"] = $"{reset}（{{0}}）"
        };
}
