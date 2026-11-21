# 从本机 Loom 缓存里导出"自定义着色器管线"需要参考的 Minecraft 源码和原版着色器，打成 reference-dump.zip。
# 用法（在项目根目录的 PowerShell 里）：
#     powershell -ExecutionPolicy Bypass -File tools\dump-mc-reference.ps1
# 然后把生成的 reference-dump.zip 发给 Claude。
#
# 注意：这些是 Mojang 的代码，不要提交到公开仓库（reference-dump/ 和 reference-dump.zip 已加入 .gitignore）。

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$out = Join-Path $PWD 'reference-dump'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

# 在这些目录里找 jar（Loom 的缓存和项目自己的 .gradle）
$roots = @(
    (Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom'),
    (Join-Path $PWD '.gradle')
) | Where-Object { Test-Path $_ }

$jars = @()
foreach ($r in $roots) {
    $jars += Get-ChildItem $r -Recurse -Filter *.jar -ErrorAction SilentlyContinue
}
# 带 sources 的 jar 排在前面，同名文件优先取源码版
$jars = $jars | Sort-Object { if ($_.Name -match 'sources') { 0 } else { 1 } }

if (-not $jars) {
    Write-Host '没有找到任何 jar。请先在 IDEA 里同步过 Gradle，并运行一次：  .\gradlew.bat genSources' -ForegroundColor Yellow
    exit 1
}

# 需要的 Java 源文件（按文件名匹配，只取 Minecraft/renderpearl/blaze3d 包下的）
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

$javaCount = ($found.Keys | Where-Object { $_ -like '*.java' }).Count
$shaderCount = ($found.Keys | Where-Object { $_ -like 'assets/*' }).Count
Write-Host "导出完成：Java 源文件 $javaCount 个，着色器文件 $shaderCount 个" -ForegroundColor Green
$found.Keys | Sort-Object | ForEach-Object { Write-Host "  $_" }

if ($javaCount -eq 0) {
    Write-Host '没有找到 Java 源码（只有反编译前的 class）。请先运行：  .\gradlew.bat genSources  然后重新运行本脚本。' -ForegroundColor Yellow
}
if ($shaderCount -eq 0) {
    Write-Host '没有找到原版着色器。' -ForegroundColor Yellow
}

$zipPath = Join-Path $PWD 'reference-dump.zip'
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path (Join-Path $out '*') -DestinationPath $zipPath
Write-Host "已生成 $zipPath ，请把它发给 Claude。" -ForegroundColor Green
