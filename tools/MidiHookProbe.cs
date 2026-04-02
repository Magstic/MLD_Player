using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

internal static class MidiHookProbe
{
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate IntPtr EntryDelegate();

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int BlobDelegate(byte[] buffer, int length);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int MediaQueryDelegate(int media, int key, ref int value);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int HandleDelegate(int handle);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int PlayerValueDelegate(int player, int value);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int PlayerAttrDelegate(int player, int key, int value);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int AttachDelegate(int player, int media);

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate int MidiOutShortMsgDelegate(IntPtr handle, uint message);

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate int MidiOutLongMsgDelegate(IntPtr handle, IntPtr midiHeader, uint size);

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate int MidiOutOpenDelegate(out IntPtr handle, uint deviceId, IntPtr callback, IntPtr instance, uint flags);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int ControlResetDelegate(int controlObject);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int ControlTripletDelegate(int controlObject, int arg1, int arg2, int arg3);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int ControlMaskDelegate(int controlObject, int mask);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int SyncDispatchDelegate(int playerState, int p1, int p2, int p3);

    private static class Native
    {
        public const uint PageExecuteReadWrite = 0x40;
        public const uint MemCommit = 0x1000;
        public const uint MemReserve = 0x2000;

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern IntPtr LoadLibrary(string lpFileName);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool VirtualProtect(IntPtr lpAddress, UIntPtr dwSize, uint flNewProtect, out uint lpflOldProtect);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr VirtualAlloc(IntPtr lpAddress, UIntPtr dwSize, uint flAllocationType, uint flProtect);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr GetCurrentProcess();

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool FlushInstructionCache(IntPtr hProcess, IntPtr lpBaseAddress, UIntPtr dwSize);
    }

    private sealed class InlineHook
    {
        private readonly IntPtr _target;
        private readonly IntPtr _hook;
        private readonly int _patchLength;
        private readonly byte[] _originalBytes;

        public InlineHook(IntPtr target, IntPtr hook, int patchLength)
        {
            _target = target;
            _hook = hook;
            _patchLength = patchLength;
            _originalBytes = new byte[patchLength];
            Marshal.Copy(target, _originalBytes, 0, patchLength);
        }

        public IntPtr Install()
        {
            IntPtr trampoline = Native.VirtualAlloc(
                IntPtr.Zero,
                (UIntPtr)(_patchLength + 6),
                Native.MemCommit | Native.MemReserve,
                Native.PageExecuteReadWrite);
            if (trampoline == IntPtr.Zero)
            {
                throw new InvalidOperationException("VirtualAlloc failed while creating trampoline");
            }

            Marshal.Copy(_originalBytes, 0, trampoline, _patchLength);
            WritePushRet(
                IntPtr.Add(trampoline, _patchLength),
                IntPtr.Add(_target, _patchLength),
                6);

            WritePushRet(_target, _hook, _patchLength);
            return trampoline;
        }

        private static void WritePushRet(IntPtr address, IntPtr target, int totalLength)
        {
            if (totalLength < 6)
            {
                throw new ArgumentOutOfRangeException("totalLength", "Inline hook needs at least 6 bytes");
            }

            byte[] patch = new byte[totalLength];
            patch[0] = 0x68;
            Array.Copy(BitConverter.GetBytes(target.ToInt32()), 0, patch, 1, 4);
            patch[5] = 0xC3;
            for (int i = 6; i < patch.Length; i++)
            {
                patch[i] = 0x90;
            }

            uint oldProtect;
            if (!Native.VirtualProtect(address, (UIntPtr)patch.Length, Native.PageExecuteReadWrite, out oldProtect))
            {
                throw new InvalidOperationException("VirtualProtect failed while writing inline hook");
            }

            Marshal.Copy(patch, 0, address, patch.Length);
            uint ignoredProtect;
            Native.VirtualProtect(address, (UIntPtr)patch.Length, oldProtect, out ignoredProtect);
            Native.FlushInstructionCache(Native.GetCurrentProcess(), address, (UIntPtr)patch.Length);
        }
    }

