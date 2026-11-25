using System.Security.Cryptography;
using System.Text.Json;
using StarHub.Core;

namespace StarHub.Platform;

public sealed class DesktopAccountStore : ICredentials
{
    private readonly WindowsCredentials manual = new();
    private readonly string path = Path.Combine(WindowsHost.DataPath, "desktop-account.bin");

    public DesktopAccountProfile? ReadProfile()
    {
        try
        {
            using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
            if (stream.Length is <= 0 or > 16 * 1024) return null;
            var bytes = new byte[(int)stream.Length];
            stream.ReadExactly(bytes);
            var decrypted = ProtectedData.Unprotect(bytes, null, DataProtectionScope.CurrentUser);
            try
            {
                var profile = JsonSerializer.Deserialize<DesktopAccountProfile>(decrypted);
                return profile?.IsValid() == true ? profile : null;
            }
            finally { CryptographicOperations.ZeroMemory(decrypted); }
        }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
        catch (Exception error) when (error is CryptographicException or JsonException)
        {
            WindowsHost.Log("desktop.log", $"Account connection read: {error.GetType().Name}.");
            return null;
        }
    }

    public string? Read(string target)
    {
        if (target == ChatEngine.OpenAiCredential && ReadProfile() is { } linked) return linked.ApiKey;
        return manual.Read(target);
    }

    public void Save(DesktopAccountProfile profile)
    {
        if (!profile.IsValid()) throw new StarHubException("Star Hub returned invalid account settings.");
        Directory.CreateDirectory(WindowsHost.DataPath);
        string temporary = path + "." + Guid.NewGuid().ToString("N");
        byte[] data = JsonSerializer.SerializeToUtf8Bytes(profile);
        try
        {
            byte[] encrypted = ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser);
            using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                file.Write(encrypted);
                file.Flush(flushToDisk: true);
            }
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(data);
            File.Delete(temporary);
        }
    }

    public void Disconnect() => File.Delete(path);
}
