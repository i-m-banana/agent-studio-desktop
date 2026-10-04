$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
try {
    $taskInput = [Console]::In.ReadToEnd() | ConvertFrom-Json
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class GuardedProjectCreate {
    [StructLayout(LayoutKind.Sequential)] struct Info { public uint Attributes; public System.Runtime.InteropServices.ComTypes.FILETIME Created,Accessed,Written; public uint Volume,SizeHigh,SizeLow,Links,IndexHigh,IndexLow; }
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle file,out Info info);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern SafeFileHandle CreateFile(string name,uint access,uint share,IntPtr security,uint disposition,uint flags,IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool WriteFile(SafeFileHandle file,byte[] bytes,uint count,out uint written,IntPtr overlapped);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool FlushFileBuffers(SafeFileHandle file);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern uint GetFileAttributes(string path);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetFileInformationByHandle(SafeFileHandle file,int kind,ref int info,uint size);
    [DllImport("kernel32.dll", EntryPoint="SetFileInformationByHandle", SetLastError=true)] static extern bool SetFileInformationByHandleRaw(SafeFileHandle file,int kind,IntPtr info,uint size);
    [StructLayout(LayoutKind.Sequential)] struct IoStatus { public IntPtr Status,Information; }
    [DllImport("ntdll.dll")] static extern uint NtSetInformationFile(SafeFileHandle file,out IoStatus status,IntPtr info,uint size,uint kind);
    public static string Identity(string path) {
        using(var file=CreateFile(Path.GetFullPath(path),0x80000000,1,IntPtr.Zero,3,0x02200000,IntPtr.Zero)) {
            Info info;
            if(file.IsInvalid || !GetFileInformationByHandle(file,out info) || (info.Attributes&0x400)!=0)throw new IOException("Directory identity unavailable");
            return info.Volume.ToString("x8")+":"+info.IndexHigh.ToString("x8")+info.IndexLow.ToString("x8");
        }
    }
    public static void Patch(string parent,string name,byte[] content,string expected) {
        parent=Path.GetFullPath(parent);
        if(name!=Path.GetFileName(name) || name.IndexOf(':')>=0)throw new IOException("Invalid file name");
        var handles=new List<SafeFileHandle>();
        try {
            string cursor=Path.GetPathRoot(parent);
            var parts=parent.Substring(cursor.Length).Split(new[]{Path.DirectorySeparatorChar},StringSplitOptions.RemoveEmptyEntries);
            for(int i=-1;i<parts.Length;i++) {
                if(i>=0)cursor=Path.Combine(cursor,parts[i]);
                var directory=CreateFile(cursor,i==parts.Length-1?0xC0000000:0x80000000,3,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
                if(directory.IsInvalid){directory.Dispose();throw new Win32Exception(Marshal.GetLastWin32Error());}
                handles.Add(directory);
                uint attributes=GetFileAttributes(cursor);
                if(attributes==0xffffffff || (attributes&0x400)!=0 || (attributes&0x10)==0)throw new IOException("Directory reparse points are forbidden");
            }
            using(var original=CreateFile(Path.Combine(parent,name),0x80000000,1,IntPtr.Zero,3,0x00200000,IntPtr.Zero)) {
                Info info;
                if(original.IsInvalid || !GetFileInformationByHandle(original,out info) || info.Links!=1 || (info.Attributes&0x410)!=0)throw new IOException("Unsafe patch target");
                using(var input=new FileStream(original,FileAccess.Read)) {
                    if(input.Length>1048576)throw new IOException("File too large");
                    string hash;
                    using(var sha=System.Security.Cryptography.SHA256.Create())hash=BitConverter.ToString(sha.ComputeHash(input)).Replace("-","").ToLowerInvariant();
                    if(!String.Equals(hash,expected,StringComparison.OrdinalIgnoreCase))throw new IOException("File changed; approval is stale");
                    string temporary=Path.Combine(parent,".agent-studio-patch-"+Guid.NewGuid().ToString("N")+".tmp");
                    using(var replacement=CreateFile(temporary,0x40010000,0,IntPtr.Zero,1,0x00200000,IntPtr.Zero)) {
                        if(replacement.IsInvalid)throw new Win32Exception(Marshal.GetLastWin32Error());
                        bool moved=false;
                        try {
                            uint written;
                            if(!WriteFile(replacement,content,(uint)content.Length,out written,IntPtr.Zero) || written!=content.Length || !FlushFileBuffers(replacement))throw new Win32Exception(Marshal.GetLastWin32Error());
                            input.Dispose(); original.Dispose();
                            using(var current=CreateFile(Path.Combine(parent,name),0x80000000,5,IntPtr.Zero,3,0x00200000,IntPtr.Zero)) {
                            Info latest;
                            if(current.IsInvalid || !GetFileInformationByHandle(current,out latest) || latest.Volume!=info.Volume || latest.IndexHigh!=info.IndexHigh || latest.IndexLow!=info.IndexLow || latest.Links!=1 || (latest.Attributes&0x410)!=0)
                                throw new IOException("Patch target changed before atomic replacement");
                            using(var check=new FileStream(current,FileAccess.Read)) {
                                using(var sha=System.Security.Cryptography.SHA256.Create())if(BitConverter.ToString(sha.ComputeHash(check)).Replace("-","").ToLowerInvariant()!=expected.ToLowerInvariant())throw new IOException("Patch content changed before atomic replacement");
                            byte[] filename=Encoding.Unicode.GetBytes(name);
                            int rootOffset=IntPtr.Size==8?8:4, lengthOffset=rootOffset+IntPtr.Size, filenameOffset=lengthOffset+4;
                            IntPtr rename=Marshal.AllocHGlobal(filenameOffset+filename.Length);
                            try {
                                for(int i=0;i<filenameOffset+filename.Length;i++)Marshal.WriteByte(rename,i,0);
                                Marshal.WriteInt32(rename,0,3); // REPLACE_IF_EXISTS | POSIX_SEMANTICS; fail closed if unsupported.
                                Marshal.WriteIntPtr(rename,rootOffset,handles[handles.Count-1].DangerousGetHandle());
                                Marshal.WriteInt32(rename,lengthOffset,filename.Length);Marshal.Copy(filename,0,IntPtr.Add(rename,filenameOffset),filename.Length);
                                IoStatus io;uint status=NtSetInformationFile(replacement,out io,rename,(uint)(filenameOffset+filename.Length),65);
                                if(status!=0)throw new IOException("Atomic rename unsupported or rejected; NTSTATUS=0x"+status.ToString("x8"));
                                moved=true;
                            } finally { Marshal.FreeHGlobal(rename); }
                            }
                            }
                        } finally { if(!moved){int remove=1;SetFileInformationByHandle(replacement,4,ref remove,4);} }
                    }
                }
            }
        } finally { for(int i=handles.Count-1;i>=0;i--)handles[i].Dispose(); }
    }
    public static void Verify(string[] paths) {
        foreach(string path in paths) {
            using(var file=CreateFile(Path.GetFullPath(path),0x80000000,1,IntPtr.Zero,3,0x00200000,IntPtr.Zero)) {
                Info info;
                if(file.IsInvalid || !GetFileInformationByHandle(file,out info))throw new IOException("File safety inspection failed");
                if(info.Links!=1 || (info.Attributes&0x400)!=0 || (info.Attributes&0x10)!=0)throw new IOException("Hard links, reparse points and non-regular files are forbidden");
            }
        }
    }
    public static void Create(string parent,string name,byte[] content) {
        parent=Path.GetFullPath(parent);
        if(name!=Path.GetFileName(name) || name.IndexOf(':')>=0)throw new IOException("Invalid file name");
        var handles=new List<SafeFileHandle>();
        try {
            string cursor=Path.GetPathRoot(parent);
            var parts=parent.Substring(cursor.Length).Split(new[]{Path.DirectorySeparatorChar},StringSplitOptions.RemoveEmptyEntries);
            for(int i=-1;i<parts.Length;i++) {
                if(i>=0)cursor=Path.Combine(cursor,parts[i]);
                // Withhold write/delete sharing while holding every ancestor. Fail closed on sharing conflicts.
                var handle=CreateFile(cursor,0x80000000,1,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
                if(handle.IsInvalid){handle.Dispose();throw new Win32Exception(Marshal.GetLastWin32Error());}
                handles.Add(handle);
                uint attributes=GetFileAttributes(cursor);
                if(attributes==0xffffffff || (attributes&0x400)!=0 || (attributes&0x10)==0)throw new IOException("Directory reparse points are forbidden");
            }
            string target=Path.Combine(parent,name);
            using(var file=CreateFile(target,0x40010000,0,IntPtr.Zero,1,0x00200000,IntPtr.Zero)) {
                if(file.IsInvalid)throw new Win32Exception(Marshal.GetLastWin32Error());
                try {
                    uint written;
                    if(!WriteFile(file,content,(uint)content.Length,out written,IntPtr.Zero) || written!=content.Length || !FlushFileBuffers(file))
                        throw new Win32Exception(Marshal.GetLastWin32Error());
                } catch {
                    int remove=1; SetFileInformationByHandle(file,4,ref remove,4); throw;
                }
            }
        } finally { for(int i=handles.Count-1;i>=0;i--)handles[i].Dispose(); }
    }
}
'@
    if ($taskInput.mode -eq 'identity') {
        [Console]::WriteLine('IDENTITY:' + [GuardedProjectCreate]::Identity([string]$taskInput.path))
    } elseif ($taskInput.mode -eq 'verify') {
        [GuardedProjectCreate]::Verify([string[]]$taskInput.paths)
    } elseif ($taskInput.mode -eq 'patch') {
        [GuardedProjectCreate]::Patch([string]$taskInput.parent,[string]$taskInput.name,[Convert]::FromBase64String([string]$taskInput.content),[string]$taskInput.expected)
    } else {
        [GuardedProjectCreate]::Create([string]$taskInput.parent,[string]$taskInput.name,[Convert]::FromBase64String([string]$taskInput.content))
    }
    [Console]::WriteLine('CREATED')
    exit 0
} catch {
    $taskFailure = $_.Exception
    while ($taskFailure.InnerException) { $taskFailure = $taskFailure.InnerException }
    [Console]::WriteLine($taskFailure.GetType().FullName + ': ' + $taskFailure.Message + ' ' + $taskFailure.StackTrace)
    exit 1
}