    private sealed class WinMmIatHook
    {
        private readonly object _gate = new object();
        private readonly HashSet<IntPtr> _patchedBases = new HashSet<IntPtr>();
        private readonly List<string> _events = new List<string>();
        private readonly Stopwatch _clock = new Stopwatch();
        private readonly MidiOutShortMsgDelegate _shortHook;
        private readonly MidiOutLongMsgDelegate _longHook;
        private readonly MidiOutOpenDelegate _openHook;
        private readonly IntPtr _shortHookPtr;
        private readonly IntPtr _longHookPtr;
        private readonly IntPtr _openHookPtr;
        private readonly MidiOutShortMsgDelegate _shortOriginal;
        private readonly MidiOutLongMsgDelegate _longOriginal;
        private readonly MidiOutOpenDelegate _openOriginal;
        private int _droppedEvents;
        private string _sampleName = string.Empty;

        public WinMmIatHook()
        {
            IntPtr winmm = Native.LoadLibrary("winmm.dll");
            if (winmm == IntPtr.Zero)
            {
                throw new InvalidOperationException("LoadLibrary(winmm.dll) failed");
            }

            _shortOriginal = Marshal.GetDelegateForFunctionPointer<MidiOutShortMsgDelegate>(
                RequireProc(winmm, "midiOutShortMsg"));
            _longOriginal = Marshal.GetDelegateForFunctionPointer<MidiOutLongMsgDelegate>(
                RequireProc(winmm, "midiOutLongMsg"));
            _openOriginal = Marshal.GetDelegateForFunctionPointer<MidiOutOpenDelegate>(
                RequireProc(winmm, "midiOutOpen"));

            _shortHook = HookShort;
            _longHook = HookLong;
            _openHook = HookOpen;

            _shortHookPtr = Marshal.GetFunctionPointerForDelegate(_shortHook);
            _longHookPtr = Marshal.GetFunctionPointerForDelegate(_longHook);
            _openHookPtr = Marshal.GetFunctionPointerForDelegate(_openHook);
        }

        public void BeginSample(string sampleName)
        {
            lock (_gate)
            {
                _sampleName = Path.GetFileName(sampleName);
                _events.Clear();
                _droppedEvents = 0;
                _clock.Restart();
            }
        }

        public void EndSample()
        {
            lock (_gate)
            {
                _clock.Stop();
            }
        }

        public IReadOnlyList<string> Snapshot()
        {
            lock (_gate)
            {
                List<string> copy = new List<string>(_events.Count + 1);
                copy.AddRange(_events);
                if (_droppedEvents != 0)
                {
                    copy.Add("... dropped " + _droppedEvents + " additional events");
                }
                return copy;
            }
        }

        public void PatchLoadedModules()
        {
            Process process = Process.GetCurrentProcess();
            foreach (ProcessModule module in process.Modules)
            {
                IntPtr baseAddress = module.BaseAddress;
                lock (_gate)
                {
                    if (_patchedBases.Contains(baseAddress))
                    {
                        continue;
                    }
                }

                if (PatchModuleImports(baseAddress))
                {
                    lock (_gate)
                    {
                        _patchedBases.Add(baseAddress);
                    }
                }
            }
        }

        private static IntPtr RequireProc(IntPtr module, string name)
        {
            IntPtr proc = Native.GetProcAddress(module, name);
            if (proc == IntPtr.Zero)
            {
                throw new InvalidOperationException("GetProcAddress failed for " + name);
            }
            return proc;
        }

