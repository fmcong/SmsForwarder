@echo off
REM ============================================
REM FrpcLib v0.70.0 一键构建脚本
REM ============================================
REM 前提：需要安装 Go 1.22+ 和 Android Studio（含 NDK）
REM
REM 使用方式：双击运行本脚本，或在终端执行
REM ============================================
setlocal EnableDelayedExpansion

REM 检测 Go
where go >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 未找到 Go，请先安装：https://go.dev/dl/
    exit /b 1
)
go version

REM 检测 gomobile
where gomobile >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [信息] 正在安装 gomobile...
    go install golang.org/x/mobile/cmd/gomobile@latest
    if !ERRORLEVEL! NEQ 0 (
        echo [错误] gomobile 安装失败
        exit /b 1
    )
)

REM 检测 ANDROID_HOME
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
    ) else (
        echo [错误] 未找到 Android SDK，请设置 ANDROID_HOME 环境变量
        exit /b 1
    )
)
echo ANDROID_HOME=!ANDROID_HOME!

REM 初始化 gomobile（下载 NDK）
echo [信息] 正在初始化 gomobile...
call gomobile init
if %ERRORLEVEL% NEQ 0 (
    echo [错误] gomobile init 失败
    exit /b 1
)

REM 添加移动端依赖
cd /d %~dp0frpc_wrapper
echo [信息] 正在添加移动端依赖...
go get golang.org/x/mobile@latest
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 依赖添加失败
    exit /b 1
)

REM 编译
echo [信息] 正在编译 frpclib.aar...
call gomobile bind -v -target android -androidapi 21 -o %~dp0app\libs\frpclib.aar frpclibwrapper
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 编译失败
    exit /b 1
)

echo [成功] frpclib.aar 已生成到 app/libs/frpclib.aar
echo [成功] 版本: v0.70.0 (基于 fatedier/frp v0.70.0)
