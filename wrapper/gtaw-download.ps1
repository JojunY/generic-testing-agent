# gtaw-download.ps1 - used by gtaw.cmd on Windows. Prints the path of a verified gta-cli jar (or a JSON error).
# Managed by gta: do not edit.
param([Parameter(Mandatory = $true)][string]$Root)
$ErrorActionPreference = 'Stop'
$props = @{}
$propsFile = Join-Path $Root '.gta\wrapper\gta-wrapper.properties'
if (-not (Test-Path $propsFile)) {
    Write-Output '{"error":{"code":"CONFIG_NOT_INITIALIZED","category":"config","message":"missing .gta/wrapper/gta-wrapper.properties","hint":"run gta-cli init --platform copilot or opencode","retryable":false}}'
    exit 0
}
Get-Content $propsFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$matches[1].Trim()] = $matches[2].Trim() }
}
$version = $props['version']
$url = $props['distributionUrl']
$sha = $props['distributionSha256Sum']
# the same contract as gtaw: no version line means the wrapper was never set up
if (-not $version) {
    Write-Output '{"error":{"code":"CONFIG_NOT_INITIALIZED","category":"config","message":"missing .gta/wrapper/gta-wrapper.properties","hint":"run gta-cli init --platform copilot or opencode","retryable":false}}'
    exit 0
}
# fail closed: never download or run a jar whose checksum is not pinned
if (-not $sha -or -not $url) {
    Write-Output '{"error":{"code":"CONFIG_NOT_INITIALIZED","category":"config","message":"distributionUrl and distributionSha256Sum must be set in .gta/wrapper/gta-wrapper.properties","hint":"set both to the Nexus gta-cli jar and its sha256 (or GTA_CLI_JAR to a local jar for development)","retryable":false}}'
    exit 0
}
$gtaHome = if ($env:GTA_HOME) { $env:GTA_HOME } else { Join-Path $env:USERPROFILE '.gta' }
$cache = Join-Path $gtaHome 'wrapper\dists'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$jar = Join-Path $cache "gta-cli-$version-$($sha.ToLower()).jar"
if (-not (Test-Path $jar)) {
    $tmp = "$jar.$PID.part"  # one file per process: parallel first runs do not collide
    $headers = @{}
    if ($env:GTA_NEXUS_USER) {
        $pair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($env:GTA_NEXUS_USER):$($env:GTA_NEXUS_PASSWORD)"))
        $headers['Authorization'] = "Basic $pair"
    }
    # Windows PowerShell 5.1 may offer only TLS 1.0/1.1, which a current Nexus refuses
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp -Headers $headers
    } catch {
        Remove-Item -Force -ErrorAction SilentlyContinue $tmp
        Write-Output '{"error":{"code":"ENV_TOOL_UNAVAILABLE","category":"environment","message":"cannot download the gta-cli jar from distributionUrl","hint":"check distributionUrl in .gta/wrapper/gta-wrapper.properties, the network and GTA_NEXUS_USER/GTA_NEXUS_PASSWORD","retryable":true}}'
        exit 0
    }
    try {
        Move-Item -Force $tmp $jar -ErrorAction Stop
    } catch {
        # a parallel first run placed the jar first and its java holds it open: use that one (checked below)
        Remove-Item -Force -ErrorAction SilentlyContinue $tmp
        if (-not (Test-Path $jar)) {
            Write-Output '{"error":{"code":"ENV_TOOL_UNAVAILABLE","category":"environment","message":"cannot place the downloaded gta-cli jar in the cache","hint":"check the permissions of the gtaw cache directory and run again","retryable":true}}'
            exit 0
        }
    }
}
$actual = (Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower()
if ($actual -ne $sha.ToLower()) {
    Remove-Item -Force $jar
    Write-Output '{"error":{"code":"ENV_WRAPPER_CHECKSUM_MISMATCH","category":"environment","message":"gta-cli jar checksum mismatch","hint":"check .gta/wrapper/gta-wrapper.properties","retryable":false}}'
    exit 0
}
Write-Output $jar