        private bool PatchModuleImports(IntPtr moduleBase)
        {
            int peOffset = Marshal.ReadInt32(moduleBase, 0x3C);
            IntPtr peHeader = IntPtr.Add(moduleBase, peOffset);
            ushort magic = (ushort)Marshal.ReadInt16(IntPtr.Add(peHeader, 0x18));
            if (magic != 0x10B)
            {
                return false;
            }

            int importDirectoryRva = Marshal.ReadInt32(IntPtr.Add(peHeader, 0x80));
            if (importDirectoryRva == 0)
            {
                return false;
            }

            bool patched = false;
            IntPtr importDescriptor = IntPtr.Add(moduleBase, importDirectoryRva);
            while (true)
            {
                int originalFirstThunkRva = Marshal.ReadInt32(importDescriptor, 0x00);
                int nameRva = Marshal.ReadInt32(importDescriptor, 0x0C);
                int firstThunkRva = Marshal.ReadInt32(importDescriptor, 0x10);
                if (originalFirstThunkRva == 0 && nameRva == 0 && firstThunkRva == 0)
                {
                    break;
                }

                string moduleName = Marshal.PtrToStringAnsi(IntPtr.Add(moduleBase, nameRva)) ?? string.Empty;
                if (moduleName.Equals("winmm.dll", StringComparison.OrdinalIgnoreCase))
                {
                    int lookupRva = originalFirstThunkRva != 0 ? originalFirstThunkRva : firstThunkRva;
                    IntPtr lookup = IntPtr.Add(moduleBase, lookupRva);
                    IntPtr thunk = IntPtr.Add(moduleBase, firstThunkRva);
                    int index = 0;
                    while (true)
                    {
                        int lookupValue = Marshal.ReadInt32(lookup, index * 4);
                        if (lookupValue == 0)
                        {
                            break;
                        }

                        if ((lookupValue & unchecked((int)0x80000000)) == 0)
                        {
                            IntPtr importByName = IntPtr.Add(moduleBase, lookupValue);
                            string importName = Marshal.PtrToStringAnsi(IntPtr.Add(importByName, 2)) ?? string.Empty;
                            IntPtr replacement = IntPtr.Zero;
                            if (importName.Equals("midiOutShortMsg", StringComparison.OrdinalIgnoreCase))
                            {
                                replacement = _shortHookPtr;
                            }
                            else if (importName.Equals("midiOutLongMsg", StringComparison.OrdinalIgnoreCase))
                            {
                                replacement = _longHookPtr;
                            }
                            else if (importName.Equals("midiOutOpen", StringComparison.OrdinalIgnoreCase))
                            {
                                replacement = _openHookPtr;
                            }

                            if (replacement != IntPtr.Zero)
                            {
                                IntPtr target = IntPtr.Add(thunk, index * 4);
                                uint oldProtect;
                                if (!Native.VirtualProtect(target, (UIntPtr)4, Native.PageExecuteReadWrite, out oldProtect))
                                {
                                    throw new InvalidOperationException("VirtualProtect failed while patching IAT");
                                }
                                Marshal.WriteIntPtr(target, replacement);
                                uint ignoredProtect;
                                Native.VirtualProtect(target, (UIntPtr)4, oldProtect, out ignoredProtect);
                                patched = true;
                            }
                        }

                        index++;
                    }
                }

                importDescriptor = IntPtr.Add(importDescriptor, 20);
            }

            return patched;
        }

        private int HookShort(IntPtr handle, uint message)
        {
            byte status = (byte)(message & 0xFF);
            byte data1 = (byte)((message >> 8) & 0xFF);
            byte data2 = (byte)((message >> 16) & 0xFF);
            AddEvent(
                "SHORT"
                + " t=" + _clock.ElapsedMilliseconds
                + " st=0x" + status.ToString("X2")
                + " d1=" + data1
                + " d2=" + data2
                + " raw=0x" + message.ToString("X8"));
            return _shortOriginal(handle, message);
        }

        private int HookLong(IntPtr handle, IntPtr midiHeader, uint size)
        {
            AddEvent("LONG t=" + _clock.ElapsedMilliseconds + " size=" + size);
            return _longOriginal(handle, midiHeader, size);
        }

        private int HookOpen(out IntPtr handle, uint deviceId, IntPtr callback, IntPtr instance, uint flags)
        {
            int result = _openOriginal(out handle, deviceId, callback, instance, flags);
            AddEvent(
                "OPEN"
                + " t=" + _clock.ElapsedMilliseconds
                + " dev=" + deviceId
                + " flags=0x" + flags.ToString("X")
                + " result=" + result
                + " handle=0x" + handle.ToInt64().ToString("X"));
            return result;
        }

        private void AddEvent(string line)
        {
            lock (_gate)
            {
                if (_events.Count < 512)
                {
                    _events.Add((_sampleName.Length == 0 ? string.Empty : _sampleName + " ") + line);
                }
                else
                {
                    _droppedEvents++;
                }
            }
        }
    }

