param(
    [Parameter(Mandatory = $true)][int]$ProcessId
)

$ErrorActionPreference = 'Stop'
Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class ProcessIntegrity
{
    private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private const uint TOKEN_QUERY = 0x0008;
    private const int TokenIntegrityLevel = 25;

    [StructLayout(LayoutKind.Sequential)]
    private struct TOKEN_MANDATORY_LABEL
    {
        public IntPtr Label;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, int pid);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(
        IntPtr token, int infoClass, IntPtr info, int infoLength, out int returnLength);

    [DllImport("advapi32.dll")]
    private static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);

    [DllImport("advapi32.dll")]
    private static extern IntPtr GetSidSubAuthority(IntPtr sid, int index);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    public static string GetIntegrity(int pid)
    {
        IntPtr process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (process == IntPtr.Zero) return "OpenProcess failed: " + Marshal.GetLastWin32Error();
        IntPtr token = IntPtr.Zero;
        try
        {
            if (!OpenProcessToken(process, TOKEN_QUERY, out token))
                return "OpenProcessToken failed: " + Marshal.GetLastWin32Error();
            int size = 0;
            GetTokenInformation(token, TokenIntegrityLevel, IntPtr.Zero, 0, out size);
            IntPtr buffer = Marshal.AllocHGlobal(size);
            try
            {
                if (!GetTokenInformation(token, TokenIntegrityLevel, buffer, size, out size))
                    return "GetTokenInformation failed: " + Marshal.GetLastWin32Error();
                var label = Marshal.PtrToStructure<TOKEN_MANDATORY_LABEL>(buffer);
                byte count = Marshal.ReadByte(GetSidSubAuthorityCount(label.Label));
                IntPtr subAuthority = GetSidSubAuthority(label.Label, count - 1);
                int rid = Marshal.ReadInt32(subAuthority);
                return "RID=" + rid;
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }
        finally
        {
            if (token != IntPtr.Zero) CloseHandle(token);
            CloseHandle(process);
        }
    }
}
"@

Write-Output ([ProcessIntegrity]::GetIntegrity($ProcessId))
