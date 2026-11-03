# gtaw-download.ps1 - used by gtaw.cmd on Windows. Prints the path of a verified gta-cli jar (or a JSON error).
# Managed by gta: do not edit.
param([Parameter(Mandatory = $true)][string]$Root)
$ErrorActionPreference = 'Stop'
$props = @{}
Get-Content (Join-Path $Root '.gta\wrapper\gta-wrapper.properties') | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$matches[1].Trim()] = $matches[2].Trim() }
}
$version = $props['version']
$url = $props['distributionUrl']
$sha = $props['distributionSha256Sum']
$gtaHome = if ($env:GTA_HOME) { $env:GTA_HOME } else { Join-Path $env:USERPROFILE '.gta' }
$cache = Join-Path $gtaHome 'wrapper\dists'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$jar = Join-Path $cache "gta-cli-$version.jar"
if (-not (Test-Path $jar)) {
    $tmp = "$jar.part"
    $headers = @{}
    if ($env:GTA_NEXUS_USER) {
        $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($env:GTA_NEXUS_USER):$($env:GTA_NEXUS_PASSWORD)"))
        $headers['Authorization'] = "Basic $pair"
    }
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp -Headers $headers
    Move-Item -Force $tmp $jar
}
if ($sha) {
    $actual = (Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower()
    if ($actual -ne $sha.ToLower()) {
        Remove-Item -Force $jar
        Write-Output '{"error":{"code":"ENV_WRAPPER_CHECKSUM_MISMATCH","category":"environment","message":"gta-cli jar checksum mismatch","hint":"check .gta/wrapper/gta-wrapper.properties","retryable":false}}'
        exit 0
    }
}
Write-Output $jar
