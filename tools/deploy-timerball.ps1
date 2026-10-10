# TimerBall（计时子球插件：正计时 + 倒计时合并版）一键部署脚本
# 用法（工作区根目录）： powershell -ExecutionPolicy Bypass -File tools\deploy-timerball.ps1
#
# 行为：① 编译插件（宿主 src/ 除菜单可读性小改外零改动，仅以宿主 jar 作 API 类路径）
#       ② 打包 plugins 运行所需的 TimerBall.jar（Manifest 声明 Api-Level-Min: 2）
#       ③ 复制到运行目录 plugins\，并移除被合并取代的旧插件 jar（CountUpTimerBall / CountdownTimer）
#       ④ 跑自检 harness（退出码非 0 即中止并报错）

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$pluginDir = Join-Path $root 'examples\plugin-timerball'
$hostJar = Join-Path $root 'RandomNamePicker.jar'
$pluginsOut = Join-Path $root 'plugins'
$buildDir = Join-Path $pluginDir 'build'
$outJar = Join-Path $pluginDir 'TimerBall.jar'

Write-Host "== TimerBall（计时子球·合并版）一键部署 ==" -ForegroundColor Cyan
Write-Host "工作区根 : $root"
Write-Host "插件工程 : $pluginDir"

if (-not (Test-Path $hostJar)) {
    throw "找不到宿主 jar（作 API 类路径用）：$hostJar"
}

# --- 1) 编译 ---
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sources = @(
    'TimerBallPlugin.java',
    'UpTracker.java',
    'UpBall.java',
    'UpBallConfig.java',
    'UpSettingsDialog.java',
    'DownController.java',
    'DownBall.java',
    'DownFocus.java',
    'DownSession.java',
    'DownSettingsDialog.java',
    'TimerBallConfig.java',
    'TimerAlert.java',
    'BallLayout.java',
    'TimerControlPanel.java',
    'TimerIcons.java'
)

Push-Location $pluginDir
try {
    Write-Host "`n[1/4] 编译插件源码（$($sources.Count) 个文件）..." -ForegroundColor Yellow
    & javac -encoding UTF-8 -cp $hostJar -d build @sources
    if ($LASTEXITCODE -ne 0) { throw "插件编译失败（退出码 $LASTEXITCODE）" }

    Write-Host "[2/4] 打包 TimerBall.jar ..." -ForegroundColor Yellow
    if (Test-Path $outJar) { Remove-Item -Force $outJar }
    $classes = Get-ChildItem -Path build -Recurse -Filter '*.class' |
        Where-Object { $_.Name -notlike 'TimerBallSelfTest*' } |
        ForEach-Object { $_.FullName.Substring($buildDir.Length + 1) }
    $jarArgs = @('cfm', $outJar, 'TimerBall.manifest')
    foreach ($c in $classes) { $jarArgs += @('-C', 'build', $c) }
    & jar @jarArgs
    if ($LASTEXITCODE -ne 0) { throw "打包失败（退出码 $LASTEXITCODE）" }

    # 打包禁令自检：jar 内不得含 com/randomnamepicker/**
    $bad = (& jar tf $outJar) | Where-Object { $_ -like 'com/randomnamepicker/*' }
    if ($bad) { throw "jar 内含宿主类，违反打包禁令：$($bad -join ', ')" }

    Write-Host "[3/4] 复制到运行目录 plugins\ 并清理被合并取代的旧 jar ..." -ForegroundColor Yellow
    if (-not (Test-Path $pluginsOut)) { New-Item -ItemType Directory -Force -Path $pluginsOut | Out-Null }
    Copy-Item -Force $outJar (Join-Path $pluginsOut 'TimerBall.jar')
    Write-Host "      -> $(Join-Path $pluginsOut 'TimerBall.jar')"
    foreach ($old in @('CountUpTimerBall.jar', 'CountdownTimer.jar')) {
        $p = Join-Path $pluginsOut $old
        if (Test-Path $p) {
            Remove-Item $p -Force
            Write-Host "      -> 已移除旧插件 $old（功能已并入 TimerBall.jar）"
        }
    }

    Write-Host "[4/4] 运行自检 harness ..." -ForegroundColor Yellow
    & javac -encoding UTF-8 -cp "build;$hostJar" -d build TimerBallSelfTest.java
    if ($LASTEXITCODE -ne 0) { throw "自检编译失败（退出码 $LASTEXITCODE）" }
    & java "-Dfile.encoding=UTF-8" -cp "$hostJar;$buildDir" TimerBallSelfTest
    $testExit = $LASTEXITCODE
    if ($testExit -ne 0) { throw "自检未通过（退出码 $testExit）" }
}
finally {
    Pop-Location
}

Write-Host "`n== 部署完成 ==" -ForegroundColor Green
Write-Host "请重启 RandomNamePicker（宿主无热插拔）；启动后看 log\Modifylog.txt 的 PLUGIN_LOADED 与 TB_* 记录。"
Write-Host "设置入口：主窗 设置 → 插件区 → 「计时子球（正计时）」/「计时子球（倒计时）」。"
Write-Host "悬浮球右键：「计时子球 ▸ …」级联子菜单（控制面板/正计时/倒计时/快捷时长/设置/自检）。"
