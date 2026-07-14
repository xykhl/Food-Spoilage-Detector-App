# Graph Report - .  (2026-07-14)

## Corpus Check
- Corpus is ~8,970 words - fits in a single context window. You may not need a graph.

## Summary
- 158 nodes · 210 edges · 26 communities (16 shown, 10 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 16 edges (avg confidence: 0.8)
- Token cost: 230,183 input · 0 output

## Community Hubs (Navigation)
- Sensor Data Model
- App Navigation & Main Activity
- Bluetooth Communication
- History & Sensor Graph UI
- Background Monitoring Service
- Detection Screen UI
- Permission Dialog UI
- Launcher Icon (xxxhdpi round)
- Sensor Parser Tests
- Gradle Wrapper Script
- Instrumented Test
- Launcher Icon (xxhdpi round)
- Launcher Icon (xxxhdpi)
- Unit Test
- Launcher Icon (mdpi)
- Launcher Icon (xhdpi round)
- Launcher Icon (hdpi)
- Launcher Icon (Round hdpi)
- Launcher Icon (Round mdpi)
- Launcher Icon (xhdpi)
- Launcher Icon (xxhdpi)

## God Nodes (most connected - your core abstractions)
1. `BluetoothService` - 17 edges
2. `DetectionScreen()` - 13 edges
3. `MonitoringService` - 11 edges
4. `FoodSpoilageDetectorApp()` - 9 edges
5. `SensorDataParser` - 8 edges
6. `HistoryScreen()` - 8 edges
7. `SensorGraph()` - 8 edges
8. `SensorReading` - 7 edges
9. `ConnectionStatus` - 6 edges
10. `DetectedFoodCard()` - 6 edges

## Surprising Connections (you probably didn't know these)
- `FoodSpoilageDetectorApp()` --calls--> `PermissionDialog()`  [INFERRED]
  app/src/main/java/com/example/foodspoilagedetector/MainActivity.kt → app/src/main/java/com/example/foodspoilagedetector/ui/components/PermissionDialog.kt
- `FoodSpoilageDetectorApp()` --calls--> `DetectionScreen()`  [INFERRED]
  app/src/main/java/com/example/foodspoilagedetector/MainActivity.kt → app/src/main/java/com/example/foodspoilagedetector/ui/DetectionScreen.kt
- `FoodSpoilageDetectorApp()` --calls--> `HistoryScreen()`  [INFERRED]
  app/src/main/java/com/example/foodspoilagedetector/MainActivity.kt → app/src/main/java/com/example/foodspoilagedetector/ui/HistoryScreen.kt
- `FoodSpoilageDetectorApp()` --references--> `BluetoothService`  [EXTRACTED]
  app/src/main/java/com/example/foodspoilagedetector/MainActivity.kt → app/src/main/java/com/example/foodspoilagedetector/bluetooth/BluetoothService.kt
- `FoodSpoilageDetectorAppPreview()` --calls--> `FoodSpoilageDetectorTheme()`  [INFERRED]
  app/src/main/java/com/example/foodspoilagedetector/MainActivity.kt → app/src/main/java/com/example/foodspoilagedetector/ui/theme/Theme.kt

## Import Cycles
- None detected.

## Communities (26 total, 10 thin omitted)

### Community 0 - "Sensor Data Model"
Cohesion: 0.14
Nodes (15): DetectedFood, Boolean, Context, File, List, Long, Map, String (+7 more)

### Community 1 - "App Navigation & Main Activity"
Cohesion: 0.13
Nodes (18): android, AppDestinations, DETECTION, HISTORY, SETTINGS, FoodSpoilageDetectorApp(), FoodSpoilageDetectorAppPreview(), Boolean (+10 more)

### Community 2 - "Bluetooth Communication"
Cohesion: 0.12
Nodes (13): BluetoothService, ConnectionStatus, CONNECTED, CONNECTING, DISCONNECTED, SCANNING, File, List (+5 more)

### Community 3 - "History & Sensor Graph UI"
Cohesion: 0.18
Nodes (16): SensorReading, formatTime(), List, Long, Modifier, String, SensorGraph(), format() (+8 more)

### Community 4 - "Background Monitoring Service"
Cohesion: 0.18
Nodes (7): Int, String, MonitoringService, IBinder, Intent, Job, Service

### Community 5 - "Detection Screen UI"
Cohesion: 0.27
Nodes (14): createImageUriInDetection(), DetectedFoodCard(), DetectionScreen(), Context, File, List, Map, Modifier (+6 more)

### Community 6 - "Permission Dialog UI"
Cohesion: 0.50
Nodes (4): String, PermissionDialog(), PermissionItem(), ImageVector

### Community 7 - "Launcher Icon (xxxhdpi round)"
Cohesion: 0.67
Nodes (4): Android Robot Head Motif (default Android Studio template glyph), App Launcher Icon (Round, xxxhdpi), Green geometric grid circular background, FoodSpoilageDetector app (placeholder/default branding, not custom-designed)

### Community 9 - "Gradle Wrapper Script"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 11 - "Launcher Icon (xxhdpi round)"
Cohesion: 0.67
Nodes (3): Android Robot Head Silhouette Motif, App Launcher Icon (Round, xxhdpi), Android Studio Default Template Launcher Icon

### Community 12 - "Launcher Icon (xxxhdpi)"
Cohesion: 0.67
Nodes (3): Android robot mascot silhouette (default template graphic element), App Launcher Icon (xxxhdpi, default Android Studio template), FoodSpoilageDetector App

## Knowledge Gaps
- **22 isolated node(s):** `DETECTION`, `HISTORY`, `SETTINGS`, `DISCONNECTED`, `SCANNING` (+17 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `FoodSpoilageDetectorApp()` connect `App Navigation & Main Activity` to `Bluetooth Communication`, `History & Sensor Graph UI`, `Detection Screen UI`, `Permission Dialog UI`?**
  _High betweenness centrality (0.268) - this node is a cross-community bridge._
- **Why does `BluetoothService` connect `Bluetooth Communication` to `App Navigation & Main Activity`, `History & Sensor Graph UI`, `Detection Screen UI`?**
  _High betweenness centrality (0.251) - this node is a cross-community bridge._
- **Why does `SensorReading` connect `History & Sensor Graph UI` to `Sensor Data Model`, `Bluetooth Communication`, `Detection Screen UI`?**
  _High betweenness centrality (0.184) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `DetectionScreen()` (e.g. with `FoodSpoilageDetectorApp()` and `Color`) actually correct?**
  _`DetectionScreen()` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 3 inferred relationships involving `FoodSpoilageDetectorApp()` (e.g. with `PermissionDialog()` and `DetectionScreen()`) actually correct?**
  _`FoodSpoilageDetectorApp()` has 3 INFERRED edges - model-reasoned connections that need verification._
- **What connects `DETECTION`, `HISTORY`, `SETTINGS` to the rest of the system?**
  _22 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Sensor Data Model` be split into smaller, more focused modules?**
  _Cohesion score 0.13666666666666666 - nodes in this community are weakly interconnected._