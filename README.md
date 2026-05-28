# AirPods Battery & Proximity (Android)

Aplicación Android nativa en Kotlin + Jetpack Compose Material 3 que lee la
batería de los AirPods (especialmente AirPods Pro 2 Lightning, model id
`0x1420`) directamente de los advertisements BLE del Continuity Protocol de
Apple, y estima la proximidad por RSSI para ayudarte a encontrar unos AirPods
extraviados cerca tuyo.

## Características

- **Foreground Service** tipo `connectedDevice` (compatible con Android 14/15)
  que mantiene un BLE scan en segundo plano sin caer por las restricciones de
  background.
- **Parser del Continuity Protocol** (`AirPodsParser.kt`): decodifica batería
  L/R/estuche, estados de carga y posición de la tapa desde la manufacturer
  data Apple (`0x004C`, subtype `0x07`).
- **Notificación persistente** con `RemoteViews` y los tres porcentajes,
  actualizada en tiempo real.
- **Proximidad por RSSI** con modelo log-distance + suavizado EMA y buckets
  (Touching / Near / Close / Far / Lost).
- **UI Compose Material 3** con dynamic color, edge-to-edge, y locale
  `en` + `es`.
- `BLUETOOTH_SCAN` con `usesPermissionFlags="neverForLocation"` para evitar
  pedir GPS.

## Build local

Requisitos:

- JDK 17 o 21
- Android SDK con platform 35 y build-tools 35.0.0
- Compatible con Android 12 (API 31) en adelante

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Para instalarlo:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

O abriendo el proyecto en Android Studio (Hedgehog o más nuevo) y usando
**Build → Build APK(s)**.

## Cómo funciona

Apple emite un paquete "proximity pairing" durante ~30 segundos cada vez que
abrís la tapa del estuche cerca del teléfono. Los 27 bytes (después del
manufacturer ID Apple) contienen:

| Offset | Significado |
|--------|-------------|
| 0      | `0x07` subtype proximity pairing |
| 1      | `0x19` length |
| 3-4    | model id big-endian (Pro 2 Lightning = `0x1420`) |
| 5      | status (bit `0x20` = primary pod flipped) |
| 6      | high nibble batería primario · low nibble secundario |
| 7      | low nibble batería estuche · high nibble flags de carga |
| 8      | contador de aperturas de tapa (impar = abierto) |

Valores 0–10 se mapean a 0–100 %; `0xF` indica "desconocido".

## Estructura

```
app/src/main/kotlin/com/airpods/app/
├── App.kt
├── MainActivity.kt
├── ble/
│   ├── AirPodsBleService.kt     # Foreground service + scan loop
│   ├── AirPodsParser.kt         # Continuity protocol decoder
│   ├── AirPodsModel.kt          # Model IDs enum
│   ├── AirPodsRepository.kt     # StateFlow singleton
│   ├── AirPodsState.kt          # Data classes
│   ├── ProximityCalculator.kt   # RSSI -> distance + bucket
│   └── BootReceiver.kt          # Restart service on boot
├── notification/
│   └── BatteryNotificationManager.kt
└── ui/
    ├── theme/
    └── dashboard/
```

## Limitaciones conocidas

- Apple solo broadcastea la batería **al abrir la tapa**. Una vez que la
  tapa se cierra, la app pierde la lectura hasta que se vuelve a abrir.
- Algunos OEMs (Xiaomi, OPPO, Samsung) matan agresivamente foreground services.
  Hay que whitelistar la app en **Battery optimization → Don't optimize**.
- La proximidad por RSSI es relativa: con `txPower = -59 dBm` y `n = 2.7`
  los buckets son útiles, pero la distancia absoluta tiene ±30% de error.
