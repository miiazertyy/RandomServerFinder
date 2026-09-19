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

# The versions live in versions.json, which the GitHub workflow reads too, so a version added for
# one is built by both. Each entry is minecraft, yarn, fabric_api, loader.
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
$targets = [ordered]@{}
foreach ($entry in (Get-Content (Join-Path $root 'versions.json') -Raw | ConvertFrom-Json)) {
    $targets[$entry.minecraft] = @($entry.yarn, $entry.fabric_api, $entry.loader)
}

# Only build the versions named on the command line, if any. Compared as text: unquoted, PowerShell
# passes 26.2 as a number, which matched nothing and skipped that version without a word.
if ($args.Count -gt 0) {
    $filtered = [ordered]@{}
    foreach ($a in $args) {
        $name = [string]$a
        if ($targets.Contains($name)) { $filtered[$name] = $targets[$name] }
        else { Write-Host "unknown version $name, see versions.json" }
    }
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
