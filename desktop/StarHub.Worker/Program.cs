using System.Diagnostics;
using StarHub.Core;
using StarHub.Platform;

const int ConcurrentTasks = 2;

try
{
    if (args is ["--stop"])
    {
        if (EventWaitHandle.TryOpenExisting(WindowsHost.StopEventName, out var signal))
        {
            using (signal) signal.Set();
            for (int i = 0; i < 60 && WindowsHost.IsRunning(WindowsHost.WorkerLockName); i++)
                await Task.Delay(200);
        }
        return WindowsHost.IsRunning(WindowsHost.WorkerLockName) ? 1 : 0;
    }

    if (args.Length != 0) return 2;
    using var workerLock = WindowsHost.AcquireLock(WindowsHost.WorkerLockName);
    if (workerLock is null) return 0;

    using var stop = new EventWaitHandle(false, EventResetMode.ManualReset, WindowsHost.StopEventName);
    stop.Reset();
    using var shutdown = new CancellationTokenSource();
    var wait = ThreadPool.RegisterWaitForSingleObject(stop, (_, _) => shutdown.Cancel(), null, Timeout.Infinite, true);

    var store = new LocalStore(WindowsHost.DataPath);
    using var client = new ProviderClient();
    var account = new DesktopAccountStore();
    using var qwen = new WindowsQwen(Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..")));
    var engine = new ChatEngine(store, account, client, account.ReadProfile, qwen);
    store.MarkInterruptedTasks();
    WindowsHost.Log("worker.log", "Started.");
    var active = new List<Task>();
    var memories = SyncAccountAsync(store, account, shutdown.Token);
    var device = Task.Run(() => CollectDeviceAsync(store, shutdown.Token));
    try
    {
        while (!shutdown.IsCancellationRequested)
        {
            foreach (var completed in active.Where(task => task.IsCompleted).ToArray())
            {
                await completed;
                active.Remove(completed);
            }

            while (active.Count < ConcurrentTasks && !shutdown.IsCancellationRequested)
            {
                var run = store.ClaimDueTask(DateTimeOffset.UtcNow);
                if (run is null) break;
                active.Add(ExecuteAsync(store, engine, account, run, shutdown.Token));
            }
            await Task.Delay(TimeSpan.FromSeconds(4), shutdown.Token);
        }
    }
    catch (OperationCanceledException) when (shutdown.IsCancellationRequested) { }
    finally
    {
        shutdown.Cancel();
        wait.Unregister(null);
        await Task.WhenAll(active.Append(device).Append(memories));
        WindowsHost.Log("worker.log", "Stopped.");
    }

    return 0;
}
catch (Exception error)
{
    WindowsHost.Log("worker.log", $"Worker stopped with {error.GetType().Name} (0x{error.HResult:X8}).");
    return 1;
}

static async Task SyncAccountAsync(LocalStore store, DesktopAccountStore account, CancellationToken token)
{
    var sync = new AccountMemorySync(store);
    try
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                if (account.ReadProfile() is { MemoryToken: not null } profile)
                    await sync.SyncAsync(profile, token);
            }
            catch (Exception e) when (!token.IsCancellationRequested)
            {
                WindowsHost.Log("worker.log", $"Memory sync: {e.GetType().Name}.");
            }
            await Task.Delay(TimeSpan.FromMinutes(1), token);
        }
    }
    catch (OperationCanceledException) when (token.IsCancellationRequested) { }
}

static async Task CollectDeviceAsync(LocalStore store, CancellationToken token)
{
    WindowsDeviceMonitor? monitor = null;
    bool logged = false;
    long lastAlertCheck = 0;
    try
    {
        while (!token.IsCancellationRequested)
        {
            var delay = LocalStore.DeviceRefreshInterval;
            try
            {
                monitor ??= new WindowsDeviceMonitor();
                var reading = monitor.Capture();
                token.ThrowIfCancellationRequested();
                store.RecordDeviceReading(reading);
                long timestamp = Stopwatch.GetTimestamp();
                if (lastAlertCheck == 0 || Stopwatch.GetElapsedTime(lastAlertCheck, timestamp) >= DeviceAlerts.CheckInterval)
                {
                    lastAlertCheck = timestamp;
                    var alert = DeviceAlerts.Check(store, DateTimeOffset.UtcNow, store.ReadPreferences().UsesChineseInterface());
                    if (alert is not null && !token.IsCancellationRequested) TaskNotification.PostDeviceAlert(alert);
                }
                logged = false;
            }
            catch (Exception error) when (!token.IsCancellationRequested)
            {
                if (!logged) WindowsHost.Log("worker.log", $"Device sampling: {error.GetType().Name} (0x{error.HResult:X8}).");
                logged = true;
                delay = TimeSpan.FromSeconds(8);
            }
            await Task.Delay(delay, token);
        }
    }
    catch (OperationCanceledException) when (token.IsCancellationRequested) { }
    finally { monitor?.Dispose(); }
}

static async Task ExecuteAsync(LocalStore store, ChatEngine engine, DesktopAccountStore account, TaskRun run, CancellationToken token)
{
    try { run = await engine.ExecuteTaskAsync(run, token, account.ReadProfile()?.Model); }
    catch (Exception error)
    {
        run = run with { State = TurnState.Failed, Detail = $"The task stopped with {error.GetType().Name}." };
    }
    store.FinishTask(run);
    TaskNotification.Post(run.State);
    WindowsHost.Log("worker.log", $"Run {run.Id} · {run.State}.");
}
