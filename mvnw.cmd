@echo off
setlocal

set MAVEN_PROJECTBASEDIR=%~dp0
set WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar
set WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties

for /f "usebackq tokens=2 delims==" %%a in (`findstr /b "wrapperUrl" "%WRAPPER_PROPERTIES%"`) do set DOWNLOAD_URL=%%a

if not exist "%WRAPPER_JAR%" (
  echo Downloading Maven Wrapper...
  powershell -Command "Invoke-WebRequest -Uri '%DOWNLOAD_URL%' -OutFile '%WRAPPER_JAR%'"
)

java -jar "%WRAPPER_JAR%" %*
