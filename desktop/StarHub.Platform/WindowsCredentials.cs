using System.ComponentModel;
using System.Runtime.InteropServices;
using StarHub.Core;

namespace StarHub.Platform;

public sealed class WindowsCredentials : ICredentials
{
    private const uint Generic = 1;
    private const uint PersistOnThisComputer = 2;
    private const int NotFound = 1168;

    public string? Read(string target)
    {
        if (!CredRead(target, Generic, 0, out var handle))
        {
            int error = Marshal.GetLastWin32Error();
            if (error == NotFound) return null;
            throw new Win32Exception(error);
        }
        try
        {
            var credential = Marshal.PtrToStructure<Credential>(handle);
            return Marshal.PtrToStringUni(credential.Blob, checked((int)credential.BlobSize / 2));
        }
        finally { CredFree(handle); }
    }

    public void Save(string target, string value)
    {
        value = value.Trim();
        if (value.Length is 0 or > 1200 || value.Any(char.IsWhiteSpace) || value.Any(char.IsControl))
            throw new StarHubException("Enter a valid API key without whitespace.");
        var buffer = Marshal.StringToCoTaskMemUni(value);
        try
        {
            var credential = new Credential
            {
                Type = Generic, Target = target, Blob = buffer, BlobSize = (uint)value.Length * 2,
                Persist = PersistOnThisComputer, UserName = "Star Hub"
            };
            if (!CredWrite(ref credential, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        finally { Marshal.ZeroFreeCoTaskMemUnicode(buffer); }
    }

    public void Remove(string target)
    {
        if (!CredDelete(target, Generic, 0))
        {
            int error = Marshal.GetLastWin32Error();
            if (error != NotFound) throw new Win32Exception(error);
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Credential
    {
        public uint Flags, Type;
        public string Target;
        public string? Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint BlobSize;
        public nint Blob;
        public uint Persist, AttributeCount;
        public nint Attributes;
        public string? TargetAlias, UserName;
    }

    [DllImport("advapi32", EntryPoint = "CredReadW", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredRead(string target, uint type, uint flags, out nint credential);

    [DllImport("advapi32", EntryPoint = "CredWriteW", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredWrite(ref Credential credential, uint flags);

    [DllImport("advapi32", EntryPoint = "CredDeleteW", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredDelete(string target, uint type, uint flags);

    [DllImport("advapi32")]
    private static extern void CredFree(nint buffer);
}
