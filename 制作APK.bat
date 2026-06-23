@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo.
echo ========================================
echo   读报 APK 一键打包脚本
echo ========================================
echo.

cd /d "%~dp0"

:: ============================================
:: Step 1: 检查 Java 17
:: ============================================
echo [1/5] 检查 Java 环境...

set JAVA_BIN=
if defined JAVA_HOME set JAVA_BIN=%JAVA_HOME%\bin\java.exe
if not defined JAVA_BIN set JAVA_BIN=java.exe

"%JAVA_BIN%" -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 找不到 Java，请安装 JDK 17
    echo 下载地址: https://adoptium.net/temurin/releases/?version=17
    pause
    exit /b 1
)

set JAVA_VER=
for /f "tokens=3" %%i in ('"%JAVA_BIN%" -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%i
set JAVA_VER=%JAVA_VER:"=%

echo %JAVA_VER% | findstr "17." >nul
if errorlevel 1 (
    echo [警告] 当前 Java 版本: %JAVA_VER%
    echo 建议使用 JDK 17，其他版本可能不兼容
    echo 下载地址: https://adoptium.net/temurin/releases/?version=17
    echo.
    choice /c yn /n /m "继续使用当前版本？(Y=是 N=否): "
    if errorlevel 2 exit /b 1
) else (
    echo   [OK] Java 17 已就绪 (%JAVA_VER%)
)

:: ============================================
:: Step 2: 检查/设置 Android SDK
:: ============================================
echo.
echo [2/5] 检查 Android SDK...

set SDK_DIR=
set USE_SUBST=

:: 2a. 尝试从 local.properties 读取
if exist local.properties (
    for /f "tokens=1,2 delims==" %%a in (local.properties) do (
        if "%%a"=="sdk.dir" set SDK_DIR=%%b
    )
)

:: 2b. 尝试环境变量
if not defined SDK_DIR if defined ANDROID_HOME set SDK_DIR=%ANDROID_HOME%
if not defined SDK_DIR if defined ANDROID_SDK_ROOT set SDK_DIR=%ANDROID_SDK_ROOT%

:: 2c. 尝试常见路径
if not defined SDK_DIR if exist "%LOCALAPPDATA%\Android\Sdk" set SDK_DIR=%LOCALAPPDATA%\Android\Sdk
if not defined SDK_DIR if exist "C:\Android\Sdk" set SDK_DIR=C:\Android\Sdk

:: 2d. 检查本地 sdk 目录
set LOCAL_SDK=%~dp0sdk
if not defined SDK_DIR if exist "%LOCAL_SDK%\platforms\android-34" set SDK_DIR=%LOCAL_SDK%

:: 如果找到了 SDK，验证是否有所需组件
if defined SDK_DIR (
    if exist "%SDK_DIR%\platforms\android-34" (
        echo   [OK] Android SDK 已找到
        goto :sdk_ready
    )
    echo   SDK 存在但缺少 android-34，将尝试安装...
    set SDK_DIR=
)

:: 2e. 下载并安装 Android SDK
echo   未找到 Android SDK，正在下载...
set SDK_DIR=%LOCAL_SDK%

:: 检查项目路径是否含中文，如果是则用 subst 映射
set PROJECT_PATH=%~dp0
echo %PROJECT_PATH% | findstr /r "[^\x00-\x7F]" >nul
if not errorlevel 1 (
    echo   [注意] 项目路径含中文，将使用虚拟驱动器映射...
    set USE_SUBST=1
)

set CMDLINE_URL=https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
set CMDLINE_ZIP=%TEMP%\android-cmdline-tools.zip

echo   从 Google 下载 cmdline-tools（约 150MB）...
powershell -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%CMDLINE_URL%' -OutFile '%CMDLINE_ZIP%' -UseBasicParsing -TimeoutSec 300 } catch { Write-Host 'FAILED: ' $_.Exception.Message; exit 1 }" 
if errorlevel 1 (
    echo   [错误] 下载失败，请检查网络连接后重试
    echo   或手动安装 Android SDK 并设置 ANDROID_HOME 环境变量
    pause
    exit /b 1
)

:: 解压 cmdline-tools
echo   正在解压...
set CMDLINE_DIR=%SDK_DIR%\cmdline-tools\latest
if not exist "%CMDLINE_DIR%" mkdir "%CMDLINE_DIR%"
powershell -Command "Expand-Archive -Path '%CMDLINE_ZIP%' -DestinationPath '%CMDLINE_DIR%' -Force"
del "%CMDLINE_ZIP%" 2>nul

:: 如果解压后多了一层目录，把内容移到正确位置
if exist "%CMDLINE_DIR%\cmdline-tools\bin" (
    xcopy /E /Y "%CMDLINE_DIR%\cmdline-tools\*" "%CMDLINE_DIR%\" >nul
    rmdir /S /Q "%CMDLINE_DIR%\cmdline-tools" 2>nul
)

:: 安装所需 SDK 组件
echo   正在安装 Android SDK 组件...
set SDKMANAGER=%CMDLINE_DIR%\bin\sdkmanager.bat

:: 接受许可协议（管道多行 y）
powershell -Command "@('y','y','y','y','y','y','y','y','y','y') | Out-File -FilePath '%TEMP%\_sdk_yes.txt' -Encoding ascii"
type "%TEMP%\_sdk_yes.txt" | "%SDKMANAGER%" --licenses >nul 2>&1

:: 安装平台和构建工具
"%SDKMANAGER%" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

if not exist "%SDK_DIR%\platforms\android-34" (
    echo   [错误] Android SDK 安装失败
    pause
    exit /b 1
)
echo   [OK] Android SDK 安装完成

:sdk_ready

:: ============================================
:: Step 3: 配置项目
:: ============================================
echo.
echo [3/5] 配置项目...

:: 如果路径含中文，用 subst 映射 SDK 目录和项目目录
if defined USE_SUBST (
    subst S: "%SDK_DIR%" 2>nul
    echo sdk.dir=S\:\\> local.properties
    echo   [OK] SDK 已映射到 S:\
) else (
    :: 将 SDK 路径中的反斜杠替换为正斜杠
    set SDK_FORWARD=%SDK_DIR:\=/%
    echo sdk.dir=!SDK_FORWARD!> local.properties
    echo   [OK] local.properties 已生成
)

:: 确保 gradle.properties 有 overridePathCheck
if not exist gradle.properties (
    echo org.gradle.jvmargs=-Xmx2g> gradle.properties
    echo android.useAndroidX=true>> gradle.properties
)
findstr /c:"android.overridePathCheck" gradle.properties >nul 2>&1
if errorlevel 1 (
    echo android.overridePathCheck=true>> gradle.properties
)

:: ============================================
:: Step 4: 检查 Gradle Wrapper
:: ============================================
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo   [错误] 缺少 gradle-wrapper.jar，请确认文件存在
    pause
    exit /b 1
)
echo   [OK] Gradle Wrapper 已就绪

:: ============================================
:: Step 5: 编译 APK
:: ============================================
echo.
echo [4/5] 开始编译 APK（首次需要下载依赖，请耐心等待）...
echo.

call gradlew.bat assembleDebug

set BUILD_RESULT=%ERRORLEVEL%

:: 清理 subst 映射
if defined USE_SUBST subst S: /d 2>nul

if %BUILD_RESULT% neq 0 (
    echo.
    echo [错误] 编译失败，请检查上方错误信息
    pause
    exit /b 1
)

:: ============================================
:: Step 6: 输出 APK
:: ============================================
echo.
echo [5/5] 打包完成！
echo.

set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
if exist "%APK_PATH%" (
    for %%f in ("%APK_PATH%") do set APK_SIZE=%%~zf
    set /a APK_MB=!APK_SIZE! / 1048576
    echo   [OK] APK 已生成: %~dp0%APK_PATH%
    echo   文件大小: !APK_MB! MB
    echo.
    echo   将 APK 传输到手机安装即可使用。
    echo.
    start explorer /select,"%~dp0%APK_PATH%"
) else (
    echo   [错误] APK 文件未找到，请检查编译日志
)

echo ========================================
pause
