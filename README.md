# QuickDiskScan

<img src="src/main/resources/de/schrell/quickdiskscan/icon.png" alt="QuickDiskScan-Logo" width="180">

QuickDiskScan ist eine native JavaFX-Anwendung, die sehr schnell sichtbar macht, wo Speicherplatz
belegt wird. Bedienung und technische Grundstruktur lehnen sich an das Parallelprojekt QuickDiff an;
Sunburst, Volume-Auswahl, Navigation und Dateiaktionen decken den Arbeitsablauf von SQDisk ab.

## Das kann die App

- komplette Volumes oder einzelne Verzeichnisse parallel scannen
- während des Scans Eintragszahl, Rate, Größen und Fehler live anzeigen
- Speicherbelegung als zoombaren Sunburst und sortierte Dateiliste untersuchen
- die gesamte Anzeige zwischen **virtueller/logischer Größe** und **physisch belegten Blöcken**
  umschalten
- Offline-/Cloud-Platzhalter erkennen, ohne deren Dateiinhalt zu lesen
- nicht in eingehängte Fremd-Volumes oder symbolische Verzeichnislinks hineinlaufen
- Dateien und Ordner im System-Dateimanager anzeigen
- mehrere Einträge auswählen und nach Bestätigung per Button, Kontextmenü, Entf-Taste oder
  Drag-and-drop in die Löschzone entfernen
- lokale und externe Volumes unter Windows, Linux und macOS erkennen
- deutsche oder englische Oberfläche automatisch anhand der Systemsprache verwenden

## Größen und Cloud-Dateien

Der Scanner liest ausschließlich Verzeichniseinträge und Metadaten. Er öffnet keinen Dateiinhalt und
löst daher keinen Download von OneDrive-, iCloud-, Google-Drive- oder SharePoint-Platzhaltern aus.

- macOS/Linux: `st_size` ist die logische Größe, `st_blocks × 512` die physische Größe. Auf macOS
  wird zusätzlich `SF_DATALESS` ausgewertet.
- Windows: normale Dateien verwenden `FILE_STANDARD_INFO.AllocationSize`; komprimierte und
  Sparse-Dateien verwenden `GetCompressedFileSizeW`. Zusätzlich werden `OFFLINE` und die
  `RECALL_*`-Attribute ausgewertet.
- Eine nichtleere Datei mit physischer Größe 0 wird wie gewünscht als **Offline** angezeigt. Auf
  Unix kann dies auch eine vollständig unallozierte Sparse-Datei betreffen, weil es dafür kein
  allgemeines, anbieterübergreifendes Cloud-Flag gibt.

## Parallelisierung

Ein einstellbarer `ForkJoinPool` scannt unabhängige Verzeichnisbäume gleichzeitig. Der Standardwert
ist auf 16 beziehungsweise die Zahl verfügbarer Prozessoren begrenzt. SSDs und Cloud-Metadaten
profitieren meist von 8–16 Workern; bei rotierenden Festplatten kann ein kleinerer Wert schneller
sein. Die Oberfläche wird nur viermal pro Sekunde aktualisiert und erhält keine Ereignisflut pro
Datei.

## Bauen und starten

Benötigt werden JDK 25, JavaFX 25 und ein C-Compiler. Wie QuickDiff verwendet das Projekt bewusst
kein zusätzliches Build-Framework.

macOS/Linux:

```bash
./build.sh
./quickdiskscan
# oder direkt einen Pfad scannen
./quickdiskscan /pfad/zum/verzeichnis
```

Unter Windows in einer normalen PowerShell:

```powershell
.\build.ps1
.\dist\QuickDiskScan\QuickDiskScan.exe
```

`build.ps1` verwendet eine bereits aktive `cl.exe`-Umgebung oder findet Visual Studio/Build Tools
selbständig. Installiert sein müssen die C++-Werkzeuge für die Architektur des verwendeten JDKs.

`JAVAFX_HOME` kann auf das JavaFX-SDK zeigen. Alternativ verwenden die Skripte vorhandene
JavaFX-25-Artefakte aus dem lokalen Gradle-Cache. Das Ergebnis ist jeweils ein selbständiges
App-Image unter `dist/QuickDiskScan` beziehungsweise `dist/QuickDiskScan.app`.

## Sicherheit

Löschen ist dauerhaft und wird immer in einem Bestätigungsdialog angekündigt. Symbolische Links
werden nicht verfolgt. Nicht lesbare Einträge werden gezählt; ein einzelner Berechtigungsfehler
bricht den übrigen Scan nicht ab.
