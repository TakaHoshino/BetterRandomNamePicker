# CountdownTimer（倒计时插件）一键部署脚本
# 用法（工作区根目录）： powershell -ExecutionPolicy Bypass -File tools\deploy-countdown.ps1
#
# 行为：① 编译插件（宿主 src/ 零改动，仅以宿主 jar 作 API 类路径）
#       ② 打包 plugins 运行所需的 CountdownTimer.jar（Manifest 声明 Api-Level-Min: 2）
#       ③ 复制到运行目录 plugins\（宿主重启后加载）
#       ④ 跑自检 harness（退出码非 0 即中止并报错）

$ErrorActionPreference = 'Stop'

# --- 项目根 = 本脚本的上一级 ---
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$pluginDir = Join-Path $root 'examples\plugin-countdown'
$hostJar = Join-Path $root 'RandomNamePicker.jar'
$pluginsOut = Join-Path $root 'plugins'
$buildDir = Join-Path $pluginDir 'build'
$outJar = Join-Path $pluginDir 'CountdownTimer.jar'

Write-Host "== CountdownTimer 一键部署 ==" -ForegroundColor Cyan
Write-Host "工作区根 : $root"
Write-Host "插件工程 : $pluginDir"

if (-not (Test-Path $hostJar)) {
    throw "找不到宿主 jar（作 API 类路径用）：$hostJar"
}

# --- 1) 编译 ---
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sources = @(
    'CountdownPlugin.java',
    'CountdownController.java',
    'CountdownSession.java',
    'CountdownBall.java',
    'CountdownFocus.java',
    'CountdownConfig.java',
    'CountdownAlert.java',
    'CountdownSettingsDialog.java'
)

Push-Location $pluginDir
try {
    Write-Host "`n[1/4] 编译插件源码 ..." -ForegroundColor Yellow
    & javac -encoding UTF-8 -cp $hostJar -d build @sources
    if ($LASTEXITCODE -ne 0) { throw "插件编译失败（退出码 $LASTEXITCODE）" }

    Write-Host "[2/4] 打包 CountdownTimer.jar ..." -ForegroundColor Yellow
    if (Test-Path $outJar) { Remove-Item -Force $outJar }
    # 只打入插件运行所需类（不含 SelfTest）
    $classes = Get-ChildItem -Path build -Recurse -Filter '*.class' |
        Where-Object { $_.Name -notlike 'CountdownSelfTest*' } |
        ForEach-Object { $_.FullName.Substring($buildDir.Length + 1) }
    $jarArgs = @('cfm', $outJar, 'Countdown.manifest')
    foreach ($c in $classes) { $jarArgs += @('-C', 'build', $c) }
    & jar @jarArgs
    if ($LASTEXITCODE -ne 0) { throw "打包失败（退出码 $LASTEXITCODE）" }

    # 打包禁令自检：jar 内不得含 com/randomnamepicker/**
    $entries = & jar tf $outJar
    $bad = $entries | Where-Object { $_ -like 'com/randomnamepicker/*' }
    if ($bad) { throw "jar 内含宿主类，违反打包禁令：$($bad -join ', ')" }

    Write-Host "[3/4] 复制到运行目录 plugins\ ..." -ForegroundColor Yellow
    if (-not (Test-Path $pluginsOut)) { New-Item -ItemType Directory -Force -Path $pluginsOut | Out-Null }
    Copy-Item -Force $outJar (Join-Path $pluginsOut 'CountdownTimer.jar')
    Write-Host "      -> $(Join-Path $pluginsOut 'CountdownTimer.jar')"

    Write-Host "[4/4] 运行自检 harness ..." -ForegroundColor Yellow
    & javac -encoding UTF-8 -cp "build;$hostJar" -d build CountdownSelfTest.java
    if ($LASTEXITCODE -ne 0) { throw "自检编译失败（退出码 $LASTEXITCODE）" }
    & java -cp "$hostJar;$buildDir" CountdownSelfTest
    $testExit = $LASTEXITCODE
    if ($testExit -ne 0) { throw "自检未通过（退出码 $testExit）" }
}
finally {
    Pop-Location
}

Write-Host "`n== 部署完成 ==" -ForegroundColor Green
Write-Host "请重启 RandomNamePicker（宿主无热插拔）；启动后看 log\Modifylog.txt 的 PLUGIN_LOADED 与 CD_* 记录。"
Write-Host "设置入口：主窗 设置 → 插件区 → 「倒计时插件」按钮。"
