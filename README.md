# Refresco Web

Aplicacion Android nativa, ligera y local para revisar paginas web periodicamente y avisar cuando aparecen enlaces o documentos nuevos.

## Que hace

- Permite configurar varias paginas.
- Agrupa paginas por grupo, con color propio elegido por el usuario.
- Cada pagina tiene nombre, URL, frecuencia, selector CSS opcional y palabras clave opcionales.
- Las tarjetas de pagina muestran solo lo esencial: nombre, iconos de editar, refrescar, abrir, detalles, eliminar e interruptor individual.
- Cada grupo puede plegarse o desplegarse para ocultar sus paginas.
- El interruptor maestro esta en la cabecera: al apagarlo desactiva las paginas y al encenderlo restaura las que estaban activas.
- Descarga solo el HTML de la pagina configurada.
- Extrae enlaces razonablemente relevantes del contenido principal: PDF, WordPress uploads, BOE y enlaces cuyo texto parezca una resolucion, listado, nota, calendario, cronograma, plantilla o modelo.
- Guarda una referencia inicial por pagina en la primera comprobacion correcta y no notifica en esa primera sincronizacion.
- En comprobaciones posteriores avisa si aparecen enlaces nuevos.
- Conserva todo localmente con DataStore: paginas, frecuencias, enlaces conocidos, ultimas novedades, ETag, Last-Modified, ultimos estados, errores y ajustes de Telegram.
- Usa WorkManager con un trabajo periodico unico.
- No usa Firebase, analitica, publicidad, cuentas ni APIs de pago. Telegram es opcional y solo se contacta con `api.telegram.org` si lo activas.

## Interfaz

La pantalla principal tiene:

- Cabecera con acceso a Ajustes.
- Interruptor maestro **Comprobaciones automaticas**.
- Boton para anadir paginas.
- Tarjetas de grupo.
- Tarjetas internas de pagina.

Los ajustes incluyen:

- Frecuencia por defecto para nuevas paginas.
- Avisos por Telegram.
- Bot token.
- Chat ID.

## Notificaciones externas

La app soporta notificaciones Android y, opcionalmente, Telegram.

Para Telegram debes crear un bot con BotFather y pegar localmente:

- Bot token.
- Chat ID.

La app envia el aviso directamente a `https://api.telegram.org` cuando hay novedades. No hay servidor intermedio ni secretos versionados. WhatsApp no se implementa porque el envio automatico fiable requiere WhatsApp Business/API, backend o automatizaciones poco robustas. Correo automatico tampoco se incluye por la misma razon: sin SMTP/backend solo seria posible abrir un borrador manual.

## Color y tema

La identidad visual usa:

- Primario: `#063347`.
- Secundario: `#b3bec6`.

El modo oscuro usa variantes mas claras para mantener contraste.

## Estrategia de extraccion

1. Jsoup analiza el HTML inicial.
2. Si hay selector CSS, se extraen enlaces dentro de los elementos que coincidan.
3. Si no hay selector, el parser toma como zona principal `main article`, `article`, `main`, `body` o el documento completo, en ese orden.
4. Dentro de esa zona recopila enlaces `<a href>`.
5. Filtra enlaces que parezcan publicaciones/documentos para evitar menus, cookies, estilos, scripts o navegacion.
6. Aplica palabras clave si se han configurado.
7. Normaliza titulo y URL, elimina tracking, convierte relativas en absolutas y deduplica por URL normalizada.

## Trabajo en segundo plano

- WorkManager con `enqueueUniquePeriodicWork`.
- Nombre unico del trabajo: `web_refresh_periodic_check`.
- En actualizaciones desde versiones antiguas se cancela el trabajo legado `airef_publications_periodic_check`.
- `NetworkType.CONNECTED`.
- `requiresBatteryNotLow(true)`.
- No requiere estar cargando.
- No requiere Wi-Fi.
- Sin `AlarmManager`, foreground service, wake locks manuales ni WebView oculto.
- Frecuencia configurable: 15, 30, 60 o 120 minutos. Android puede retrasar las ejecuciones para ahorrar bateria.
- Reintentos con backoff exponencial ante errores transitorios.

## Red y bateria

Cada comprobacion hace una sola peticion HTTP a la URL configurada:

