@echo off
echo Checking Android device...
adb devices
echo.
echo Setting up USB tunnel for RilzzCam port 4747...
adb reverse tcp:4747 tcp:4747
echo.
echo In Android RilzzCam app use host 127.0.0.1 and port 4747.
pause
