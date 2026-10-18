namespace BetterRNP.Core;

/// <summary>
/// Cross-process single-instance guard. The primary process owns a named mutex and
/// listens for a named activation event; secondary processes signal that event.
/// </summary>
public sealed class SingleInstanceGuard : IDisposable
{
    private readonly Mutex _mutex;
    private readonly string _showEventName;
    private readonly EventWaitHandle? _showEvent;
    private readonly ManualResetEvent _shutdownEvent = new(false);
    private Thread? _listenerThread;
    private bool _disposed;

    public SingleInstanceGuard(string mutexName, string showEventName)
    {
        _showEventName = showEventName;
        _mutex = new Mutex(initiallyOwned: true, mutexName, out var createdNew);
        IsPrimary = createdNew;
        if (IsPrimary)
        {
            _showEvent = new EventWaitHandle(
                initialState: false,
                EventResetMode.AutoReset,
                _showEventName);
        }
    }

    public bool IsPrimary { get; }

    public void StartListening(Action activationHandler)
    {
        ArgumentNullException.ThrowIfNull(activationHandler);
        if (!IsPrimary || _showEvent is null || _listenerThread is not null)
        {
            return;
        }

        _listenerThread = new Thread(() =>
        {
            var handles = new WaitHandle[] { _showEvent, _shutdownEvent };
            while (true)
            {
                try
                {
                    var index = WaitHandle.WaitAny(handles);
                    if (index == 1)
                    {
                        return;
                    }
                    if (index == 0)
                    {
                        activationHandler();
                    }
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
            }
        })
        {
            IsBackground = true,
            Name = "BetterRNP.SingleInstanceListener"
        };
        _listenerThread.Start();
    }

    public bool SignalExisting(int attempts = 20, int delayMilliseconds = 100)
    {
        if (IsPrimary)
        {
            return false;
        }

        for (var attempt = 0; attempt < attempts; attempt++)
        {
            try
            {
                using var showEvent = EventWaitHandle.OpenExisting(
                    _showEventName);
                showEvent.Set();
                return true;
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                Thread.Sleep(delayMilliseconds);
            }
        }
        return false;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;

        _shutdownEvent.Set();
        _listenerThread?.Join(TimeSpan.FromSeconds(1));
        _showEvent?.Dispose();
        _shutdownEvent.Dispose();

        if (IsPrimary)
        {
            try
            {
                _mutex.ReleaseMutex();
            }
            catch (ApplicationException)
            {
                // The mutex may already have been released during process shutdown.
            }
        }
        _mutex.Dispose();
    }

}
