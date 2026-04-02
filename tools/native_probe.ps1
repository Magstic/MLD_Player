param(
    [Parameter(Mandatory = $true)]
    [string]$LibPath,

    [Parameter(Mandatory = $true)]
    [string[]]$Samples,

    [int]$SleepMs = 150,

    [int[]]$TraceSleepMs = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class NativeMethods
{
    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
    public static extern IntPtr LoadLibrary(string lpFileName);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
    public static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);
}

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate IntPtr EntryDelegate();

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate int BlobDelegate(byte[] buffer, int length);

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate int MediaQueryDelegate(int media, int key, ref int value);

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate int HandleDelegate(int handle);

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate int PlayerValueDelegate(int player, int value);

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate int PlayerAttrDelegate(int player, int key, int value);

[UnmanagedFunctionPointer(CallingConvention.StdCall)]
public delegate int AttachDelegate(int player, int media);
"@

function Get-Delegate {
    param(
        [IntPtr]$Table,
        [int]$Offset,
        [Type]$DelegateType
    )

    $fnPtr = [Runtime.InteropServices.Marshal]::ReadIntPtr($Table, $Offset)
    if ($fnPtr -eq [IntPtr]::Zero) {
        throw "Null function pointer at offset 0x{0:X}" -f $Offset
    }

    return [Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer($fnPtr, $DelegateType)
}

function Convert-UInt32ArrayToBytes {
    param([uint32[]]$Values)

    $buffer = New-Object byte[] ($Values.Length * 4)
    for ($i = 0; $i -lt $Values.Length; $i++) {
        [BitConverter]::GetBytes($Values[$i]).CopyTo($buffer, $i * 4)
    }
    return $buffer
}

function Read-UInt32Words {
    param(
        [byte[]]$Buffer,
        [int]$WordCount
    )

    $values = New-Object uint32[] $WordCount
    for ($i = 0; $i -lt $WordCount; $i++) {
        $values[$i] = [BitConverter]::ToUInt32($Buffer, $i * 4)
    }
    return $values
}

$resolvedLib = (Resolve-Path -LiteralPath $LibPath).Path
$resolvedSamples = @($Samples | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })

$module = [NativeMethods]::LoadLibrary($resolvedLib)
if ($module -eq [IntPtr]::Zero) {
    throw "LoadLibrary failed for $resolvedLib"
}

$entryPtr = [NativeMethods]::GetProcAddress($module, "SoundLibraryMFi5Entry")
if ($entryPtr -eq [IntPtr]::Zero) {
    throw "SoundLibraryMFi5Entry export not found in $resolvedLib"
}

