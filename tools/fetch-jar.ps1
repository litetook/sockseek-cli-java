# Downloads the latest sockseek-cli.jar from this project's GitHub releases.
#
# For the one case run.cmd cannot cover on its own: somebody has only run.cmd in a folder.
# The jar cannot be pinned by sha256 the way Java and sockseek are - the pin would have to
# live in the thing being pinned - so this is the same trust as downloading the release by
# hand, over HTTPS, from the repository the launcher came from.

param([string]$LogFile = '')

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$target = Join-Path $root 'sockseek-cli.jar'
$api = 'https://api.github.com/repos/litetook/sockseek-cli-java/releases/latest'

if ($LogFile -ne '') {
    try { Start-Transcript -Path $LogFile -Append | Out-Null } catch { }
}

try {
    Write-Host "looking up the latest release"
    $progress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        $release = Invoke-RestMethod -Uri $api -UseBasicParsing -Headers @{
            'User-Agent' = 'sockseek-cli-launcher'
        }
        $asset = $release.assets | Where-Object { $_.name -eq 'sockseek-cli.jar' } |
            Select-Object -First 1
        if (-not $asset) { throw "the latest release carries no sockseek-cli.jar" }
        Write-Host "downloading $($release.tag_name) sockseek-cli.jar ($([math]::Round($asset.size / 1MB, 1)) MB)"
        Write-Host "  $($asset.browser_download_url)"
        $part = "$target.part"
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $part -UseBasicParsing
        Move-Item -Path $part -Destination $target -Force
    } finally {
        $ProgressPreference = $progress
    }
    Write-Host "installed sockseek-cli.jar -> $target"
    exit 0
} catch {
    Write-Host "ERROR $($_.Exception.Message)"
    exit 1
} finally {
    if ($LogFile -ne '') { try { Stop-Transcript | Out-Null } catch { } }
}
