# Museo de los Museos

App Android del caso Museo de los Museos: Kotlin, Jetpack Compose, MVVM con **un solo ViewModel**,
**Retrofit** para consumir el backend, Room como copia local, OpenStreetMap, cámara, escáner QR,
animaciones, notificaciones locales y los 3 roles. Los museos muestran **fotos reales** de Wikimedia Commons.

Los datos (usuarios, museos, productos y solicitudes) vienen del backend **Spring Boot con 3 microservicios**
de la carpeta `Museo de los Museos Backend`:

| Microservicio | Puerto | Lo usa la app para |
|---|---|---|
| `ms-usuarios` | 8081 | Iniciar sesión, registrarse, editar el perfil y activar/desactivar cuentas |
| `ms-museos` | 8082 | Mapa y fichas, crear y corregir fichas, publicarlas u observarlas, buscar el QR de un museo |
| `ms-tienda` | 8083 | Catálogo, compras (stock y envío los calcula el servidor), consultas y estados de solicitudes |

Room guarda una copia de lo último que respondió el servidor: si se corta la conexión, las pantallas siguen
mostrando esos datos y la app avisa "Sin conexión con el servidor". Favoritos, carrito, historial QR y avisos
se guardan solo en el teléfono.

## Ejecutar

1. **Backend:** enciende MySQL en Laragon e inicia los 3 microservicios (ver el README del backend).
2. **Conecta el teléfono o el emulador** con depuración USB y redirige los 3 puertos hacia el PC. Lo más
   simple es hacer doble clic en **`conectar.bat`** (en esta carpeta), o escribir:

```bash
adb reverse tcp:8081 tcp:8081
```

```bash
adb reverse tcp:8082 tcp:8082
```

```bash
adb reverse tcp:8083 tcp:8083
```

   Hay que repetirlos cada vez que se reconecta el cable. Para usar Wi-Fi en su lugar, cambia `HOST` en
   `data/remote/RetrofitInstance.kt` por la IP del PC (por ejemplo `http://192.168.1.50`) y permite los puertos
   en el Firewall de Windows.
3. Abre esta carpeta en Android Studio y ejecuta `app` (Android 8.0 / API 26 o superior). Por consola:

```bash
gradlew.bat assembleDebug
```

```bash
gradlew.bat testDebugUnitTest
```

```bash
gradlew.bat connectedDebugAndroidTest
```

El primero genera la APK en `C:\Users\<usuario>\.museo-build\MuseoDeLosMuseos\app\outputs\apk\debug\app-debug.apk` (la carpeta de compilación está fuera del proyecto para que OneDrive no la bloquee); el segundo corre las pruebas unitarias y el tercero
la prueba de migración Room 1 → 2 (necesita un dispositivo). Se necesita Internet para el mapa y las fotos, y
Google Play services para el escáner QR.

## Cuentas de demostración (ficticias)

| Rol | Correo | Contraseña |
|---|---|---|
| Administrador | admin@museo.demo | Admin#2026 |
| Investigador | investigador@museo.demo | Investiga#2026 |
| Cliente | cliente@museo.demo | Cliente#2026 |
| Cliente 2 | lucia@museo.demo | Cliente#2026 |

El botón **"Usar cuenta de demostración"** las rellena. Los clientes nuevos se crean en **Registro**.

## Estructura (17 archivos Kotlin)

```text
MainActivity.kt          Activity única, configuración de osmdroid y apertura desde notificaciones
ViewModel.kt             ÚNICO ViewModel: sesión, navegación, museos, catálogo, carrito, QR, avisos, perfil
data/Entidades.kt        Tablas Room (usuarios, museos, favoritos, productos, carrito, solicitudes, lecturas_qr, notificaciones)
data/MuseoDao.kt         Único DAO: caché de lo que llega del backend y datos propios del teléfono
data/BaseDatos.kt        Base Room v2, MIGRACION_1_2 y las cuentas de demostración
data/Repositorio.kt      Une la API y Room: login, registro, sincronización por rol y avisos
data/remote/ApiService.kt       Interfaces Retrofit: UsuariosApi, MuseosApi y TiendaApi
data/remote/Dtos.kt             JSON que se envía y recibe de los microservicios
data/remote/RetrofitInstance.kt Un cliente Retrofit por microservicio (puertos 8081, 8082 y 8083)
data/remote/ApiRepositorio.kt   Todas las llamadas a la API, con mensajes de error claros
model/Logica.kt          Roles/permisos, pantallas, validaciones, envío ficticio, lector QR, hash
util/Utilidades.kt       Permisos, notificaciones, cámara, imágenes web, QR y marcadores del mapa
ui/Componentes.kt        Tema, barra, campos, foto web, selector y mapa
ui/MuseoApp.kt           Raíz con barra inferior por rol
ui/Pantallas*.kt         Las 15 pantallas del documento
```

