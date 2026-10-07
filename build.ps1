# Gera dist/firebird.metabase-driver.jar
#
# O plugin é distribuído como código-fonte Clojure (sem AOT): o próprio Metabase compila o namespace ao carregar,
# então não é preciso instalar Java/Clojure para gerar o .jar. O Jaybird (driver JDBC) é embutido no mesmo jar.
#
# Uso:  powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = 'Stop'

$JaybirdVersion = '5.0.13.java11'
$JaybirdUrl     = "https://repo1.maven.org/maven2/org/firebirdsql/jdbc/jaybird/$JaybirdVersion/jaybird-$JaybirdVersion.jar"

$Root    = $PSScriptRoot
$LibDir  = Join-Path $Root 'lib'
$DistDir = Join-Path $Root 'dist'
$Jaybird = Join-Path $LibDir "jaybird-$JaybirdVersion.jar"
$OutJar  = Join-Path $DistDir 'firebird.metabase-driver.jar'

New-Item -ItemType Directory -Force $LibDir, $DistDir | Out-Null

if (-not (Test-Path $Jaybird)) {
    Write-Host "Baixando Jaybird $JaybirdVersion..."
    Invoke-WebRequest -Uri $JaybirdUrl -OutFile $Jaybird -UseBasicParsing
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# FileMode.Create sobrescreve o jar de um build anterior
$stream = [System.IO.File]::Open($OutJar, [System.IO.FileMode]::Create)
$out = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    function Add-File([string]$source, [string]$entryName) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($out, $source, $entryName) | Out-Null
    }

    # Manifesto
    $manifest = $out.CreateEntry('META-INF/MANIFEST.MF')
    $writer = New-Object System.IO.StreamWriter($manifest.Open())
    $writer.Write("Manifest-Version: 1.0`r`nCreated-By: build.ps1`r`n`r`n")
    $writer.Close()

    # Manifesto do plugin + código-fonte do driver
    Add-File (Join-Path $Root 'resources\metabase-plugin.yaml') 'metabase-plugin.yaml'
    Add-File (Join-Path $Root 'src\metabase\driver\firebird.clj') 'metabase/driver/firebird.clj'

    # Conteúdo do Jaybird (exceto o manifesto dele)
    $jb = [System.IO.Compression.ZipFile]::OpenRead($Jaybird)
    try {
        foreach ($entry in $jb.Entries) {
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF' -or $entry.FullName.EndsWith('/')) { continue }
            $target = $out.CreateEntry($entry.FullName)
            $src = $entry.Open(); $dst = $target.Open()
            try { $src.CopyTo($dst) } finally { $src.Close(); $dst.Close() }
        }
    } finally { $jb.Dispose() }
} finally {
    $out.Dispose()
    $stream.Dispose()
}

Write-Host "OK: $OutJar ($([math]::Round((Get-Item $OutJar).Length / 1MB, 2)) MB)"