    private sealed class MdControlHook
    {
        private const int PatchLength = 6;
        private const int ResetRva = 0x163E0;
        private const int TripletRva = 0x16420;
        private const int MaskRva = 0x16450;

        private readonly object _gate = new object();
        private readonly List<string> _events = new List<string>();
        private readonly Stopwatch _clock = new Stopwatch();
        private readonly ControlResetDelegate _resetHook;
        private readonly ControlTripletDelegate _tripletHook;
        private readonly ControlMaskDelegate _maskHook;
        private readonly IntPtr _resetHookPtr;
        private readonly IntPtr _tripletHookPtr;
        private readonly IntPtr _maskHookPtr;
        private ControlResetDelegate _resetOriginal;
        private ControlTripletDelegate _tripletOriginal;
        private ControlMaskDelegate _maskOriginal;
        private int _droppedEvents;
        private string _sampleName = string.Empty;

        public MdControlHook()
        {
            _resetHook = HookReset;
            _tripletHook = HookTriplet;
            _maskHook = HookMask;
            _resetHookPtr = Marshal.GetFunctionPointerForDelegate(_resetHook);
            _tripletHookPtr = Marshal.GetFunctionPointerForDelegate(_tripletHook);
            _maskHookPtr = Marshal.GetFunctionPointerForDelegate(_maskHook);
        }

        public bool TryInstall(IntPtr moduleBase)
        {
            IntPtr resetTarget = IntPtr.Add(moduleBase, ResetRva);
            IntPtr tripletTarget = IntPtr.Add(moduleBase, TripletRva);
            IntPtr maskTarget = IntPtr.Add(moduleBase, MaskRva);

            byte[] expectedPrefix = { 0x8B, 0x44, 0x24, 0x04, 0x85, 0xC0 };
            if (!HasBytes(resetTarget, expectedPrefix)
                || !HasBytes(tripletTarget, expectedPrefix)
                || !HasBytes(maskTarget, expectedPrefix))
            {
                return false;
            }

            IntPtr resetTrampoline = new InlineHook(resetTarget, _resetHookPtr, PatchLength).Install();
            IntPtr tripletTrampoline = new InlineHook(tripletTarget, _tripletHookPtr, PatchLength).Install();
            IntPtr maskTrampoline = new InlineHook(maskTarget, _maskHookPtr, PatchLength).Install();

            _resetOriginal = Marshal.GetDelegateForFunctionPointer<ControlResetDelegate>(resetTrampoline);
            _tripletOriginal = Marshal.GetDelegateForFunctionPointer<ControlTripletDelegate>(tripletTrampoline);
            _maskOriginal = Marshal.GetDelegateForFunctionPointer<ControlMaskDelegate>(maskTrampoline);
            return true;
        }

        public void BeginSample(string sampleName)
        {
            lock (_gate)
            {
                _sampleName = Path.GetFileName(sampleName);
                _events.Clear();
                _droppedEvents = 0;
                _clock.Restart();
            }
        }

        public void EndSample()
        {
            lock (_gate)
            {
                _clock.Stop();
            }
        }

        public IReadOnlyList<string> Snapshot()
        {
            lock (_gate)
            {
                List<string> copy = new List<string>(_events.Count + 1);
                copy.AddRange(_events);
                if (_droppedEvents != 0)
                {
                    copy.Add("... dropped " + _droppedEvents + " additional events");
                }
                return copy;
            }
        }

        private static bool HasBytes(IntPtr address, byte[] expected)
        {
            byte[] actual = new byte[expected.Length];
            Marshal.Copy(address, actual, 0, actual.Length);
            for (int i = 0; i < expected.Length; i++)
            {
                if (actual[i] != expected[i])
                {
                    return false;
                }
            }
            return true;
        }

        private int HookReset(int controlObject)
        {
            AddEvent(
                "RESET"
                + " t=" + _clock.ElapsedMilliseconds
                + DescribeControlObject(controlObject));
            if (_resetOriginal == null)
            {
                throw new InvalidOperationException("Reset trampoline is not installed");
            }
            return _resetOriginal(controlObject);
        }

