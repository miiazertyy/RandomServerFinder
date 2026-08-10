# Builds the mod against several Minecraft versions and collects the jars in dist/.
#
# The source only compiles where the client APIs it uses exist, so versions that fail are reported
# rather than silently skipped. gradle.properties is restored afterwards either way.

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$props = Join-Path $root 'gradle.properties'
$dist = Join-Path $root 'dist'
$backup = Join-Path $root 'gradle.properties.bak'

# version = @(yarn, fabric-api, loader)
#
# 26.x is unobfuscated and needs no mappings, so its yarn entry is unused — build.gradle picks the
# non-remapping Loom plugin and the src/mc26 sources from the version number alone.
#
# 1.21.9 is the floor for the Yarn line. Below it the screen input API is different: 1.21.9 replaced
# mouseClicked(double, double, int) with mouseClicked(Click, boolean), and the same for dragging,
# releasing and key presses. Those are overrides, so their signatures have to match at compile time;
# no amount of reflection bridges them. Supporting 1.21.8 and older needs a third source variant.
$targets = [ordered]@{
    '26.2'    = @('unused', '0.156.0+26.2', '0.19.3')
    '1.21.11' = @('1.21.11+build.6', '0.141.6+1.21.11', '0.19.3')
    '1.21.10' = @('1.21.10+build.3', '0.138.4+1.21.10', '0.19.3')
    '1.21.9'  = @('1.21.9+build.1', '0.134.1+1.21.9', '0.19.3')
    '1.20.6'  = @('1.20.6+build.3', '0.100.8+1.20.6', '0.15.11')
}

# Only build the versions named on the command line, if any.
if ($args.Count -gt 0) {
    $filtered = [ordered]@{}
    foreach ($a in $args) { if ($targets.Contains($a)) { $filtered[$a] = $targets[$a] } }
    $targets = $filtered
}

New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $props $backup -Force

$succeeded = @()
$failed = @()

try {
    foreach ($version in $targets.Keys) {
        $yarn, $api, $loader = $targets[$version]
        Write-Host "=== building for Minecraft $version ==="

        $text = Get-Content $backup -Raw
        $text = $text -replace 'minecraft_version=.*', "minecraft_version=$version"
        $text = $text -replace 'yarn_mappings=.*', "yarn_mappings=$yarn"
        $text = $text -replace 'loader_version=.*', "loader_version=$loader"
        $text = $text -replace 'fabric_version=.*', "fabric_version=$api"
        [IO.File]::WriteAllText($props, $text)

        & (Join-Path $root 'gradlew.bat') build --quiet -p $root 2>&1 | Out-Null

        $jar = Join-Path $root "build\libs\randomserverfinder-1.0.0.jar"
        if ($LASTEXITCODE -eq 0 -and (Test-Path $jar)) {
            Copy-Item $jar (Join-Path $dist "randomserverfinder-1.0.0-mc$version.jar") -Force
            Write-Host "    OK"
            $succeeded += $version
        }
        else {
            Write-Host "    FAILED (source is not compatible with $version)"
            $failed += $version
        }
    }
}
finally {
    Copy-Item $backup $props -Force
    Remove-Item $backup -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "built:  $($succeeded -join ', ')"
Write-Host "failed: $($failed -join ', ')"
Write-Host "jars in: $dist"
