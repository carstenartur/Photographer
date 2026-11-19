# RGB8-Experiment: nicht übernehmen

Stand: 19. November 2026.

Das Experiment fusioniert Farbmatrix und bestehende Gamma-LUT in einem Durchlauf über geeignete gepackte RGB8-Raster. Allgemeine Raster behalten ihre ursprünglichen Farbmodell-Rundläufe; der optionale Vectorpfad bleibt getrennt. Die Entscheidung lautet: **nicht in den Produktstand übernehmen**. Weitere RGB8-Umbauten werden aus diesem Experiment nicht abgeleitet.

Messgrundlage ist [rgb8-experiment/summary.json](rgb8-experiment/summary.json). Alle **48 Prozesse** wurden erfolgreich abgeschlossen: vier vollständige CR3-Inputs, ein beziehungsweise vier CPUs, jeweils drei frische Prozesse pro Variante, drei Warmups und fünf Messiterationen. Verglichen wurde der vollständige Aufruf `ImageIO.read(File)`. Die Reihenfolge wechselte zwischen den Forks. Zuerst wurde der Median jedes Prozesses gebildet, anschließend der Median dieser Prozessmediane.

Mit einer CPU änderte sich die Laufzeit gegenüber der Baseline bei `sample.cr3` um **+3,20 %**, beim EOS-R-Input um **−2,36 %**, beim G5-X-II-C-RAW um **+12,05 %** und beim M50-C-RAW um **+8,16 %**. Positive Werte bedeuten langsamere Verarbeitung. Beim G5-X-II-C-RAW war der Kandidat in allen drei Forks langsamer. Mit vier CPUs lagen die Verbesserungen nur zwischen **1,20 % und 6,93 %**.

Die bestandenen Tests auf exakte Pixelgleichheit bestätigen die Korrektheit, rechtfertigen aber keine Übernahme ohne konsistenten Laufzeitvorteil. Die deutlichen Regressionen bei einer CPU überwiegen hier die begrenzten Vorteile bei vier CPUs. Der fokussierte RGB16-Runtime-PR bleibt davon getrennt.

Gesichert ist der isolierte Diff von `83a687e7680405b96e0ce35b82802b1f03a3d764` nach `fe5ebd1733374e13dfe9a3efd7c40a042d598014` als [rgb8-rejected.patch](rgb8-rejected.patch).

**Patch-SHA-256:** `9ec20f26d79e221a0463718562b84b6d1ca2e9129f409d49a3afff699ae283ab`