        private int HookTriplet(int controlObject, int arg1, int arg2, int arg3)
        {
            AddEvent(
                "TRIPLET"
                + " t=" + _clock.ElapsedMilliseconds
                + DescribeControlObject(controlObject)
                + " family=" + (arg1 & 0xFF)
                + " selector=" + (arg2 & 0xFF)
                + " note=" + (arg3 & 0xFF));
            if (_tripletOriginal == null)
            {
                throw new InvalidOperationException("Triplet trampoline is not installed");
            }
            return _tripletOriginal(controlObject, arg1, arg2, arg3);
        }

        private int HookMask(int controlObject, int mask)
        {
            AddEvent(
                "MASK"
                + " t=" + _clock.ElapsedMilliseconds
                + DescribeControlObject(controlObject)
                + " mask=0x" + (mask & 0xFFFF).ToString("X4"));
            if (_maskOriginal == null)
            {
                throw new InvalidOperationException("Mask trampoline is not installed");
            }
            return _maskOriginal(controlObject, mask);
        }

        private static string DescribeControlObject(int controlObject)
        {
            try
            {
                IntPtr wrapper = new IntPtr(controlObject);
                int inner = Marshal.ReadInt32(wrapper, 12);
                int d0 = inner != 0 ? Marshal.ReadInt32(new IntPtr(inner), 0) : 0;
                int d4 = inner != 0 ? Marshal.ReadInt32(new IntPtr(inner), 4) : 0;
                int d8 = inner != 0 ? Marshal.ReadInt32(new IntPtr(inner), 8) : 0;
                int dc = inner != 0 ? Marshal.ReadInt32(new IntPtr(inner), 12) : 0;
                int iface = inner != 0 ? Marshal.ReadInt32(new IntPtr(inner), 40) : 0;
                int handler = 0;
                int target = inner != 0 ? Marshal.ReadInt32(new IntPtr(inner), 48) : 0;
                if (iface != 0)
                {
                    handler = Marshal.ReadInt32(new IntPtr(iface), 8);
                }
                return " obj=0x" + controlObject.ToString("X8")
                    + " inner=0x" + inner.ToString("X8")
                    + " d0=0x" + d0.ToString("X8")
                    + " d4=0x" + d4.ToString("X8")
                    + " d8=0x" + d8.ToString("X8")
                    + " dc=0x" + dc.ToString("X8")
                    + " iface=0x" + iface.ToString("X8")
                    + " handler=0x" + handler.ToString("X8")
                    + " target=0x" + target.ToString("X8") + " ";
            }
            catch
            {
                return " obj=0x" + controlObject.ToString("X8") + " ";
            }
        }

        private void AddEvent(string line)
        {
            lock (_gate)
            {
                if (_events.Count < 512)
                {
                    _events.Add((_sampleName.Length == 0 ? string.Empty : _sampleName + " ") + line);
                }
                else
                {
                    _droppedEvents++;
                }
            }
        }
    }

    private sealed class PlayerSyncHook
    {
        private const int PatchLength = 11;
        private const int SyncDispatchRva = 0x37FD0;

        private readonly object _gate = new object();
        private readonly List<string> _events = new List<string>();
        private readonly Stopwatch _clock = new Stopwatch();
        private readonly SyncDispatchDelegate _syncHook;
        private readonly IntPtr _syncHookPtr;
        private SyncDispatchDelegate _syncOriginal;
        private int _droppedEvents;
        private string _sampleName = string.Empty;

        public PlayerSyncHook()
        {
            _syncHook = HookSyncDispatch;
            _syncHookPtr = Marshal.GetFunctionPointerForDelegate(_syncHook);
        }

        public bool TryInstall(IntPtr moduleBase)
        {
            IntPtr syncTarget = IntPtr.Add(moduleBase, SyncDispatchRva);
            byte[] expectedPrefix = { 0x56, 0x8B, 0x74, 0x24, 0x08, 0x8B, 0x86, 0x4C, 0x0F, 0x00, 0x00 };
            if (!HasBytes(syncTarget, expectedPrefix))
            {
                return false;
            }

            IntPtr syncTrampoline = new InlineHook(syncTarget, _syncHookPtr, PatchLength).Install();
            _syncOriginal = Marshal.GetDelegateForFunctionPointer<SyncDispatchDelegate>(syncTrampoline);
            return true;
        }