## Pantallas por rol

1 Bienvenida · 2 Registro (Cliente) · 3 Selección de rol · 4 Dashboard por rol · 5 Mapa OSM con filtros país/proyecto ·
6 Detalle del museo (favorito, corrección, revisión, QR de la ficha) · 7 Escáner QR · 8 Resultado del QR ·
9 Catálogo (Admin gestiona) · 10 Detalle y compra/información · 11 Favoritos · 12 Carrito y envío ·
13 Solicitudes (Admin cambia estados) · 14 Notificaciones (Admin envía avisos) · 15 Perfil y permisos.

## Fotos reales

Las 12 fichas usan fotografías de **Wikimedia Commons**, que se descargan al abrir la app (se identifica con su
propio User-Agent y las guarda en memoria). Cada foto tiene la licencia libre de su autor, indicada en
su página de Commons. La foto tomada con la cámara (columna `fotoRuta`, versión 2 de la base) reemplaza a la foto web.
Sin Internet se muestra un emoji.

## Pruebas unitarias

Como en el ejemplo de la guía, usan JUnit4, MockK (simulaciones), Kotest (`shouldBe`) y
kotlinx-coroutines-test (`runTest`). Ninguna se conecta al backend.

| Archivo | Qué verifica |
|---|---|
| `test/.../data/remote/ApiRepositorioTest.kt` | Con las 3 APIs simuladas: login, mensajes de error del servidor (401, 409, 500), backend apagado, compra, crear o actualizar fichas y productos |
| `test/.../ViewModelTest.kt` | Con el repositorio simulado: validación del login, login correcto y rechazado, registro con clave débil, compra correcta y compra sin stock |
| `test/.../LogicaTest.kt` | Validaciones, cálculo de envío, lector QR, permisos por rol y cifrado de contraseñas |
| `androidTest/.../MigracionTest.kt` | Migración de Room 1 → 2 (en un dispositivo) |

Para que se puedan probar, el `ViewModel` y el `ApiRepositorio` reciben sus dependencias por el constructor
(inyección de dependencias), con un valor por defecto para que la app funcione igual.

## APK firmada

1. **Build > Generate Signed App Bundle or APK... > APK > Next**.
2. **Create new...** y completa: ruta del Key Store (por ejemplo `museo.jks`, guárdalo fuera del proyecto),
   contraseña del almacén, alias (`museo`), contraseña de la clave, validez (25 años) y tus datos.
3. Elige la variante **release** y presiona **Create**.
4. La APK queda en `app/release/app-release.apk`.

Guarda el `.jks` y sus contraseñas: sin ellos no se puede publicar una actualización. No los subas a GitHub.
Para instalar la APK firmada sobre una versión de prueba, desinstala antes la app (las firmas son distintas).

## Migración

`app/schemas` guarda `1.json` y `2.json`. `MIGRACION_1_2` agrega `museos.fotoRuta` sin borrar datos, y
`MigracionTest` lo comprueba.

## QR

**Lectura con cámara:** se usa el escáner de Google Play services, como el ejemplo de QR: abre su propia
pantalla de cámara y devuelve el texto. Si la app se instaló desde Android Studio, el teléfono aún no tiene ese
escáner ("Failed to scan code"); por eso la app lo descarga la primera vez que se usa (unos segundos) y luego abre
la cámara. También se puede probar con **"Probar sin cámara"**, pegando el JSON.
La carpeta `qr/` trae un PNG por museo y `qr_museos.txt` sus JSON para probar.

El JSON lleva `tipo` (`museo`, `exposicion`, `publicacion`, `producto`, `curso`) y `codigo` (`MUS-001`…`MUS-012`,
`PRD-001`…`PRD-009`). Ejemplo: `{"tipo":"museo","codigo":"MUS-004","titulo":"Museo Nacional del Prado"}`.

## Limitaciones

- El backend no usa tokens: la app envía el id del usuario conectado. Es suficiente para el ramo, no para producción.
- Favoritos, carrito, historial QR y avisos no se sincronizan entre teléfonos (viven en Room).
- Notificaciones push reales (FCM) requieren Firebase; hoy son avisos locales por usuario.
- Pagos, courier y validación oficial de stock son simulados.
- La sesión no persiste al cerrar la app y el idioma solo se guarda como preferencia.
