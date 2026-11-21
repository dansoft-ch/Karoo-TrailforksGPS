# Karoo Fused GPS

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
denn ohne Karoo-App im Vordergrund fragt das Karoo-System GPS nur alle 30 Minuten an. Den GPS-Provider
ersetzt sie nicht: Die Karoo-App und Locus Map holen ihr GPS
direkt und sind nicht betroffen. Die Weiterleitung startet nach dem Einschalten des Karoo automatisch.

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

Der `appops`-Befehl entspricht „Entwickleroptionen → App für simulierte Standorte → Karoo Fused GPS“.

In der App zeigt der Status, ob der Fused-Ersatz aktiv ist und wie viele Positionen weitergeleitet
wurden. Positionen gibt es nur, wenn der Karoo GPS-Empfang hat.

## Rückgängig machen

Schalter in der App aus (entfernt den Fused-Ersatz) oder ganz deinstallieren:

```
adb uninstall ch.dansoft.karoofusedgps
```

Danach läuft „fused“ wieder über Qualcomm, alles ist wie vorher.