        public void BeginSample(string sampleName)
        {
            lock (_gate)
            {
                _sampleName = Path.GetFileName(sampleName);
                _events.Clear();
                _droppedEvents = 0;
                _clock.Restart();
            }
        }

        public void EndSample()
        {
            lock (_gate)
            {
                _clock.Stop();
            }
        }

        public IReadOnlyList<string> Snapshot()
        {
            lock (_gate)
            {
                List<string> copy = new List<string>(_events.Count + 1);
                copy.AddRange(_events);
                if (_droppedEvents != 0)
                {
                    copy.Add("... dropped " + _droppedEvents + " additional events");
                }
                return copy;
            }
        }

        private static bool HasBytes(IntPtr address, byte[] expected)
        {
            byte[] actual = new byte[expected.Length];
            Marshal.Copy(address, actual, 0, actual.Length);
            for (int i = 0; i < expected.Length; i++)
            {
                if (actual[i] != expected[i])
                {
                    return false;
                }
            }
            return true;
        }

        private int HookSyncDispatch(int playerState, int p1, int p2, int p3)
        {
            AddEvent(
                "SYNC_DISPATCH"
                + " t=" + _clock.ElapsedMilliseconds
                + DescribePlayerState(playerState, p1, p2, p3));
            if (_syncOriginal == null)
            {
                throw new InvalidOperationException("Sync trampoline is not installed");
            }
            return _syncOriginal(playerState, p1, p2, p3);
        }

        private static string DescribePlayerState(int playerState, int p1, int p2, int p3)
        {
            try
            {
                IntPtr state = new IntPtr(playerState);
                int syncSlot = Marshal.ReadInt32(state, 7204);
                int syncCtx = Marshal.ReadInt32(state, 7208);
                int slotFn = syncSlot != 0 ? Marshal.ReadInt32(new IntPtr(syncSlot), 8) : 0;
                int slotUser = syncSlot != 0 ? Marshal.ReadInt32(new IntPtr(syncSlot), 12) : 0;
                int matchP1 = syncCtx != 0 ? Marshal.ReadInt32(new IntPtr(syncCtx), 4) : 0;
                int matchP2 = syncCtx != 0 ? Marshal.ReadInt32(new IntPtr(syncCtx), 8) : 0;
                return " state=0x" + playerState.ToString("X8")
                    + " p1=" + p1
                    + " p2=" + p2
                    + " p3=" + p3
                    + " slot=0x" + syncSlot.ToString("X8")
                    + " slot_fn=0x" + slotFn.ToString("X8")
                    + " slot_ud=0x" + slotUser.ToString("X8")
                    + " ctx=0x" + syncCtx.ToString("X8")
                    + " match_p1=" + matchP1
                    + " match_p2=" + matchP2;
            }
            catch
            {
                return " state=0x" + playerState.ToString("X8")
                    + " p1=" + p1
                    + " p2=" + p2
                    + " p3=" + p3;
            }
        }

        private void AddEvent(string line)
        {
            lock (_gate)
            {
                if (_events.Count < 512)
                {
                    _events.Add((_sampleName.Length == 0 ? string.Empty : _sampleName + " ") + line);
                }
                else
                {
                    _droppedEvents++;
                }
            }
        }
    }

    private static T GetDelegate<T>(IntPtr table, int offset)
    {
        IntPtr functionPointer = Marshal.ReadIntPtr(table, offset);
        if (functionPointer == IntPtr.Zero)
        {
            throw new InvalidOperationException("Null function pointer at offset 0x" + offset.ToString("X"));
        }

        return Marshal.GetDelegateForFunctionPointer<T>(functionPointer);
    }

    private static byte[] ConvertUInt32ArrayToBytes(uint[] values)
    {
        byte[] buffer = new byte[values.Length * 4];
        for (int i = 0; i < values.Length; i++)
        {
            Array.Copy(BitConverter.GetBytes(values[i]), 0, buffer, i * 4, 4);
        }
        return buffer;
    }

