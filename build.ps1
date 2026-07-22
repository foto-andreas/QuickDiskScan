$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildDir = Join-Path $ProjectDir "build"
$DistDir = Join-Path $ProjectDir "dist"

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    throw "JAVA_HOME muss auf ein JDK 25 zeigen."
}
$JavaHome = $env:JAVA_HOME
if (-not (& (Join-Path $JavaHome "bin\javac.exe") --version).StartsWith("javac 25")) {
    throw "QuickDiskScan benötigt JDK 25."
}

Remove-Item $BuildDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $DistDir "QuickDiskScan") -Recurse -Force -ErrorAction SilentlyContinue
New-Item (Join-Path $BuildDir "classes\de\schrell\quickdiskscan\native") -ItemType Directory -Force | Out-Null
New-Item (Join-Path $BuildDir "test-classes") -ItemType Directory -Force | Out-Null
New-Item (Join-Path $BuildDir "package") -ItemType Directory -Force | Out-Null
New-Item (Join-Path $BuildDir "javafx") -ItemType Directory -Force | Out-Null
New-Item (Join-Path $BuildDir "native") -ItemType Directory -Force | Out-Null
New-Item $DistDir -ItemType Directory -Force | Out-Null

foreach ($Module in @("javafx-base", "javafx-graphics", "javafx-controls")) {
    $Jar = $null
    if ($env:JAVAFX_HOME) {
        $Jar = Get-ChildItem $env:JAVAFX_HOME -Recurse -Filter "$Module*.jar" |
            Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1
    }
    if (-not $Jar) {
        $Cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.openjfx\$Module\25"
        if (Test-Path $Cache) {
            $Jar = Get-ChildItem $Cache -Recurse -Filter "$Module-25-*.jar" |
                Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1
        }
    }
    if (-not $Jar) { throw "JavaFX-25-Modul fehlt: $Module" }
    Copy-Item $Jar.FullName (Join-Path $BuildDir "javafx")
}

function Import-VisualCppEnvironment {
    $JavaExe = [IO.Path]::Combine($JavaHome, "bin", "java.exe")
    $JavaCommand = '"{0}" -XshowSettings:properties -version 2>&1' -f $JavaExe
    $JavaSettings = & $env:ComSpec /d /s /c $JavaCommand | Out-String
    if ($JavaSettings -notmatch '(?m)^\s*os\.arch\s*=\s*(\S+)\s*$') { return $false }
    $JavaArchitecture = $Matches[1]
    $VcConfiguration = switch ($JavaArchitecture) {
        { $_ -in "amd64", "x86_64" } {
            "amd64", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64"
            break
        }
        { $_ -in "aarch64", "arm64" } {
            "arm64", "Microsoft.VisualStudio.Component.VC.Tools.ARM64"
            break
        }
        default { return $false }
    }
    $VcArchitecture = $VcConfiguration[0]
    $VcComponent = $VcConfiguration[1]

    $ProgramFiles = ${env:ProgramFiles(x86)}
    if (-not $ProgramFiles) { $ProgramFiles = $env:ProgramFiles }
    if (-not $ProgramFiles) { return $false }
    $VsWhere = [IO.Path]::Combine(
        $ProgramFiles, "Microsoft Visual Studio", "Installer", "vswhere.exe")
    if (-not (Test-Path $VsWhere)) { return $false }
    $VsDevCmd = & $VsWhere -latest -prerelease -products '*' -requires $VcComponent `
        -find "Common7\Tools\VsDevCmd.bat" | Select-Object -First 1
    if ($VsDevCmd) { $VsDevCmd = ([string] $VsDevCmd).Trim() }
    if (-not $VsDevCmd -or -not (Test-Path $VsDevCmd)) { return $false }

    $EnvironmentLines = & $env:ComSpec /d /s /c `
        "call `"$VsDevCmd`" -no_logo -arch=$VcArchitecture -host_arch=amd64 >nul && set"
    if ($LASTEXITCODE -ne 0) { return $false }
    foreach ($Line in $EnvironmentLines) {
        if ($Line -match '^([^=]+)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], "Process")
        }
    }
    Write-Host "MSVC-Umgebung geladen: $VsDevCmd ($VcArchitecture)"
    return $true
}

$Compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
if (-not $Compiler -and (Import-VisualCppEnvironment)) {
    $Compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
}
if (-not $Compiler) {
    throw "cl.exe fehlt. Visual Studio oder Build Tools mit C++-Werkzeugen installieren."
}
Push-Location (Join-Path $BuildDir "native")
try {
    & $Compiler.Source /nologo /O2 /LD "/I$JavaHome\include" "/I$JavaHome\include\win32" `
        (Join-Path $ProjectDir "src\main\native\diskmetrics.c") /Fe:quickdiskscanmetrics.dll
    if ($LASTEXITCODE -ne 0) { throw "Native Windows-Hilfe konnte nicht gebaut werden." }
} finally {
    Pop-Location
}
Copy-Item (Join-Path $BuildDir "native\quickdiskscanmetrics.dll") `
    (Join-Path $BuildDir "classes\de\schrell\quickdiskscan\native\quickdiskscanmetrics.dll")

$MainSources = Get-ChildItem (Join-Path $ProjectDir "src\main\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
$TestSources = Get-ChildItem (Join-Path $ProjectDir "src\test\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
$JavaFxPath = Join-Path $BuildDir "javafx"
& (Join-Path $JavaHome "bin\javac.exe") --release 25 -Xlint:all -Werror --module-path $JavaFxPath --add-modules javafx.controls `
    -d (Join-Path $BuildDir "classes") $MainSources
