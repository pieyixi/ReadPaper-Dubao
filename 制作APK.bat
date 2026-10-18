@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo ========================================
echo   读报 APK 一键打包
echo ========================================
echo.

:: 检查 Java
echo [1/3] 检查 Java...
if not defined JAVA_HOME (
    echo   [错误] JAVA_HOME 未设置，请先运行 D:\Envirnment\ENV_SETUP.md
    pause & exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -version >nul 2>&1
if errorlevel 1 (
    echo   [错误] Java 不可用
    pause & exit /b 1
)
echo   [OK] Java 已就绪

:: 检查 Android SDK
echo [2/3] 检查 Android SDK...
if not exist "D:\Envirnment\android-sdk\platforms\android-34" (
    echo   [错误] 找不到 android-34 平台
    echo   请确认 D:\Envirnment\android-sdk 存在
    pause & exit /b 1
)
echo sdk.dir=D:/Envirnment/android-sdk> local.properties
echo   [OK] Android SDK 已就绪

:: 编译
echo [3/3] 编译 APK...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo [错误] 编译失败
    pause & exit /b 1
)

:: 完成
echo.
echo ========================================
echo   打包完成！
echo ========================================
set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
if exist "%APK_PATH%" (
    for %%f in ("%APK_PATH%") do set APK_MB=%%~zf
    set /a APK_MB=!APK_MB! / 1048576
    echo   APK: %~dp0%APK_PATH%
    echo   大小: !APK_MB! MB
    start explorer /select,"%~dp0%APK_PATH%"
) else (
    echo   [错误] APK 文件未找到
)
echo.
pause
