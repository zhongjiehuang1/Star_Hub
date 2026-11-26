using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;
using StarHub.Core;

namespace StarHub.Platform;

public sealed class WindowsQwen(string appDirectory) : ILocalQwen, IDisposable
{
    public QwenDownload Download { get; } = new(WindowsHost.DataPath);
    public bool IsInstalled => Download.IsInstalled;
    private readonly SemaphoreSlim startup = new(1, 1);
    private readonly object lifecycle = new();
    private static readonly HttpClient Health = new(new HttpClientHandler { UseProxy = false, UseCookies = false, AllowAutoRedirect = false })
        { Timeout = TimeSpan.FromSeconds(2) };
    private Process? process;
    private SafeFileHandle? job;
    private ModelRoute? route;
    private bool disposed;

    public async Task<ModelRoute> StartAsync(CancellationToken token)
    {
        await startup.WaitAsync(token).ConfigureAwait(false);
        try
        {
            ModelRoute candidate;
            Process child;
            lock (lifecycle)
            {
                ObjectDisposedException.ThrowIf(disposed, this);
                if (route is not null && process is { HasExited: false }) return route;
                Stop();
                if (!IsInstalled) throw new StarHubException("Set up offline chat first.");
                using var port = new TcpListener(IPAddress.Loopback, 0);
                port.Start();
                int number = ((IPEndPoint)port.LocalEndpoint).Port;
                port.Stop();
                string key = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
                string runtime = Path.Combine(appDirectory, "offline-runtime");
                var info = new ProcessStartInfo(Path.Combine(runtime, "llama-server.exe"))
                    { WorkingDirectory = runtime, UseShellExecute = false, CreateNoWindow = true };
                info.Environment.Clear();
                foreach (string name in new[] { "SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA" })
                    if (Environment.GetEnvironmentVariable(name) is { } value) info.Environment[name] = value;
                info.Environment["PATH"] = string.Join(';', runtime, appDirectory, Environment.SystemDirectory);
                info.Environment["LLAMA_API_KEY"] = key;
                foreach (string arg in new[] { "--model", Download.ModelPath, "--alias", QwenDownload.Model, "--host", "127.0.0.1",
                    "--port", number.ToString(), "--offline", "--no-webui", "--no-slots", "--no-warmup", "--parallel", "1",
                    "--ctx-size", "8192", "--threads", Math.Clamp(Environment.ProcessorCount / 2, 1, 6).ToString(),
                    "--sleep-idle-seconds", "120" }) info.ArgumentList.Add(arg);
                job = ChildJob.Create();
                child = process = Process.Start(info) ?? throw new StarHubException("Offline Qwen could not start.");
                if (!ChildJob.AssignProcessToJobObject(job, child.SafeHandle)) throw new Win32Exception();
                candidate = new(Provider.LocalQwen, new Uri($"http://127.0.0.1:{number}/v1/"), QwenDownload.Model, key);
            }

            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
            deadline.CancelAfter(TimeSpan.FromMinutes(2));
            while (!child.HasExited)
            {
                using var ping = new HttpRequestMessage(HttpMethod.Get, new Uri(candidate.Endpoint, "models"));
                ping.Headers.Authorization = new("Bearer", candidate.Credential);
                try
                {
                    using var response = await Health.SendAsync(ping, deadline.Token).ConfigureAwait(false);
                    if (response.IsSuccessStatusCode && !child.HasExited)
                    {
                        lock (lifecycle) { ObjectDisposedException.ThrowIf(disposed, this); return route = candidate; }
                    }
                }
                catch (HttpRequestException) { }
                catch (OperationCanceledException) when (!deadline.IsCancellationRequested) { }
                await Task.Delay(200, deadline.Token).ConfigureAwait(false);
            }
            throw new StarHubException("Offline Qwen could not start on this PC.");
        }
        catch (Exception e) when (e is Win32Exception or IOException or StarHubException or OperationCanceledException)
        {
            lock (lifecycle) Stop();
            token.ThrowIfCancellationRequested();
            throw new StarHubException("Offline Qwen could not start on this PC.");
        }
        finally { startup.Release(); }
    }

    private void Stop()
    {
        job?.Dispose();
        job = null;
        if (process is { } child)
        {
            try { if (!child.HasExited) child.Kill(entireProcessTree: true); }
            catch (Exception e) when (e is InvalidOperationException or Win32Exception) { }
            child.Dispose();
        }
        process = null;
        route = null;
    }

    public void Dispose() { lock (lifecycle) { disposed = true; Stop(); } }

    // Windows closes this job if the app or worker exits, including an unexpected exit.
    private static class ChildJob
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct BasicLimits
        {
            public long ProcessTime, JobTime;
            public uint LimitFlags;
            public nuint WorkingSetMin, WorkingSetMax;
            public uint ActiveProcesses;
            public nuint Affinity;
            public uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)]
        private struct ExtendedLimits
        {
            public BasicLimits Basic;
            public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
            public nuint ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }

        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        private static extern SafeFileHandle CreateJobObject(nint security, string? name);
        [DllImport("kernel32.dll", SetLastError=true)]
        private static extern bool SetInformationJobObject(SafeFileHandle job, int infoClass, ref ExtendedLimits limits, int size);
        [DllImport("kernel32.dll", SetLastError=true)]
        internal static extern bool AssignProcessToJobObject(SafeFileHandle job, SafeProcessHandle process);

        internal static SafeFileHandle Create()
        {
            var handle = CreateJobObject(0, null);
            var limits = new ExtendedLimits { Basic = new BasicLimits { LimitFlags = 0x2000 } };
            if (!handle.IsInvalid && SetInformationJobObject(handle, 9, ref limits, Marshal.SizeOf<ExtendedLimits>())) return handle;
            int error = Marshal.GetLastWin32Error();
            handle.Dispose();
            throw new Win32Exception(error);
        }
    }
}
