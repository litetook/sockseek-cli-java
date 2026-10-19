# Installs a PRIVATE copy of Java into jdk\ beside this tool, for a machine that has none.
#
# Why PowerShell and not Python, against this project's own rule: this runs BEFORE any Java
# exists, so it cannot be Java, and a machine with no Java usually has no Python either.
# Windows ships PowerShell. ASCII only, no profile, nothing installed system-wide: the copy
# lands in jdk\ and nothing but run.cmd ever looks at it.
#
# The version, the file name and the sha256 come from dependencies.yaml - one source of truth
# for every pinned download in this project.

param([string]$LogFile = '')

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$pins = Join-Path $root 'dependencies.yaml'
$target = Join-Path $root 'jdk'

if ($LogFile -ne '') {
    try { Start-Transcript -Path $LogFile -Append | Out-Null } catch { }
}

function Pin($key) {
    if (-not (Test-Path $pins)) { throw "dependencies.yaml is missing next to the tool: $pins" }
    foreach ($line in Get-Content $pins) {
        $text = $line.Trim()
        if ($text.StartsWith('#')) { continue }
        $at = $text.IndexOf(': ')
        if ($at -lt 1) { continue }
        if ($text.Substring(0, $at).Trim() -eq $key) { return $text.Substring($at + 2).Trim() }
    }
    throw "dependencies.yaml has no '$key'"
}

try {
    if (Test-Path (Join-Path $target 'bin\java.exe')) {
        Write-Host "Java is already in $target"
        exit 0
    }

    $version = Pin 'java.version'
    $asset = Pin 'java.windows.asset'
    $want = (Pin 'java.windows.sha256').ToLower()
    $url = (Pin 'java.url').Replace('{asset}', $asset)

    $zip = Join-Path $env:TEMP $asset
    Write-Host "downloading Java $version (about 47 MB)"
    Write-Host "  $url"
    $progress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'   # the bar redraws per byte over a slow link
    try {
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    } finally {
        $ProgressPreference = $progress
    }

    # verified BEFORE it is unpacked, the way the jar verifies sockseek: a truncated or
    # tampered download must never become the Java this tool runs
    $got = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLower()
    if ($got -ne $want) {
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        throw "sha256 mismatch for $asset. expected $want, got $got"
    }
    Write-Host "sha256 ok (Java $version)"

    # unpacked beside the target and renamed, so an interrupted unpack leaves no half a Java
    $staging = Join-Path $root 'jdk.part'
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $staging -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue

    # the zip holds one top folder, jdk-21.0.12.1+1-jre; jdk\bin\java.exe is what run.cmd wants
    $inner = Get-ChildItem -Path $staging -Directory | Select-Object -First 1
    if (-not $inner -or -not (Test-Path (Join-Path $inner.FullName 'bin\java.exe'))) {
        Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
        throw "the archive did not hold bin\java.exe where it was expected"
    }
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Move-Item -Path $inner.FullName -Destination $target
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue

    & (Join-Path $target 'bin\java.exe') -version
    Write-Host "installed Java $version -> $target"
    exit 0
} catch {
    Write-Host "ERROR $($_.Exception.Message)"
    exit 1
} finally {
    if ($LogFile -ne '') { try { Stop-Transcript | Out-Null } catch { } }
}
