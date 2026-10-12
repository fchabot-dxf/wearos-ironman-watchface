@echo off
set "JAVA_HOME=C:\Users\danse\AppData\Local\Android\jdk17\jdk-17.0.12+7"
set "ADB=c:\Users\danse\APPS\WearOS\tools\platform-tools\adb.exe"

echo [1/3] Building Watch Face APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed! Check XML syntax in watch_face.xml.
    pause
    exit /b %ERRORLEVEL%
)

echo [2/3] Installing on emulator...
"%ADB%" -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk

echo [3/3] Refreshing watch display...
"%ADB%" -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

echo ========================================================
echo Done! Changes are now live on your Pixel Watch emulator.
echo ========================================================
pause
