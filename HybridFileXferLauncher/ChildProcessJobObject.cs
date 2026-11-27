using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace HybridFileXferLauncher;

/// <summary>
/// Win32 Job Object configured with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}.
///
/// Guarantees at the Windows OS kernel level that the managed child {@code java.exe}
/// ({@code hfx-engine}) is 100% automatically terminated whenever this launcher closes
/// (console window close button, crash, Task Manager end-task, or normal disposal),
/// eliminating orphaned zombie JVMs and locked JAR handles.
/// </summary>
public sealed class ChildProcessJobObject : IDisposable
{
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;

    private IntPtr _jobHandle;

    public ChildProcessJobObject()
    {
        var handle = CreateJobObjectW(IntPtr.Zero, null);
        if (handle == IntPtr.Zero)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateJobObjectW failed");
        }

        try
        {
            var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION
            {
                BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION
                {
                    LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                }
            };

            var length = Marshal.SizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>();
            var infoPtr = Marshal.AllocHGlobal(length);
            try
            {
                Marshal.StructureToPtr(info, infoPtr, false);
                if (!SetInformationJobObject(handle, JobObjectExtendedLimitInformation, infoPtr, (uint)length))
                {
                    throw new Win32Exception(
                        Marshal.GetLastWin32Error(),
                        "SetInformationJobObject(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE) failed");
                }
            }
            finally
            {
                Marshal.FreeHGlobal(infoPtr);
            }

            _jobHandle = handle;
        }
        catch
        {
            CloseHandle(handle);
            throw;
        }
    }

    public bool IsActive => Volatile.Read(ref _jobHandle) != IntPtr.Zero;

    public void AssignProcess(Process process)
    {
        ArgumentNullException.ThrowIfNull(process);
        var handle = Volatile.Read(ref _jobHandle);
        if (handle == IntPtr.Zero)
        {
            throw new ObjectDisposedException(nameof(ChildProcessJobObject));
        }

        if (!AssignProcessToJobObject(handle, process.Handle))
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "AssignProcessToJobObject failed");
        }
    }

    public static void VerifySelfTest()
    {
        var expectedSize = IntPtr.Size == 8 ? 144 : 112;
        var actualSize = Marshal.SizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>();
        if (actualSize != expectedSize)
        {
            throw new InvalidOperationException(
                $"JOBOBJECT_EXTENDED_LIMIT_INFORMATION size mismatch: expected {expectedSize}, got {actualSize}");
        }

        using var job = new ChildProcessJobObject();
        if (!job.IsActive)
        {
            throw new InvalidOperationException("ChildProcessJobObject failed to initialize active kernel handle");
        }
    }

    public void Dispose()
    {
        var handle = Interlocked.Exchange(ref _jobHandle, IntPtr.Zero);
        if (handle != IntPtr.Zero)
        {
            CloseHandle(handle);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObjectW(IntPtr lpJobAttributes, string? lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(
        IntPtr hJob,
        int jobObjectInfoClass,
        IntPtr lpJobObjectInfo,
        uint cbJobObjectInfoLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr hObject);
}
