# Builds the mod against several Minecraft versions and collects the jars in dist/.
#
# The source only compiles where the client APIs it uses exist, so versions that fail are reported
# rather than silently skipped. gradle.properties is restored afterwards either way.

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$props = Join-Path $root 'gradle.properties'
$dist = Join-Path $root 'dist'

# Read rather than hardcode: the old literal kept matching a stale jar left in build\libs from an
# earlier version, so a build could "succeed" while shipping the previous release's code.
$modVersion = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$backup = Join-Path $root 'gradle.properties.bak'

# version = @(yarn, fabric-api, loader)
#
# 26.x is unobfuscated and needs no mappings, so its yarn entry is unused — build.gradle picks the
# non-remapping Loom plugin and the src/mc26 sources from the version number alone.
#
# Four source trees cover this list, picked by build.gradle from the version number alone:
#
#   src/mc26   26.x, unobfuscated
#   src/mc121  1.21.9 and later, where mouseClicked takes a Click rather than three doubles
#   src/mc120  1.20.2 through 1.21.8
#   src/mc1201 1.20.1 only, which predates ServerInfo.Status, drawGuiTexture, the four-argument
#              mouseScrolled and the multiplayer screen packages
#
# Those differences are overrides and enum constants, so their shapes have to match at compile time;
# no amount of reflection bridges them, which is why the trees exist at all.
$targets = [ordered]@{
    '26.2'    = @('unused', '0.156.0+26.2', '0.19.3')
    '26.1.2'  = @('unused', '0.155.2+26.1.2', '0.19.3')
    '1.21.11' = @('1.21.11+build.6', '0.141.6+1.21.11', '0.19.3')
    '1.21.1'  = @('1.21.1+build.3', '0.116.15+1.21.1', '0.16.14')
    '1.20.6'  = @('1.20.6+build.3', '0.100.8+1.20.6', '0.15.11')
    '1.20.1'  = @('1.20.1+build.10', '0.92.11+1.20.1', '0.15.11')
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

        Remove-Item (Join-Path $root 'build\libs\*.jar') -Force -ErrorAction SilentlyContinue
        & (Join-Path $root 'gradlew.bat') build --quiet -p $root 2>&1 | Out-Null

        $jar = Join-Path $root "build\libs\randomserverfinder-$modVersion.jar"
        if ($LASTEXITCODE -eq 0 -and (Test-Path $jar)) {
            Copy-Item $jar (Join-Path $dist "randomserverfinder-$modVersion-mc$version.jar") -Force
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
