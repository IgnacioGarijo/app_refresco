# Avisos AIReF

Aplicacion Android nativa, sencilla y local, para vigilar nuevas publicaciones en la pagina publica de AIReF:

https://www.airef.es/es/sobre-nosotros/gestion-de-personal/provision-de-puestos-de-trabajo/personal-laboral-fijo-2025-2/

El apartado monitorizado es:

**Experto/a en evaluacion de politicas publicas**

La app esta pensada para uso personal. No usa cuentas, Firebase, analitica, publicidad, servidores externos ni APIs de pago. Toda la logica y el estado viven en el telefono.

## Funcionalidad

- Comprueba periodicamente la pagina de AIReF con WorkManager.
- Vigila solo el apartado "Experto/a en evaluacion de politicas publicas".
- Detecta documentos o enlaces nuevos dentro de ese apartado.
- Ignora cambios de cookies, menus, scripts, estilos, orden del HTML y otros procesos selectivos.
- La primera comprobacion correcta crea una referencia inicial y no genera una notificacion masiva.
- Las comprobaciones posteriores notifican documentos nuevos.
- Incluye una pantalla Compose en espanol con estado, ultima comprobacion, numero de documentos conocidos, historial y acciones manuales.
- Permite "Comprobar ahora" sin crear workers adicionales.
- Permite "Restablecer referencia" con confirmacion.
- Permite editar la URL monitorizada.
- Permite elegir una frecuencia aproximada: 15, 30, 60 o 120 minutos.

## Estrategia de extraccion

La pagina actual de AIReF esta construida con WordPress/Elementor. En el HTML inicial, el titulo del puesto aparece como texto visible en un bloque de texto, seguido de secciones internas con enlaces `<a>` a PDF de AIReF y BOE.

El extractor usa una estrategia por capas:

1. Normaliza texto ignorando acentos, mayusculas, espacios repetidos y diferencias tipograficas menores.
2. Busca un nodo cuyo texto propio coincida con `Experto/a en evaluacion de politicas publicas`.
3. Sube al contenedor estructural mas cercano del bloque.
4. Recorre los nodos posteriores y extrae enlaces.
5. Detiene la extraccion cuando encuentra otro encabezado de puesto equivalente, por ejemplo "Analista macroeconomico" o "Experto/a en comunicacion".
6. Si la estructura principal no sirve, usa un fallback de contenedor cercano asociado al mismo titulo.

No depende de clases CSS generadas, posiciones numericas rigidas ni detalles visuales fragiles.

## Normalizacion y deteccion

Cada publicacion se guarda con:

- titulo visible original;
- titulo normalizado para comparar;
- URL absoluta normalizada;
- tipo inferido cuando es posible: PDF, BOE, listado, nota, calendario, resolucion;
- fecha detectada en el titulo cuando exista;
- clave principal basada en la URL normalizada.

La app elimina fragmentos de URL, filtra parametros de tracking, convierte rutas relativas en absolutas y deduplica enlaces repetidos.

## Trabajo en segundo plano

Se usa WorkManager con un trabajo periodico unico:

- nombre unico: `airef_publications_periodic_check`;
- `enqueueUniquePeriodicWork`;
- periodicidad aproximada: 30 minutos;
- periodicidad configurable desde la app: 15, 30, 60 o 120 minutos;
- red requerida: `NetworkType.CONNECTED`;
- bateria: `requiresBatteryNotLow(true)`;
- no exige Wi-Fi;
- no exige carga;
- no usa `AlarmManager`;
- no usa foreground service;
- no usa wake locks manuales;
- no usa WebView ni navegador oculto.

Android puede retrasar las comprobaciones para ahorrar bateria. La periodicidad de 30 minutos es aproximada, no exacta.

## Red

En cada comprobacion:

- hace una sola peticion HTTP al HTML;
- no descarga imagenes, fuentes, JavaScript, CSS ni PDF;
- usa OkHttp con timeout razonable;
- usa compresion HTTP;
- envia un User-Agent identificable;
- respeta HTTPS y redirecciones;
- usa `ETag` / `If-None-Match` si existen;
- usa `Last-Modified` / `If-Modified-Since` si existen;
- ante HTTP 304 registra una comprobacion correcta sin parsear;
- limita el tamano maximo de respuesta;
- conserva el estado anterior ante errores de red, HTTP invalido o parsing anomalos.

## TLS en airef.es

La web de AIReF usa un certificado emitido por FNMT. En algunos dispositivos Android la comprobacion puede fallar con:

