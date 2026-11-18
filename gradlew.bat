@echo off
setlocal
set VERSION=8.9
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set GRADLE_HOME_DIR=%GRADLE_USER_HOME%\bootstrap\gradle-%VERSION%
if not exist "%GRADLE_HOME_DIR%\bin\gradle.bat" (
  if not exist "%GRADLE_USER_HOME%\bootstrap" mkdir "%GRADLE_USER_HOME%\bootstrap"
  set ZIP=%GRADLE_USER_HOME%\bootstrap\gradle-%VERSION%-bin.zip
  if not exist "%ZIP%" powershell -NoProfile -Command "Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
  powershell -NoProfile -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%GRADLE_USER_HOME%\bootstrap' -Force"
)
call "%GRADLE_HOME_DIR%\bin\gradle.bat" %*
endlocal
