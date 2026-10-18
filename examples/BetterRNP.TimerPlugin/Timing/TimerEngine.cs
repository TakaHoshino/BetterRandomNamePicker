using System.Diagnostics;

namespace BetterRNP.TimerPlugin.Timing;

/// <summary>正计时或倒计时。</summary>
public enum TimerMode
{
    CountUp,
    CountDown
}

/// <summary>计时窗口的状态机状态。</summary>
public enum TimerState
{
    /// <summary>尚未开始，或重置后回到初始进度。</summary>
    Idle,

    /// <summary>正在计时。</summary>
    Running,

    /// <summary>已暂停，可继续或重置。</summary>
    Paused,

    /// <summary>倒计时已归零（正计时不会进入该状态）。</summary>
    Finished
}

/// <summary>
/// 与 UI 无关的计时状态机。窗口用 <see cref="Tick"/> 刷新显示，
/// 测试可以注入假时钟得到确定结果。
/// </summary>
public sealed class TimerEngine
{
    private readonly Func<TimeSpan> _clock;
    private TimeSpan _accumulated;
    private TimeSpan _segmentStart;

    /// <param name="mode">正计时或倒计时。</param>
    /// <param name="duration">倒计时总时长；正计时忽略该参数。</param>
    /// <param name="clock">可选的单调时钟，默认使用 <see cref="Stopwatch"/>。</param>
    public TimerEngine(TimerMode mode, TimeSpan duration = default, Func<TimeSpan>? clock = null)
    {
        if (duration < TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(duration), "倒计时时长不能为负数");
        }

        Mode = mode;
        Duration = mode == TimerMode.CountDown ? duration : TimeSpan.Zero;
        _clock = clock ?? CreateStopwatchClock();
        Refresh();
    }

    public TimerMode Mode { get; }

    /// <summary>倒计时的初始时长；正计时为 <see cref="TimeSpan.Zero"/>。</summary>
    public TimeSpan Duration { get; }

    /// <summary>进入“最后 10 秒”的阈值。</summary>
    public TimeSpan WarningThreshold { get; init; } = TimeSpan.FromSeconds(10);

    public TimerState State { get; private set; } = TimerState.Idle;

    /// <summary>当前应显示的进度：正计时为已用时间，倒计时为剩余时间。</summary>
    public TimeSpan Displayed { get; private set; }

    /// <summary>倒计时进入最后 10 秒（或已结束）时为 <c>true</c>，窗口据此把字体改成红色。</summary>
    public bool IsWarning { get; private set; }

    public string DisplayText => FormatTime(Displayed, roundUp: Mode == TimerMode.CountDown);

    /// <summary>已用时间（倒计时结束后等于总时长）。</summary>
    public TimeSpan Elapsed => CurrentElapsed();

    public void Start()
    {
        if (State == TimerState.Running)
        {
            return;
        }
        if (State == TimerState.Finished)
        {
            _accumulated = TimeSpan.Zero;
        }
        _segmentStart = _clock();
        State = TimerState.Running;
        Refresh();
    }

    public void Pause()
    {
        if (State != TimerState.Running)
        {
            return;
        }
        _accumulated = CurrentElapsed();
        State = TimerState.Paused;
        Refresh();
    }

    /// <summary>回到初始进度：正计时归零，倒计时恢复为设定时长。</summary>
    public void Reset()
    {
        _accumulated = TimeSpan.Zero;
        _segmentStart = TimeSpan.Zero;
        State = TimerState.Idle;
        Refresh();
    }

    /// <summary>刷新显示。返回 <c>true</c> 表示倒计时在这一刻刚好归零。</summary>
    public bool Tick()
    {
        var wasRunning = State == TimerState.Running;
        Refresh();
        return wasRunning && State == TimerState.Finished;
    }

    /// <summary>把时间格式化为 <c>MM:SS</c>，超过一小时使用 <c>H:MM:SS</c>。</summary>
    /// <param name="roundUp">倒计时使用向上取整，避免刚开始就显示少一秒。</param>
    public static string FormatTime(TimeSpan value, bool roundUp = false)
    {
        var totalSeconds = (long)(roundUp ? Math.Ceiling(value.TotalSeconds) : Math.Floor(value.TotalSeconds));
        if (totalSeconds < 0)
        {
            totalSeconds = 0;
        }
        var hours = totalSeconds / 3600;
        var minutes = totalSeconds / 60 % 60;
        var seconds = totalSeconds % 60;
        return hours > 0 ? $"{hours}:{minutes:00}:{seconds:00}" : $"{minutes:00}:{seconds:00}";
    }

    private TimeSpan CurrentElapsed()
        => _accumulated + (State == TimerState.Running ? _clock() - _segmentStart : TimeSpan.Zero);

    private void Refresh()
    {
        var elapsed = CurrentElapsed();
        if (elapsed < TimeSpan.Zero)
        {
            elapsed = TimeSpan.Zero;
        }

        if (Mode == TimerMode.CountDown)
        {
            var remaining = Duration - elapsed;
            if (remaining <= TimeSpan.Zero)
            {
                remaining = TimeSpan.Zero;
                if (State == TimerState.Running)
                {
                    State = TimerState.Finished;
                    _accumulated = Duration;
                }
            }
            Displayed = remaining;
            // 尚未开始时不标红，避免刚选完很短的时长就直接变红。
            IsWarning = State != TimerState.Idle && remaining <= WarningThreshold;
        }
        else
        {
            Displayed = elapsed;
            IsWarning = false;
        }
    }

    private static Func<TimeSpan> CreateStopwatchClock()
    {
        var stopwatch = Stopwatch.StartNew();
        return () => stopwatch.Elapsed;
    }
}
