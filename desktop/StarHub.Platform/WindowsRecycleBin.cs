using System.Runtime.InteropServices;
using StarHub.Core;

namespace StarHub.Platform;

public static class WindowsRecycleBin
{
    private static readonly Guid FileOperationClass = new("3AD05575-8857-4850-9277-11B85BDB8E09");
    private static readonly Guid ShellItemId = new("43826D1E-E718-42EE-BC55-A1E261C37BFE");
    private const uint Silent = 0x0004, NoConfirmation = 0x0010, AllowUndo = 0x0040;
    private const uint NoErrorUi = 0x0400, WarnBeforePermanentDelete = 0x4000, RecycleOnDelete = 0x00080000;
    private const uint DeleteRecycleIfPossible = 0x80;

    public static Task<PcTaskResult> RecyclePdfAsync(string file, CancellationToken token)
    {
        var completed = new TaskCompletionSource<PcTaskResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try
            {
                token.ThrowIfCancellationRequested();
                completed.TrySetResult(RecyclePdf(file, token));
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { completed.TrySetCanceled(token); }
            catch (Exception error) { completed.TrySetException(error); }
        }) { IsBackground = true };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completed.Task;
    }

    private static PcTaskResult RecyclePdf(string file, CancellationToken token)
    {
        string path = WindowsHost.TaskWorkspace().LocalPath(file);
        if (!Path.IsPathFullyQualified(file) || !Path.GetExtension(path).Equals(".pdf", StringComparison.OrdinalIgnoreCase))
            return new(false, "The saved file path is not a local PDF.");
        FileAttributes attributes;
        try { attributes = File.GetAttributes(path); }
        catch (IOException error) when (error is FileNotFoundException or DirectoryNotFoundException)
        { return new(false, "The PDF is already gone from its saved location."); }
        catch (UnauthorizedAccessException) { return new(false, "Windows denied access to this PDF."); }
        if ((attributes & (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0)
            return new(false, "The saved path no longer refers to a regular PDF.");

        var sink = new RecycleProgress(token);
        nint item = 0, progress = 0;
        IFileOperation? operation = null;
        try
        {
            Marshal.ThrowExceptionForHR(SHCreateItemFromParsingName(path, 0, ShellItemId, out item));
            operation = (IFileOperation)Activator.CreateInstance(Type.GetTypeFromCLSID(FileOperationClass, throwOnError: true)!)!;
            operation.SetOperationFlags(Silent | NoConfirmation | AllowUndo | NoErrorUi | WarnBeforePermanentDelete | RecycleOnDelete);
            progress = Marshal.GetComInterfaceForObject(sink, typeof(IFileOperationProgressSink));
            operation.DeleteItem(item, progress);
            token.ThrowIfCancellationRequested();
            operation.PerformOperations();
            operation.GetAnyOperationsAborted(out bool aborted);
            return !aborted && sink.Recycled && !File.Exists(path)
                ? new(true, "Moved the PDF to the Recycle Bin.")
                : new(false, "Windows did not confirm that the PDF reached the Recycle Bin.", sink.Result);
        }
        catch (COMException error)
        {
            return new(false, error.HResult == unchecked((int)0x80070005) ? "Windows denied access to this PDF."
                : "Windows did not confirm that the PDF reached the Recycle Bin.", sink.Result ?? error.HResult);
        }
        finally
        {
            if (operation is not null) Marshal.ReleaseComObject(operation);
            if (progress != 0) Marshal.Release(progress);
            if (item != 0) Marshal.Release(item);
        }
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int SHCreateItemFromParsingName(string path, nint context, in Guid id, out nint item);

    [ComImport, Guid("947AAB5F-0A5C-4C13-B4D6-4BF7836FC9F8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileOperation
    {
        void Advise(nint sink, out uint cookie);
        void Unadvise(uint cookie);
        void SetOperationFlags(uint flags);
        void SetProgressMessage(nint message);
        void SetProgressDialog(nint dialog);
        void SetProperties(nint properties);
        void SetOwnerWindow(nint owner);
        void ApplyPropertiesToItem(nint item);
        void ApplyPropertiesToItems(nint items);
        void RenameItem(nint item, nint name, nint sink);
        void RenameItems(nint items, nint name);
        void MoveItem(nint item, nint folder, nint name, nint sink);
        void MoveItems(nint items, nint folder);
        void CopyItem(nint item, nint folder, nint name, nint sink);
        void CopyItems(nint items, nint folder);
        void DeleteItem(nint item, nint sink);
        void DeleteItems(nint items);
        void NewItem(nint folder, uint attributes, nint name, nint template, nint sink);
        void PerformOperations();
        void GetAnyOperationsAborted([MarshalAs(UnmanagedType.Bool)] out bool aborted);
    }

    [Guid("04B0F1A7-9490-44BC-96E1-4296A31252E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), ComVisible(true)]
    public interface IFileOperationProgressSink
    {
        [PreserveSig] int StartOperations();
        [PreserveSig] int FinishOperations(int result);
        [PreserveSig] int PreRenameItem(uint flags, nint item, nint name);
        [PreserveSig] int PostRenameItem(uint flags, nint item, nint name, int result, nint created);
        [PreserveSig] int PreMoveItem(uint flags, nint item, nint destination, nint name);
        [PreserveSig] int PostMoveItem(uint flags, nint item, nint destination, nint name, int result, nint created);
        [PreserveSig] int PreCopyItem(uint flags, nint item, nint destination, nint name);
        [PreserveSig] int PostCopyItem(uint flags, nint item, nint destination, nint name, int result, nint created);
        [PreserveSig] int PreDeleteItem(uint flags, nint item);
        [PreserveSig] int PostDeleteItem(uint flags, nint item, int result, nint recycled);
        [PreserveSig] int PreNewItem(uint flags, nint destination, nint name);
        [PreserveSig] int PostNewItem(uint flags, nint destination, nint name, nint template, uint attributes, int result, nint created);
        [PreserveSig] int UpdateProgress(uint total, uint completed);
        [PreserveSig] int ResetTimer();
        [PreserveSig] int PauseTimer();
        [PreserveSig] int ResumeTimer();
    }

    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    private sealed class RecycleProgress(CancellationToken token) : IFileOperationProgressSink
    {
        public bool Recycled { get; private set; }
        public int? Result { get; private set; }

        public int PreDeleteItem(uint flags, nint item) => token.IsCancellationRequested || (flags & DeleteRecycleIfPossible) == 0
            ? unchecked((int)0x80004004) : 0;
        public int PostDeleteItem(uint flags, nint item, int result, nint recycled)
        {
            Result = result;
            Recycled = result >= 0 && recycled != 0;
            return 0;
        }
        public int StartOperations() => 0;
        public int FinishOperations(int result) => 0;
        public int PreRenameItem(uint flags, nint item, nint name) => 0;
        public int PostRenameItem(uint flags, nint item, nint name, int result, nint created) => 0;
        public int PreMoveItem(uint flags, nint item, nint destination, nint name) => 0;
        public int PostMoveItem(uint flags, nint item, nint destination, nint name, int result, nint created) => 0;
        public int PreCopyItem(uint flags, nint item, nint destination, nint name) => 0;
        public int PostCopyItem(uint flags, nint item, nint destination, nint name, int result, nint created) => 0;
        public int PreNewItem(uint flags, nint destination, nint name) => 0;
        public int PostNewItem(uint flags, nint destination, nint name, nint template, uint attributes, int result, nint created) => 0;
        public int UpdateProgress(uint total, uint completed) => 0;
        public int ResetTimer() => 0;
        public int PauseTimer() => 0;
        public int ResumeTimer() => 0;
    }
}