```text
Trust anchor for certification path not found
```

La app mantiene validacion TLS estricta y no acepta certificados invalidos. Para `airef.es` se incluye la CA intermedia publica `AC Componentes Informaticos` de FNMT en `res/raw/fnmt_ac_componentes_informaticos.pem`, limitada mediante `network_security_config.xml` al dominio `airef.es` y sus subdominios. Para cualquier otra URL configurada por el usuario se usan las autoridades del sistema Android.

## Arquitectura

```text
app/src/main/java/es/personal/avisosairef/
  data/network       Cliente HTTP y resultados de red
  data/parser        Parser Jsoup, normalizacion y modelos
  data/storage       DataStore y estado persistente
  data/repository    Coordinacion de comprobaciones y novedades
  worker             WorkManager periodico y manual
  notifications      Canal y notificaciones Android
  ui                 Pantalla Compose, ViewModel y tema
```

## Versiones principales

- Android Gradle Plugin: 8.13.2
- Version de la app: 1.0.1
- Kotlin: 2.3.21
- Compose BOM: 2026.06.01
- WorkManager: 2.11.2
- DataStore: 1.2.1
- OkHttp: 5.4.0
- Jsoup: 1.22.2
- minSdk: 26
- compileSdk: 36
- applicationId: `es.personal.avisosairef`

## Compilar y verificar

```powershell
.\gradlew.bat clean test lint assembleRelease
```

Verificacion realizada antes de generar el APK:

- compilacion desde cero: correcta;
- tests unitarios: correctos;
- lint: correcto;
- APK release firmado: generado;
- manifiesto revisado;
- permisos revisados;
- comprobado que solo hay un trabajo periodico unico;
- comprobado que los cambios de URL/frecuencia reprograman ese trabajo unico;
- comprobado con fixtures que otro apartado no genera novedades;
- comprobado con fixtures que un enlace nuevo del apartado objetivo si genera novedad;
- comprobado que la primera sincronizacion no notifica;
- comprobado que un error de red no borra el estado anterior;
- comprobado que una lista vacia inesperada no se acepta como valida.

No habia emulador ni telefono conectado por ADB en el entorno de construccion, asi que no se ha probado instalacion real en dispositivo.

## APK

APK principal:

```text
dist/AvisosAIReF.apk
```

Tamano:

```text
9.63 MB
```

SHA-256:

```text
5AA26626C87E209BDF9ED044BDCDA3795940B61F59CE3F3EC49C6F6237EF355A
```

Para recalcularlo:

```powershell
Get-FileHash .\dist\AvisosAIReF.apk -Algorithm SHA256
```

## Instalacion directa

1. Copia `dist/AvisosAIReF.apk` al movil.
2. Abre el APK desde Archivos, Drive, correo o la app que uses.
3. Permite temporalmente "Instalar aplicaciones desconocidas" para esa app.
4. Instala el APK.
5. Retira despues ese permiso si procede.
6. Abre "Avisos AIReF".
7. Concede el permiso de notificaciones si Android lo solicita.
8. Activa "Monitorizacion activa".
9. Pulsa "Comprobar ahora" para crear la referencia inicial.

## Instalacion mediante ADB

```powershell
adb install -r dist/AvisosAIReF.apk
```

Para actualizar posteriormente sin perder datos locales:

1. Mantener el mismo `applicationId`: `es.personal.avisosairef`.
2. Firmar con la misma clave release local.
3. Instalar con:

```powershell
adb install -r dist/AvisosAIReF.apk
```

## Firma local

El APK de `dist/` se genera como release firmado con una clave local de uso personal.

Conserva la carpeta local `signing/`, especialmente:

- `avisosairef-release.jks`;
- `signing.properties`;
- `KEY_PASSWORD.txt`.

Android no permite actualizar una app instalada con otra firma. La carpeta `signing/` esta ignorada por Git y no debe subirse al repositorio.

## Privacidad y permisos

Permisos declarados:

- `INTERNET`;
- `ACCESS_NETWORK_STATE`;
- `POST_NOTIFICATIONS`.

No se solicitan permisos de ubicacion, archivos, contactos, accesibilidad, bateria sin restricciones ni inicio automatico propietario.

## Motorola Edge 40

Si observas retrasos excesivos, revisa Ajustes > Bateria > uso de bateria por app > Avisos AIReF y deja permitida la actividad en segundo plano. No hace falta desactivar por completo las optimizaciones de bateria: WorkManager esta pensado para convivir con ellas, aunque Android puede aplazar las comprobaciones.
