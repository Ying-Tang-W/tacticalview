# Dump Minecraft sources + vanilla shaders needed for writing a custom shader pipeline.
# Run from the project root:
#     powershell -ExecutionPolicy Bypass -File tools\dump-mc-reference.ps1
# It creates reference-dump.zip - send that file to Claude.
#
# NOTE: this is Mojang code. Do NOT commit it to a public repository
# (reference-dump/ and reference-dump.zip are already in .gitignore).
#
# This file is intentionally ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$out = Join-Path $PWD 'reference-dump'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

$roots = @(
    (Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom'),
    (Join-Path $PWD '.gradle')
) | Where-Object { Test-Path $_ }

$jars = @()
foreach ($r in $roots) {
    $jars += Get-ChildItem $r -Recurse -Filter *.jar -ErrorAction SilentlyContinue
}
# jars with "sources" in the name first, so source files win over anything else
$jars = $jars | Sort-Object { if ($_.Name -match 'sources') { 0 } else { 1 } }

Write-Host ("Scanning {0} jar files ({1} of them are sources jars)" -f @($jars).Count, @($jars | Where-Object { $_.Name -match 'sources' }).Count)

if (-not $jars) {
    Write-Host 'No jar found. Sync Gradle in IDEA first, then run:  .\gradlew.bat genSources' -ForegroundColor Yellow
    exit 1
}

$wantedJava = @(
    'RenderPipelines.java', 'BindGroupLayouts.java', 'DefaultVertexFormat.java',
    'RenderPass.java', 'CommandEncoder.java', 'GpuDevice.java',
    'GpuTexture.java', 'GpuTextureView.java', 'GpuSampler.java',
    'BindGroupLayout.java', 'BindGroup.java', 'RenderPipeline.java'
)

$found = @{}
foreach ($jar in $jars) {
    try { $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName) } catch { continue }
    try {
        foreach ($e in $zip.Entries) {
            $name = $e.FullName
            $leaf = [System.IO.Path]::GetFileName($name)
            if (-not $leaf) { continue }
            $take = $false
            if ($name -match '^(net/minecraft/client/renderer|com/mojang/renderpearl|com/mojang/blaze3d)/.*\.java$' -and ($wantedJava -contains $leaf)) {
                $take = $true
            } elseif ($name -match '^assets/minecraft/shaders/(core/(entity|position_tex)\.|include/)') {
                $take = $true
            }
            if ($take -and -not $found.ContainsKey($name)) {
                $dest = Join-Path $out $name
                New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
                $found[$name] = $jar.Name
            }
        }
    } finally {
        $zip.Dispose()
    }
}

$javaCount = @($found.Keys | Where-Object { $_ -like '*.java' }).Count
$shaderCount = @($found.Keys | Where-Object { $_ -like 'assets/*' }).Count
Write-Host ("Exported: {0} Java source files, {1} shader files" -f $javaCount, $shaderCount) -ForegroundColor Green
foreach ($k in ($found.Keys | Sort-Object)) { Write-Host ("  " + $k) }

if ($javaCount -eq 0) {
    Write-Host 'No Java sources found. Run  .\gradlew.bat genSources  (takes a few minutes), then run this script again.' -ForegroundColor Yellow
}
if ($shaderCount -eq 0) {
    Write-Host 'No vanilla shaders found.' -ForegroundColor Yellow
}

$zipPath = Join-Path $PWD 'reference-dump.zip'
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path (Join-Path $out '*') -DestinationPath $zipPath
Write-Host ("Created " + $zipPath + " - send it to Claude.") -ForegroundColor Green