$entry = [Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer(
    $entryPtr,
    [EntryDelegate]
)
$table = $entry.Invoke()
if ($table -eq [IntPtr]::Zero) {
    throw "SoundLibraryMFi5Entry returned null"
}

$configure = Get-Delegate -Table $table -Offset 0x38 -DelegateType ([BlobDelegate])
$createPlayer = Get-Delegate -Table $table -Offset 0x48 -DelegateType ([BlobDelegate])
$setAttribute = Get-Delegate -Table $table -Offset 0x50 -DelegateType ([PlayerAttrDelegate])
$attachMedia = Get-Delegate -Table $table -Offset 0x58 -DelegateType ([AttachDelegate])
$play = Get-Delegate -Table $table -Offset 0x5C -DelegateType ([HandleDelegate])
$stop = Get-Delegate -Table $table -Offset 0x60 -DelegateType ([HandleDelegate])
$setPosition = Get-Delegate -Table $table -Offset 0x64 -DelegateType ([PlayerValueDelegate])
$getPosition = Get-Delegate -Table $table -Offset 0x68 -DelegateType ([HandleDelegate])
$getStatus = Get-Delegate -Table $table -Offset 0x6C -DelegateType ([HandleDelegate])
$playerInit = Get-Delegate -Table $table -Offset 0x74 -DelegateType ([PlayerValueDelegate])
$loadMemory = Get-Delegate -Table $table -Offset 0x0C -DelegateType ([BlobDelegate])
$destroyMedia = Get-Delegate -Table $table -Offset 0x10 -DelegateType ([HandleDelegate])
$queryMedia = Get-Delegate -Table $table -Offset 0x14 -DelegateType ([MediaQueryDelegate])
$getMaxPosition = Get-Delegate -Table $table -Offset 0x20 -DelegateType ([HandleDelegate])

$libWords = [uint32[]](24, 0, 0, 0, 0, 0)
$libBuffer = Convert-UInt32ArrayToBytes $libWords
$configureResult = $configure.Invoke($libBuffer, $libBuffer.Length)
$libWords = Read-UInt32Words -Buffer $libBuffer -WordCount 6

$playerWords = New-Object uint32[] 42
$playerWords[0] = 168
$playerWords[1] = $libWords[2]
$playerWords[2] = $libWords[3]
$playerWords[3] = $libWords[4]
$playerWords[4] = 0
$playerWords[5] = 0
for ($i = 0; $i -lt 16; $i++) {
    $playerWords[6 + ($i * 2)] = [uint32]::MaxValue
    $playerWords[7 + ($i * 2)] = 0
}
$playerWords[38] = 4
$playerWords[39] = [uint32](2 * $libWords[5])
$playerWords[40] = 1
$playerWords[41] = 1
$playerBuffer = Convert-UInt32ArrayToBytes $playerWords

$player = $createPlayer.Invoke($playerBuffer, -1)
$playerInitResult = 0
if ($player -ne 0) {
    $playerInitResult = $playerInit.Invoke($player, 0)
}

Write-Output ("LIB {0}" -f $resolvedLib)
Write-Output (
    "INIT configure={0} player={1} player_init={2} lib_words={3}" -f
    $configureResult,
    $player,
    $playerInitResult,
    (($libWords | ForEach-Object { "0x{0:X8}" -f $_ }) -join ",")
)

foreach ($sample in $resolvedSamples) {
    $bytes = [IO.File]::ReadAllBytes($sample)
    $media = $loadMemory.Invoke($bytes, $bytes.Length)

    $attachResult = -999
    $queryResult = -999
    $queryValue = 0
    $setVolumeResult = -999
    $setLoopResult = -999
    $setPositionResult = -999
    $playResult = -999
    $status = -999
    $position = -999
    $traceParts = @()
    $maxPosition = -999
    $stopResult = -999
    $destroyResult = -999

    if ($media -ne 0 -and $player -ne 0) {
        $attachResult = $attachMedia.Invoke($player, $media)
        $queryResult = $queryMedia.Invoke($media, 7, [ref]$queryValue)
        $setVolumeResult = $setAttribute.Invoke($player, 4, 100)
        $setLoopResult = $setAttribute.Invoke($player, 6, 0)
        $setPositionResult = $setPosition.Invoke($player, 0)
        $playResult = $play.Invoke($player)

        $traceSchedule = @()
        if ($TraceSleepMs.Count -gt 0) {
            $traceSchedule = @($TraceSleepMs)
        } else {
            $traceSchedule = @($SleepMs)
        }

        $elapsedMs = 0
        foreach ($sleep in $traceSchedule) {
            Start-Sleep -Milliseconds $sleep
            $elapsedMs += $sleep
            $status = $getStatus.Invoke($player)
            $position = $getPosition.Invoke($player)
            $traceParts += ("{0}:{1}:{2}" -f $elapsedMs, $status, $position)
        }

        $maxPosition = $getMaxPosition.Invoke($media)
        $stopResult = $stop.Invoke($player)
    }

    if ($media -ne 0) {
        $destroyResult = $destroyMedia.Invoke($media)
    }

    Write-Output (
        "SAMPLE {0} media={1} attach={2} query7=({3},{4}) setVol={5} setLoop={6} setPos={7} play={8} status={9} pos={10} trace={11} max={12} stop={13} destroy={14}" -f
        $sample,
        $media,
        $attachResult,
        $queryResult,
        $queryValue,
        $setVolumeResult,
        $setLoopResult,
        $setPositionResult,
        $playResult,
        $status,
        $position,
        ($traceParts -join ","),
        $maxPosition,
        $stopResult,
        $destroyResult
    )
}