if ($LASTEXITCODE -ne 0) { throw "Java-Kompilierung fehlgeschlagen." }
Copy-Item (Join-Path $ProjectDir "src\main\resources\de\schrell\quickdiskscan\app.css") `
    (Join-Path $BuildDir "classes\de\schrell\quickdiskscan\app.css") -Force
& (Join-Path $JavaHome "bin\javac.exe") --release 25 -Xlint:all -Werror -cp (Join-Path $BuildDir "classes") `
    -d (Join-Path $BuildDir "test-classes") $TestSources
if ($LASTEXITCODE -ne 0) { throw "Test-Kompilierung fehlgeschlagen." }
$TestPath = (Join-Path $BuildDir "classes") + ";" + (Join-Path $BuildDir "test-classes")
$PreferenceOption = "-Djava.util.prefs.userRoot=$(Join-Path $BuildDir 'preferences')"
& (Join-Path $JavaHome "bin\java.exe") --enable-native-access=ALL-UNNAMED -ea $PreferenceOption -cp $TestPath de.schrell.quickdiskscan.DiskScannerTest
if ($LASTEXITCODE -ne 0) { throw "Tests fehlgeschlagen." }
& (Join-Path $JavaHome "bin\java.exe") $PreferenceOption -cp $TestPath de.schrell.quickdiskscan.I18nTest
if ($LASTEXITCODE -ne 0) { throw "I18n-Test fehlgeschlagen." }
& (Join-Path $JavaHome "bin\java.exe") $PreferenceOption -cp $TestPath de.schrell.quickdiskscan.ByteFormatTest
if ($LASTEXITCODE -ne 0) { throw "Zahlenformat-Test fehlgeschlagen." }

$Jar = Join-Path $BuildDir "package\quickdiskscan.jar"
& (Join-Path $JavaHome "bin\jar.exe") --create --file $Jar `
    --main-class de.schrell.quickdiskscan.QuickDiskScanApp -C (Join-Path $BuildDir "classes") .
$ModulePath = $Jar + ";" + $JavaFxPath
if (Test-Path (Join-Path $JavaHome "jmods")) {
    $ModulePath = (Join-Path $JavaHome "jmods") + ";" + $ModulePath
}
& (Join-Path $JavaHome "bin\jpackage.exe") --type app-image --name QuickDiskScan --dest $DistDir `
    --module-path $ModulePath --module de.schrell.quickdiskscan/de.schrell.quickdiskscan.QuickDiskScanApp `
    --java-options "-Dfile.encoding=UTF-8" --java-options "--enable-native-access=javafx.graphics,de.schrell.quickdiskscan" `
    --app-version 1.0.0 --icon (Join-Path $ProjectDir "src\main\packaging\QuickDiskScan.ico")
if ($LASTEXITCODE -ne 0) { throw "Packaging fehlgeschlagen." }
Write-Host "Erstellt: $(Join-Path $DistDir 'QuickDiskScan')"
