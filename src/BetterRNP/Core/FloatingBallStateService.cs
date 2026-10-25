namespace BetterRNP.Core;

public readonly record struct FloatingBallRuntimeState(
    int X,
    int Y,
    int Width,
    int Height,
    string ColorHex,
    bool IsVisible);

public sealed class FloatingBallStateService
{
    private readonly object _sync = new();
    private FloatingBallRuntimeState? _current;

    public event EventHandler<FloatingBallRuntimeState>? StateChanged;

    public FloatingBallRuntimeState? Current
    {
        get
        {
            lock (_sync)
            {
                return _current;
            }
        }
    }

    public void Publish(FloatingBallRuntimeState state)
    {
        lock (_sync)
        {
            _current = state;
        }
        StateChanged?.Invoke(this, state);
    }
}
