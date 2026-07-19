@echo off
REM ============================================
REM FrpcLib v0.70.0 一键构建脚本
REM ============================================
REM 前提：Go 1.22+ + Android Studio（含 NDK）
REM 使用：双击运行，完成后自动清理临时文件
REM ============================================
setlocal EnableDelayedExpansion
set FRP_VER=v0.70.0
set FRP_VER_SHORT=0.70.0
set TMP_DIR=%TEMP%\frpclib_build_%RANDOM%
set FRP_DIR=%TMP_DIR%\frp_source
set WRP_DIR=%TMP_DIR%\wrapper
set LOG=%TMP_DIR%\build.log

echo [1/8] 检测 Go 环境...
where go >nul 2>nul
if %ERRORLEVEL% NEQ 0 ( echo [错误] 未找到 Go: https://go.dev/dl/ & exit /b 1 )
for /f "tokens=3" %%v in ('go version') do echo  %%v

echo [2/8] 检测 gomobile...
where gomobile >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo 正在安装 gomobile...
    go install golang.org/x/mobile/cmd/gomobile@latest
)
mkdir "%TMP_DIR%" 2>nul

echo [3/8] 检测 Android SDK...
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" ( set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
    ) else ( echo [错误] 请设置 ANDROID_HOME 环境变量 & exit /b 1 )
)
call gomobile init >"%LOG%" 2>&1

echo [4/8] 拉取 frp %FRP_VER% 源码...
git clone --depth=1 -b %FRP_VER% https://github.com/fatedier/frp.git "%FRP_DIR%" >"%LOG%" 2>&1
if %ERRORLEVEL% NEQ 0 ( echo [错误] frp 源码拉取失败 & exit /b 1 )

echo [5/8] 创建 Go 包装器...
mkdir "%WRP_DIR%"
copy NUL "%WRP_DIR%\go.mod" >nul
(
  echo module frpclibwrapper
  echo go 1.22
  echo require github.com/fatedier/frp v%FRP_VER_SHORT%
  echo replace github.com/fatedier/frp =^> "%FRP_DIR:\=\\%"
) > "%WRP_DIR%\go.mod"

copy NUL "%WRP_DIR%\frpclib.go" >nul
(
  echo package frpclib
  echo.
  echo import ^(
  echo    "context"
  echo    "fmt"
  echo    "os"
  echo    "sync"
  echo    "time"
  echo    "github.com/fatedier/frp/client"
  echo    "github.com/fatedier/frp/pkg/config"
  echo    "github.com/fatedier/frp/pkg/config/source"
  echo    "github.com/fatedier/frp/pkg/util/log"
  echo    "github.com/fatedier/frp/pkg/util/version"
  echo ^)
  echo.
  echo func init^(^) { log.InitLogger^("console", "error", 0, false^) }
  echo.
  echo var ^(
  echo    mu sync.Mutex
  echo    svcs = make^(map[int64]*client.Service^)
  echo    canc = make^(map[int64]context.CancelFunc^)
  echo ^)
  echo.
  echo func GetVersion^(^) string { return version.Full^(^) }
  echo.
  echo func RunContent^(uid int64, content string^) bool {
  echo    mu.Lock^(^); defer mu.Unlock^(^)
  echo    if s, ok := svcs[uid]; ok { s.GracefulClose^(3*time.Second^); delete^(svcs, uid^) }
  echo    if c, ok := canc[uid]; ok { c^(^); delete^(canc, uid^) }
  echo    f, _ := os.CreateTemp^("", fmt.Sprintf^("frpc_%%d_*.toml", uid^)^)
  echo    if f == nil { return false }
  echo    f.WriteString^(content^); f.Close^(^)
  echo    path := f.Name^(^)
  echo    defer os.Remove^(path^)
  echo    common, proxies, visitors, _, err := config.LoadClientConfig^(path, false^)
  echo    if err != nil { return false }
  echo    src := source.NewConfigSource^(^)
  echo    if src.ReplaceAll^(proxies, visitors^) != nil { return false }
  echo    agg := source.NewAggregator^(src^)
  echo    svr, err := client.NewService^(client.ServiceOptions{Common: common, ConfigSourceAggregator: agg}^)
  echo    if err != nil { return false }
  echo    ctx, cancel := context.WithCancel^(context.Background^(^)^)
  echo    go svr.Run^(ctx^)
  echo    svcs[uid] = svr; canc[uid] = cancel
  echo    return true
  echo }
  echo.
  echo func IsRunning^(uid int64^) bool { mu.Lock^(^); defer mu.Unlock^(^); _, ok := svcs[uid]; return ok }
  echo.
  echo func Close^(uid int64^) bool {
  echo    mu.Lock^(^); defer mu.Unlock^(^)
  echo    if s, ok := svcs[uid]; ok { s.GracefulClose^(3*time.Second^); delete^(svcs, uid^) }
  echo    if c, ok := canc[uid]; ok { c^(^); delete^(canc, uid^) }
  echo    return true
  echo }
) > "%WRP_DIR%\frpclib.go"

echo [6/8] 添加移动端依赖...
cd /d "%WRP_DIR%"
go get golang.org/x/mobile@latest >"%LOG%" 2>&1
if %ERRORLEVEL% NEQ 0 ( echo [错误] 依赖添加失败 & exit /b 1 )

echo [7/8] 编译 frpclib.aar...
call gomobile bind -v -target android -androidapi 21 -o "%~dp0app\libs\frpclib.aar" frpclibwrapper >"%LOG%" 2>&1
if %ERRORLEVEL% NEQ 0 ( type "%LOG%" & echo [错误] 编译失败 & exit /b 1 )

echo [8/8] 清理临时文件...
rmdir /s /q "%TMP_DIR%" 2>nul

echo [成功] app/libs/frpclib.aar (frp %FRP_VER_SHORT%)
