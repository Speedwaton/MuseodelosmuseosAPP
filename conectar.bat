@echo off
rem Conecta el telefono (o emulador) con el backend del PC: redirige los puertos de los 3 microservicios.
rem Ejecutalo con doble clic cada vez que reconectes el cable o reinicies el telefono.

set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

echo Dispositivos conectados:
"%ADB%" devices
echo.

"%ADB%" reverse tcp:8081 tcp:8081 >nul || goto error
"%ADB%" reverse tcp:8082 tcp:8082 >nul || goto error
"%ADB%" reverse tcp:8083 tcp:8083 >nul || goto error

echo Listo. Redirecciones activas:
"%ADB%" reverse --list
echo.
echo   8081 = ms-usuarios   8082 = ms-museos   8083 = ms-tienda
echo Ahora abre la app en el telefono.
pause
exit /b 0

:error
echo.
echo No se pudo conectar. Revisa que:
echo   - el telefono este conectado por USB con la depuracion USB activada
echo   - hayas aceptado el aviso "Permitir depuracion por USB" en el telefono
echo   - solo haya un dispositivo conectado (o cierra el emulador)
pause
exit /b 1
