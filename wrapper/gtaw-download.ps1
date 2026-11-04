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
# fail closed: never download or run a jar whose checksum is not pinned
if (-not $sha -or -not $url) {
    Write-Output '{"error":{"code":"CONFIG_NOT_INITIALIZED","category":"config","message":"distributionUrl and distributionSha256Sum must be set in .gta/wrapper/gta-wrapper.properties","hint":"set both to the Nexus gta-cli jar and its sha256 (or GTA_CLI_JAR to a local jar for development)","retryable":false}'
    exit 0
}
$gtaHome = if ($env:GTA_HOME) { $env:GTA_HOME } else { Join-Path $env:USERPROFILE '.gta' }
$cache = Join-Path $gtaHome 'wrapper\dists'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$jar = Join-Path $cache "gta-cli-$version.jar"
if (-not (Test-Path $jar)) {
    $tmp = "$jar.$PID.part"  # one file per process: parallel first runs do not collide
    $headers = @{}
    if ($env:GTA_NEXUS_USER) {
        $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($env:GTA_NEXUS_USER):$($env:GTA_NEXUS_PASSWORD)"))
        $headers['Authorization'] = "Basic $pair"
    }
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp -Headers $headers
    } catch {
        Remove-Item -Force -ErrorAction SilentlyContinue $tmp
        Write-Output '{"error":{"code":"ENV_TOOL_UNAVAILABLE","category":"environment","message":"cannot download the gta-cli jar from distributionUrl","hint":"check distributionUrl in .gta/wrapper/gta-wrapper.properties, the network and GTA_NEXUS_USER/GTA_NEXUS_PASSWORD","retryable":true}}'
        exit 0
    }
    Move-Item -Force $tmp $jar
}
$actual = (Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower()
if ($actual -ne $sha.ToLower()) {
    Remove-Item -Force $jar
    Write-Output '{"error":{"code":"ENV_WRAPPER_CHECKSUM_MISMATCH","category":"environment","message":"gta-cli jar checksum mismatch","hint":"check .gta/wrapper/gta-wrapper.properties","retryable":false}}'
    exit 0
}
Write-Output $jar
