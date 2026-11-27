@echo off
rem Concede ao app a permissao de ligar a propria acessibilidade ao abrir. Rode UMA vez (e de novo so se parar de funcionar,
rem por exemplo depois de desinstalar o app). Antes: instale o app com o Run do Android Studio, celular ligado por USB.
set ADB=C:\Users\teteu\AppData\Local\Android\Sdk\platform-tools\adb.exe

if not exist "%ADB%" (
  echo Nao achei o adb em: %ADB%
  pause
  exit /b 1
)

"%ADB%" shell pm grant com.matheus.bot android.permission.WRITE_SECURE_SETTINGS
if %errorlevel%==0 (
  echo Pronto. Agora o app liga a acessibilidade sozinho toda vez que abrir.
) else (
  echo Deu erro. Se a mensagem falar em SecurityException, ligue "Depuracao USB (configuracoes de seguranca)" nas opcoes do desenvolvedor.
)
pause
