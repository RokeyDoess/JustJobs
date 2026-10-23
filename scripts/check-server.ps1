param(
    [Parameter(Mandatory)][string]$PaperJar,
    [Parameter(Mandatory)][string]$VaultJar,
    [Parameter(Mandatory)][string]$DependencyCache,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PaperRuntime,
    [string]$UpgradeFromTag,
    [string]$JobsConfig,
    [ValidateRange(10,3600)][int]$LoadSeconds = 10,
    [string]$EconomyJar,
    [ValidatePattern('^[A-Za-z]+$')][string]$CheckClass = 'ReleaseChecks',
    [ValidateRange(1024,65535)][int]$ServerPort = 25577
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$paperSource = (Resolve-Path -LiteralPath $PaperJar).Path
$vaultSource = (Resolve-Path -LiteralPath $VaultJar).Path
$dependencySource = (Resolve-Path -LiteralPath $DependencyCache).Path
$javaBin = Join-Path $JavaHome 'bin'
$build = Join-Path $projectRoot 'target'
[xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml')
$candidate = Get-Item -LiteralPath (Join-Path $build ('JustJobs-' + $pom.project.version + '.jar')) -ErrorAction SilentlyContinue
if (!$candidate) { throw 'Build the current plugin version first with mvnw verify.' }

# Each run gets a new folder. No server files are edited or removed at the source paths.
$runRoot = Join-Path $build ('server-check-' + [guid]::NewGuid().ToString('N'))
$plugins = Join-Path $runRoot 'plugins'
$classes = Join-Path $runRoot 'check-classes'
New-Item -ItemType Directory -Path $plugins,$classes | Out-Null
Copy-Item -LiteralPath $paperSource -Destination (Join-Path $runRoot 'paper.jar')
Copy-Item -LiteralPath $vaultSource -Destination $plugins
Copy-Item -LiteralPath $candidate.FullName -Destination $plugins
if ($EconomyJar) { Copy-Item -LiteralPath (Resolve-Path -LiteralPath $EconomyJar).Path -Destination $plugins }
if ($UpgradeFromTag) {
    $settingsFolder = Join-Path $plugins 'JustJobs'
    New-Item -ItemType Directory -Path $settingsFolder | Out-Null
    foreach ($file in @('config.yml','jobs.yml')) {
        $sourceText = & git -C $projectRoot show ($UpgradeFromTag + ':src/main/resources/' + $file)
        if ($LASTEXITCODE -ne 0) { throw "Could not read $file from $UpgradeFromTag" }
        $sourceText | Set-Content -LiteralPath (Join-Path $settingsFolder $file) -Encoding utf8
    }
}
if ($JobsConfig) {
    $jobsSource = (Resolve-Path -LiteralPath $JobsConfig).Path
    $settingsFolder = Join-Path $plugins 'JustJobs'
    New-Item -ItemType Directory -Path $settingsFolder -Force | Out-Null
    Copy-Item -LiteralPath $jobsSource -Destination (Join-Path $settingsFolder 'jobs.yml')
}
if ($PaperRuntime) {
    $runtimeSource = (Resolve-Path -LiteralPath $PaperRuntime).Path
    foreach ($folder in @('cache','libraries','versions')) {
        $source = Join-Path $runtimeSource $folder
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $runRoot -Recurse }
    }
}
'eula=true' | Set-Content -LiteralPath (Join-Path $runRoot 'eula.txt')
@'
server-ip=127.0.0.1
server-port=25577
online-mode=false
white-list=true
enforce-secure-profile=false
level-name=checks
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
max-players=2
pause-when-empty-seconds=-1
spawn-protection=0
enable-rcon=false
'@ | Set-Content -LiteralPath (Join-Path $runRoot 'server.properties')
$propertiesPath = Join-Path $runRoot 'server.properties'
[IO.File]::WriteAllText($propertiesPath, [IO.File]::ReadAllText($propertiesPath).Replace('server-port=25577', "server-port=$ServerPort"))
$classpath = @($candidate.FullName) + @(Get-ChildItem -LiteralPath $dependencySource -Filter '*.jar' -Recurse | ForEach-Object FullName)
$checkSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test/server') -Filter '*Checks.java' | ForEach-Object FullName)
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -cp ($classpath -join [IO.Path]::PathSeparator) -d $classes @checkSources
if ($LASTEXITCODE -ne 0) { throw 'Server check compilation failed.' }
$checkDescription = [IO.File]::ReadAllText((Join-Path $projectRoot 'src/test/server/plugin.yml')).Replace('checks.ReleaseChecks', 'checks.' + $CheckClass)
[IO.File]::WriteAllText((Join-Path $classes 'plugin.yml'), $checkDescription)
& (Join-Path $javaBin 'jar.exe') --create --file (Join-Path $plugins 'JustJobsChecks.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Server check packaging failed.' }
Write-Output "Isolated server: $runRoot"
for ($phase = 1; $phase -le 2; $phase++) {
    $logPath = Join-Path $runRoot "phase-$phase.log"
    Push-Location -LiteralPath $runRoot
    try {
        & (Join-Path $javaBin 'java.exe') '-Dterminal.jline=false' '-Dterminal.ansi=false' ("-Djustjobs.check.load-ticks=" + ($LoadSeconds * 20)) -Xms512M -Xmx2G -jar paper.jar --nogui 2>&1 | Tee-Object -FilePath $logPath
        if ($LASTEXITCODE -ne 0) { throw "Server phase $phase exited with an error." }
    } finally { Pop-Location }
    if (!(Select-String -LiteralPath $logPath -Pattern "RELEASE CHECKS PHASE $phase PASS:" -Quiet)) { throw "Server checks failed in phase $phase. See $logPath" }
}
Write-Output 'Both isolated server phases passed.'
