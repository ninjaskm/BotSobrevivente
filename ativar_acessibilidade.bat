@echo off
rem Liga a acessibilidade do Bot Sobrevivente pelo adb (sem abrir Configuracoes no celular).
rem Use depois de cada Run no Android Studio, com o celular ligado por USB.
set ADB=C:\Users\teteu\AppData\Local\Android\Sdk\platform-tools\adb.exe
set SVC=com.matheus.bot/com.matheus.bot.BotAccessibilityService

if not exist "%ADB%" (
  echo Nao achei o adb em: %ADB%
  pause
  exit /b 1
)

set CUR=null
for /f "delims=" %%A in ('"%ADB%" shell settings get secure enabled_accessibility_services') do set CUR=%%A

echo Servicos ligados agora: %CUR%
echo %CUR% | find "%SVC%" >nul
if %errorlevel%==0 (
  echo O Bot Sobrevivente ja esta ligado.
  goto fim
)

rem Mantem os outros servicos de acessibilidade que ja estavam ligados.
if "%CUR%"=="null" (set NEW=%SVC%) else (set NEW=%CUR%:%SVC%)
"%ADB%" shell settings put secure enabled_accessibility_services %NEW%
"%ADB%" shell settings put secure accessibility_enabled 1
echo Pronto: acessibilidade do Bot Sobrevivente ligada.

:fim
pause
