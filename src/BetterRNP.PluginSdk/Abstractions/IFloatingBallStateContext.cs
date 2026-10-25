namespace BetterRNP.PluginSdk.Abstractions;

/// <summary>
/// Floating ball position in physical screen pixels.
/// </summary>
public readonly record struct FloatingBallState(
    int X,
    int Y,
    int Width,
    int Height,
    string ColorHex,
    bool IsVisible);

public sealed class FloatingBallStateChangedEventArgs : EventArgs
{
    public FloatingBallStateChangedEventArgs(FloatingBallState state)
    {
        State = state;
    }

    public FloatingBallState State { get; }
}

/// <summary>
/// Optional host capability exposing the floating ball's current geometry and
/// background color. ColorHex contains the RGB value in #RRGGBB form.
/// </summary>
public interface IFloatingBallStateContext
{
    FloatingBallState? CurrentFloatingBallState { get; }

    event EventHandler<FloatingBallStateChangedEventArgs>? FloatingBallStateChanged;
}
