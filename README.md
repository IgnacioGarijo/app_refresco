# Refresco Web

Aplicacion Android nativa, ligera y local para revisar paginas web periodicamente y avisar cuando aparecen enlaces o documentos nuevos.

La app nace para usar principalmente esta pagina de AIReF, que queda configurada como URL inicial:

https://www.airef.es/es/sobre-nosotros/gestion-de-personal/provision-de-puestos-de-trabajo/personal-laboral-fijo-2025-2/

Tambien puede vigilar otras paginas HTTPS desde la propia interfaz.

## Que hace

- Descarga solo el HTML de la pagina configurada.
- Extrae enlaces razonablemente relevantes del contenido principal: PDF, enlaces de WordPress uploads, BOE y enlaces cuyo texto parezca una resolucion, listado, nota, calendario, cronograma, plantilla o modelo.
- Guarda una referencia inicial en la primera comprobacion correcta y no notifica en esa primera sincronizacion.
- En comprobaciones posteriores avisa si aparecen enlaces nuevos.
- Conserva todo localmente con DataStore: URL, frecuencia, enlaces conocidos, ultimas novedades, ETag, Last-Modified, ultimos estados y errores.
- Usa WorkManager con un trabajo periodico unico.
- No usa servidores externos, Firebase, analitica, publicidad, cuentas ni APIs de pago.

## Cambio de enfoque

La app ya no esta planteada como "Avisos AIReF". Ahora es un monitor general de paginas llamado **Refresco Web**.

AIReF sigue apareciendo solo como:

- URL inicial por defecto.
- Compatibilidad TLS especifica para `airef.es`.
- Filtro opcional avanzado para el apartado "Experto/a en evaluacion de politicas publicas".

Por defecto el filtro esta desactivado. Esto significa que la app vigila cambios relevantes en toda la pagina, que es el comportamiento recomendado para el uso actual.

## Estrategia de extraccion

Modo normal:

1. Jsoup analiza el HTML inicial.
2. El parser toma como zona principal `main article`, `article`, `main`, `body` o el documento completo, en ese orden.
3. Dentro de esa zona recopila enlaces `<a href>`.
4. Filtra enlaces que parezcan publicaciones/documentos para evitar menus, cookies, estilos, scripts o navegacion.
5. Normaliza titulo y URL, elimina tracking, convierte relativas en absolutas y deduplica por URL normalizada.

Modo opcional de apartado:

1. Busca un encabezado cuyo texto normalizado coincida con "Experto/a en evaluacion de politicas publicas".
2. Extrae enlaces del bloque asociado hasta el siguiente encabezado profesional.
3. Si no encuentra el apartado, informa error de parsing sin borrar el estado previo.

## Trabajo en segundo plano

- WorkManager con `enqueueUniquePeriodicWork`.
- Nombre unico del trabajo: `web_refresh_periodic_check`.
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

## TLS en airef.es

La web de AIReF puede presentar problemas de confianza TLS en algunos dispositivos Android por su cadena FNMT. La app mantiene validacion TLS estricta y no acepta certificados invalidos.

Para `airef.es` se incluye la CA publica `AC Componentes Informaticos` de FNMT en `res/raw/fnmt_ac_componentes_informaticos.crt` y se declara en `network_security_config.xml`. Para el resto de URLs se usan las autoridades de certificacion del sistema Android.

## Estructura

```text
app/src/main/java/es/personal/avisosairef/
  data/network/       Cliente HTTP OkHttp
  data/parser/        Parser Jsoup y normalizacion
  data/storage/       Estado local DataStore
  data/repository/    Coordinacion de comprobaciones
  notifications/      Canal y notificaciones
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
09D621CBEE6636B2929E85FB485BA0BFA6A39D4DCD79B6680B2E495E9285054D
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
8. Activa la revision.
9. Pulsa "Comprobar ahora" para crear la referencia inicial.

Si vienes de la version anterior, Android deberia tratarlo como actualizacion porque se mantiene el mismo `applicationId` y la misma firma.

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
- enlace nuevo detectado;
- enlace nuevo en otro bloque detectado en modo pagina completa;
- enlace nuevo en otro bloque ignorado cuando el filtro opcional esta activado;
- duplicados;
- URL relativa;
- cambio de orden;
- ausencia del apartado opcional;
- lista vacia inesperada;
- error de red sin borrar estado anterior;
- estructura HTML moderadamente distinta.

## Motorola Edge 40

En Motorola/Android, WorkManager suele funcionar bien sin tocar ajustes agresivos. Si ves retrasos excesivos:

1. Ajustes > Bateria > Uso de bateria por app.
2. Busca "Refresco Web".
3. Permite actividad en segundo plano si el sistema la restringe.

No hace falta desactivar por completo las optimizaciones de bateria; la frecuencia es aproximada y Android puede agrupar o retrasar trabajos.
