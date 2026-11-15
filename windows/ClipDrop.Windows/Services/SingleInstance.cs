using System.Threading;

namespace ClipDrop.Windows.Services;

/// <summary>
/// One running copy per user session. A named mutex marks the first instance; a second launch sets a
/// named event, which tells the first one to show its window, and then exits. (Forwarding command-line
/// arguments, for "Send to", arrives with file transfer.)
/// </summary>
internal sealed class SingleInstance : IDisposable
{
    private readonly Mutex _mutex;
    private readonly EventWaitHandle _showSignal;
    private readonly RegisteredWaitHandle _registration;

    private SingleInstance(Mutex mutex, EventWaitHandle showSignal, Action onShowRequested)
    {
        _mutex = mutex;
        _showSignal = showSignal;
        _registration = ThreadPool.RegisterWaitForSingleObject(showSignal, (_, _) => onShowRequested(), null, Timeout.Infinite, executeOnlyOnce: false);
    }

    /// <summary>
    /// Becomes the single instance, or returns null after asking the existing one to show itself.
    /// <paramref name="onShowRequested"/> runs on a thread-pool thread.
    /// </summary>
    public static SingleInstance? TryAcquire(string name, Action onShowRequested)
    {
        // "Local\" scopes both objects to this user's session.
        var mutex = new Mutex(initiallyOwned: true, $@"Local\{name}.Instance", out bool createdNew);
        if (!createdNew)
        {
            mutex.Dispose();
            if (EventWaitHandle.TryOpenExisting($@"Local\{name}.Show", out EventWaitHandle? show))
            {
                using (show)
                {
                    show.Set();
                }
            }

            return null;
        }

        var showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, $@"Local\{name}.Show");
        return new SingleInstance(mutex, showSignal, onShowRequested);
    }

    public void Dispose()
    {
        _registration.Unregister(null);
        _showSignal.Dispose();
        _mutex.ReleaseMutex();
        _mutex.Dispose();
    }
}