    private static uint[] ReadUInt32Words(byte[] buffer, int wordCount)
    {
        uint[] values = new uint[wordCount];
        for (int i = 0; i < wordCount; i++)
        {
            values[i] = BitConverter.ToUInt32(buffer, i * 4);
        }
        return values;
    }

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length < 2)
            {
                Console.Error.WriteLine("Usage: MidiHookProbe.exe <libPath> <sample1> [sample2 ...]");
                return 2;
            }

            string libPath = Path.GetFullPath(args[0]);
            string[] samples = new string[args.Length - 1];
            for (int i = 1; i < args.Length; i++)
            {
                samples[i - 1] = Path.GetFullPath(args[i]);
            }

            WinMmIatHook hook = new WinMmIatHook();
            MdControlHook mdHook = new MdControlHook();
            PlayerSyncHook syncHook = new PlayerSyncHook();

            IntPtr module = Native.LoadLibrary(libPath);
            if (module == IntPtr.Zero)
            {
                Console.Error.WriteLine("LoadLibrary failed for " + libPath);
                return 1;
            }

            IntPtr entryPtr = Native.GetProcAddress(module, "SoundLibraryMFi5Entry");
            if (entryPtr == IntPtr.Zero)
            {
                Console.Error.WriteLine("SoundLibraryMFi5Entry export not found");
                return 1;
            }

            EntryDelegate entry = Marshal.GetDelegateForFunctionPointer<EntryDelegate>(entryPtr);
            IntPtr table = entry();
            if (table == IntPtr.Zero)
            {
                Console.Error.WriteLine("SoundLibraryMFi5Entry returned null");
                return 1;
            }

            hook.PatchLoadedModules();
            bool mdHookInstalled = mdHook.TryInstall(module);
            bool syncHookInstalled = syncHook.TryInstall(module);

            BlobDelegate configure = GetDelegate<BlobDelegate>(table, 0x38);
            BlobDelegate createPlayer = GetDelegate<BlobDelegate>(table, 0x48);
            PlayerAttrDelegate setAttribute = GetDelegate<PlayerAttrDelegate>(table, 0x50);
            AttachDelegate attachMedia = GetDelegate<AttachDelegate>(table, 0x58);
            HandleDelegate play = GetDelegate<HandleDelegate>(table, 0x5C);
            HandleDelegate stop = GetDelegate<HandleDelegate>(table, 0x60);
            PlayerValueDelegate setPosition = GetDelegate<PlayerValueDelegate>(table, 0x64);
            HandleDelegate getPosition = GetDelegate<HandleDelegate>(table, 0x68);
            HandleDelegate getStatus = GetDelegate<HandleDelegate>(table, 0x6C);
            PlayerValueDelegate playerInit = GetDelegate<PlayerValueDelegate>(table, 0x74);
            BlobDelegate loadMemory = GetDelegate<BlobDelegate>(table, 0x0C);
            HandleDelegate destroyMedia = GetDelegate<HandleDelegate>(table, 0x10);
            MediaQueryDelegate queryMedia = GetDelegate<MediaQueryDelegate>(table, 0x14);
            HandleDelegate getMaxPosition = GetDelegate<HandleDelegate>(table, 0x20);

            uint[] libWords = { 24U, 0U, 0U, 0U, 0U, 0U };
            byte[] libBuffer = ConvertUInt32ArrayToBytes(libWords);
            int configureResult = configure(libBuffer, libBuffer.Length);
            libWords = ReadUInt32Words(libBuffer, 6);

            uint[] playerWords = new uint[42];
            playerWords[0] = 168;
            playerWords[1] = libWords[2];
            playerWords[2] = libWords[3];
            playerWords[3] = libWords[4];
            for (int i = 0; i < 16; i++)
            {
                playerWords[6 + (i * 2)] = 0xFFFFFFFFU;
                playerWords[7 + (i * 2)] = 0U;
            }
            playerWords[38] = 4;
            playerWords[39] = 2 * libWords[5];
            playerWords[40] = 1;
            playerWords[41] = 1;

            byte[] playerBuffer = ConvertUInt32ArrayToBytes(playerWords);
            int player = createPlayer(playerBuffer, -1);
            hook.PatchLoadedModules();
            int playerInitResult = player != 0 ? playerInit(player, 0) : -1;

            Console.WriteLine("LIB " + libPath);
            Console.WriteLine(
                "INIT configure=" + configureResult
                + " player=" + player
                + " player_init=" + playerInitResult
                + " md_hook=" + (mdHookInstalled ? "on" : "off")
                + " sync_hook=" + (syncHookInstalled ? "on" : "off"));

            foreach (string sample in samples)
            {
                byte[] data = File.ReadAllBytes(sample);
                int media = loadMemory(data, data.Length);
                hook.PatchLoadedModules();

                int attachResult = -999;
                int queryValue = 0;
                int queryResult = -999;
                int setVolumeResult = -999;
                int setLoopResult = -999;
                int setPositionResult = -999;
                int playResult = -999;
                int status = -999;
                int position = -999;
                int maxPosition = -999;
                int stopResult = -999;
                int destroyResult = -999;

                hook.BeginSample(sample);
                mdHook.BeginSample(sample);
                syncHook.BeginSample(sample);
                if (media != 0 && player != 0)
                {
                    attachResult = attachMedia(player, media);
                    queryResult = queryMedia(media, 7, ref queryValue);
                    setVolumeResult = setAttribute(player, 4, 100);
                    setLoopResult = setAttribute(player, 6, 0);
                    setPositionResult = setPosition(player, 0);
                    hook.PatchLoadedModules();
                    playResult = play(player);

                    int[] traceSchedule = { 100, 300, 700 };
                    int elapsed = 0;
                    foreach (int sleep in traceSchedule)
                    {
                        System.Threading.Thread.Sleep(sleep);
                        elapsed += sleep;
                        status = getStatus(player);
                        position = getPosition(player);
                        Console.WriteLine(
                            "TRACE " + Path.GetFileName(sample)
                            + " t=" + elapsed
                            + " status=" + status
                            + " pos=" + position);
                    }

                    maxPosition = getMaxPosition(media);
                    stopResult = stop(player);
                }
                hook.EndSample();
                mdHook.EndSample();
                syncHook.EndSample();

                if (media != 0)
                {
                    destroyResult = destroyMedia(media);
                }

                Console.WriteLine(
                    "SAMPLE " + sample
                    + " media=" + media
                    + " attach=" + attachResult
                    + " query7=(" + queryResult + "," + queryValue + ")"
                    + " setVol=" + setVolumeResult
                    + " setLoop=" + setLoopResult
                    + " setPos=" + setPositionResult
                    + " play=" + playResult
                    + " status=" + status
                    + " pos=" + position
                    + " max=" + maxPosition
                    + " stop=" + stopResult
                    + " destroy=" + destroyResult);

                IReadOnlyList<string> log = hook.Snapshot();
                if (log.Count == 0)
                {
                    Console.WriteLine("MIDI " + Path.GetFileName(sample) + " none");
                }
                else
                {
                    foreach (string line in log)
                    {
                        Console.WriteLine("MIDI " + line);
                    }
                }

                IReadOnlyList<string> mdLog = mdHook.Snapshot();
                if (mdLog.Count == 0)
                {
                    Console.WriteLine("MDCTRL " + Path.GetFileName(sample) + " none");
                }
                else
                {
                    foreach (string line in mdLog)
                    {
                        Console.WriteLine("MDCTRL " + line);
                    }
                }

                IReadOnlyList<string> syncLog = syncHook.Snapshot();
                if (syncLog.Count == 0)
                {
                    Console.WriteLine("SYNC " + Path.GetFileName(sample) + " none");
                }
                else
                {
                    foreach (string line in syncLog)
                    {
                        Console.WriteLine("SYNC " + line);
                    }
                }
            }

            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("UNHANDLED " + ex.GetType().FullName);
            try
            {
                Console.Error.WriteLine("MESSAGE " + ex.Message);
            }
            catch
            {
                Console.Error.WriteLine("MESSAGE <failed>");
            }

            Exception inner = ex.InnerException;
            int depth = 0;
            while (inner != null && depth < 4)
            {
                Console.Error.WriteLine("INNER[" + depth + "] " + inner.GetType().FullName);
                try
                {
                    Console.Error.WriteLine("INNER_MESSAGE[" + depth + "] " + inner.Message);
                }
                catch
                {
                    Console.Error.WriteLine("INNER_MESSAGE[" + depth + "] <failed>");
                }
                inner = inner.InnerException;
                depth++;
            }
            return 1;
        }
    }
}
