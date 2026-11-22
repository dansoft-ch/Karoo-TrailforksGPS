# TrailforksFusedGPS

*English version: [README.md](README.md) (ausführlicher: Problem, Ursache, Lösung)*

Anzeigename auf dem Karoo: **TrailforksFusedGPS** (steht in der App-Liste direkt nach Trailforks). Paketname: `ch.dansoft.karoofusedgps`

Kleine Android-App für den **Hammerhead Karoo 3**: Sie leitet die GPS-Positionen des Karoo an den
Android-Standortdienst **„fused“** weiter, damit nachgeladene Apps wie **Trailforks** den Standort
laufend bekommen.

## Problem

Auf dem Karoo 3 (Android 12) liefert der „fused“-Dienst von Qualcomm (`com.qualcomm.location`) nie eine
Position. Trailforks (Kartenbibliothek Mapbox) hört aber auf „fused“ und übernimmt eine neue Position
nur kurz nach dem Einschalten des Displays. Getestet am 21.11.2026: Mit einem simulierten
„fused“-Standort folgt Trailforks laufend.

## Lösung

Die App ersetzt „fused“ durch einen Test-Provider (Mock-Location) und speist jede echte GPS-Position
1:1 dort ein (Genauigkeit, Höhe, Geschwindigkeit, Richtung). Sie fragt das GPS selbst jede Sekunde an,
denn ohne Karoo-App im Vordergrund fragt das Karoo-System GPS nur alle 30 Minuten an. Auf Wunsch
pausiert diese Anfrage bei ausgeschaltetem Display (Schalter, siehe unten). Den GPS-Provider
ersetzt sie nicht: Die Karoo-App und Locus Map holen ihr GPS
direkt und sind nicht betroffen. Die Weiterleitung startet nach dem Einschalten des Karoo automatisch (LOCKED_BOOT_COMPLETED und BOOT_COMPLETED, mit bis zu drei Versuchen). Jeder Schritt steht im Log: `adb logcat -s KarooFusedGPS`.

## Schalter in der App

Im Karoo-Stil: eingeschaltet schwarzer Knopf, ausgeschaltet hellgrau.

| Schalter | Grundeinstellung | Wirkung |
|---|---|---|
| Weiterleitung GPS → fused | ein | Aus entfernt den Fused-Ersatz, alles wie vorher |
| Autostart beim Einschalten | ein | Aus: Weiterleitung startet erst, wenn man die App öffnet (nach einem App-Update startet sie immer) |
| Auch bei Display aus weiterleiten | ein | GPS-Anfrage läuft auch bei dunklem Display weiter, damit Aufzeichnungen in Trailforks nicht unterbrechen. Aus spart etwas Strom, Trailforks bekommt dann bei dunklem Display keine Positionen |

## Getestet mit

| Komponente | Version |
|---|---|
| Hammerhead Karoo 3 Firmware | 1.665.2548.20 |
| Android | 12 (API 32) |
| Trailforks (Sideload) | 2024.10.1 (versionCode 19168404) |
| TrailforksFusedGPS | 1.4 (Tag `v1.4`), getestet 21.11.2026 |

Ergebnis: Nach einem Neustart startet der Dienst von selbst, Trailforks folgt dem Standort laufend,
Karoo-App und Locus Map sind nicht betroffen. Andere Versionen können sich anders verhalten; mit
`adb shell dumpsys location` lässt sich prüfen, ob Trailforks beim `fused`-Provider angemeldet ist.

## Bauen

GitHub Actions baut bei jedem Push die APK und legt sie als `apk/karoo-fused-gps.apk` ins Repository
(Bot-Commit). Danach `git pull`.

Signiert wird mit dem festen Schlüssel in `keystore/`, damit sich neue Versionen über alte
installieren lassen.

## Auf den Karoo bringen (adb, Karoo per USB, USB-Debugging erlaubt)

```
adb install -r apk/karoo-fused-gps.apk
adb shell pm grant ch.dansoft.karoofusedgps android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ch.dansoft.karoofusedgps android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant ch.dansoft.karoofusedgps android.permission.ACCESS_BACKGROUND_LOCATION
adb shell appops set ch.dansoft.karoofusedgps android:mock_location allow
adb shell am start -n ch.dansoft.karoofusedgps/.MainActivity
```

Der `appops`-Befehl entspricht „Entwickleroptionen → App für simulierte Standorte → TrailforksFusedGPS“.

In der App zeigt der Status, ob der Fused-Ersatz aktiv ist und wie viele Positionen weitergeleitet
wurden. Positionen gibt es nur, wenn der Karoo GPS-Empfang hat.

## Rückgängig machen

Schalter in der App aus (entfernt den Fused-Ersatz) oder ganz deinstallieren:

```
adb uninstall ch.dansoft.karoofusedgps
```

Danach läuft „fused“ wieder über Qualcomm, alles ist wie vorher.
