using System.Windows.Threading;

namespace BetterRNP.Core;

public sealed class RollingPicker
{
    private readonly Func<string> _candidateSupplier;
    private readonly Action<string> _display;
    private readonly int _intervalMilliseconds;
    private readonly int? _maxTicks;
    private readonly Action? _autoStopped;
    private DispatcherTimer? _timer;
    private int _ticks;

    public RollingPicker(
        Func<string> candidateSupplier,
        Action<string> display,
        int intervalMilliseconds = 50,
        int? maxTicks = null,
        Action? autoStopped = null)
    {
        _candidateSupplier = candidateSupplier;
        _display = display;
        _intervalMilliseconds = intervalMilliseconds;
        _maxTicks = maxTicks;
        _autoStopped = autoStopped;
    }

    public bool IsRunning => _timer?.IsEnabled == true;
    public string? LastValue { get; private set; }

    public void Start()
    {
        Stop();
        _ticks = 0;
        LastValue = null;
        _timer = new DispatcherTimer(DispatcherPriority.Render)
        {
            Interval = TimeSpan.FromMilliseconds(_intervalMilliseconds)
        };
        _timer.Tick += OnTick;
        _timer.Start();
    }

    public void Stop()
    {
        if (_timer is not null)
        {
            _timer.Stop();
            _timer.Tick -= OnTick;
            _timer = null;
        }
    }

    private void OnTick(object? sender, EventArgs e)
    {
        _ticks++;
        LastValue = _candidateSupplier();
        _display(LastValue);
        if (_maxTicks is not null && _ticks >= _maxTicks)
        {
            Stop();
            _autoStopped?.Invoke();
        }
    }
}
