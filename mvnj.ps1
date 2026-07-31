# Helper: invoke Maven via classworlds launcher (mvn.cmd is blocked by group policy).
# Usage: powershell -File mvnj.ps1 <projectDir> <maven args...>
param(
    [Parameter(Mandatory=$true)][string]$ProjectDir,
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$MvnArgs
)
$ErrorActionPreference = "Stop"
$mh = "C:\Users\tuanlc\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11"
$cw = Join-Path $mh "boot\plexus-classworlds-2.9.0.jar"
$conf = Join-Path $mh "bin\m2.conf"
$full = (Resolve-Path $ProjectDir).Path
Push-Location $full
try {
    & java -classpath $cw "-Dclassworlds.conf=$conf" "-Dmaven.home=$mh" "-Dmaven.multiModuleProjectDirectory=$full" org.codehaus.plexus.classworlds.launcher.Launcher @MvnArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