- `Accept-Encoding: gzip`.
- User-Agent claro: `RefrescoWeb/1.0 (Android personal web monitor)`.
- Timeout razonable.
- Respeta redirecciones HTTPS.
- Usa `ETag` / `If-None-Match` y `Last-Modified` / `If-Modified-Since` si el servidor los ofrece.
- Ante HTTP 304 registra comprobacion correcta sin parsear de nuevo.
- Limita el cuerpo HTML a 1,5 MB.
- No descarga PDF, imagenes, fuentes, CSS ni JavaScript.

## TLS

Para URLs normales se usa el almacen de certificados del sistema Android mediante OkHttp sin trust manager personalizado.

La app conserva compatibilidad adicional para un sitio publico con cadena FNMT que puede fallar en algunos dispositivos Android. Esa excepcion se aplica solo a `airef.es` y sus subdominios; el resto de dominios, como GitHub Pages o X, usan el cliente TLS estandar.

## Estructura

```text
app/src/main/java/es/personal/avisosairef/
  data/network/       Cliente HTTP OkHttp
  data/parser/        Parser Jsoup y normalizacion
  data/storage/       Estado local DataStore
  data/repository/    Coordinacion de comprobaciones
  notifications/      Canal, Android y Telegram
  worker/             WorkManager
  ui/theme/           Tema Compose
  MainActivity.kt     Pantalla principal Compose
```

El `applicationId` se mantiene como `es.personal.avisosairef` para que puedas actualizar la app existente sin perder datos locales.

## Compilar

```powershell
$env:JAVA_HOME='C:\Program Files\StataNow19\utilities\java\windows-x64\zulu-jdk21.0.9'
$env:ANDROID_HOME=(Resolve-Path .android-sdk).Path
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat test lint assembleRelease
```

## APK

APK principal:

```text
dist/RefrescoWeb.apk
```

SHA-256:

```text
1AA181BF1274426B7C6AE85B2203D05D9B0EFBA81E4779AC230D712115D4B8B6
```

Calcular hash local:

```powershell
Get-FileHash .\dist\RefrescoWeb.apk -Algorithm SHA256
```

## Instalacion directa

1. Copia `dist/RefrescoWeb.apk` al movil.
2. Abre el APK desde Archivos, Drive, navegador o la app que uses para copiarlo.
3. Permite temporalmente "Instalar aplicaciones desconocidas" para esa app.
4. Instala.
5. Retira ese permiso si quieres.
6. Abre "Refresco Web".
7. Concede notificaciones si Android lo pide.
8. Activa **Comprobaciones automaticas**.
9. Anade o edita una pagina.
10. Pulsa **Refrescar** en esa tarjeta para crear la referencia inicial.

## Instalacion mediante ADB

```powershell
adb install -r dist/RefrescoWeb.apk
```

Para actualizar posteriormente sin perder datos:

1. Mantener el mismo `applicationId`: `es.personal.avisosairef`.
2. Firmar con la misma clave local.
3. Instalar encima:

```powershell
adb install -r dist/RefrescoWeb.apk
```

## Firma local

La release se firma con una clave local de este proyecto si existe `signing/signing.properties`.

No subas a Git:

- `signing/*.jks`
- `signing/signing.properties`

Conserva la clave si quieres instalar futuras actualizaciones sin desinstalar.

## Verificacion realizada

Antes de entregar una build se ejecuta:

- `test`
- `lint`
- `assembleRelease`
- revision de permisos del Manifest
- pruebas unitarias de parser y repositorio

Casos cubiertos por tests:

- referencia inicial sin notificacion masiva;
- varias paginas configurables;
- selector CSS y palabras clave opcionales;
- enlace nuevo detectado;
- enlace nuevo en otro bloque detectado en modo pagina completa;
- duplicados;
- URL relativa;
- cambio de orden;
- lista vacia inesperada;
- error de red sin borrar estado anterior;
- estructura HTML moderadamente distinta.

## Motorola Edge 40

En Motorola/Android, WorkManager suele funcionar bien sin tocar ajustes agresivos. Si ves retrasos excesivos:

1. Ajustes > Bateria > Uso de bateria por app.
2. Busca "Refresco Web".
3. Permite actividad en segundo plano si el sistema la restringe.

No hace falta desactivar por completo las optimizaciones de bateria; la frecuencia es aproximada y Android puede agrupar o retrasar trabajos.
