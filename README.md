# SpinApp

Android-app for trening på Stages-spinnesykkel: leser watt og kadens, viser hvor du burde ligge, og lager treningsprogram ut fra FTP. Se [PLAN.md](PLAN.md) for hele planen.

## Installere på telefonen

1. Åpne siden **Releases → «SpinApp – siste utviklingsversjon»** i dette repoet på telefonen.
2. Last ned `app-debug.apk` og åpne den.
3. Tillat installasjon fra nettleseren når Android spør («Installer ukjente apper»).

## Datakilder

| Kilde | Når | Hva du trenger |
|---|---|---|
| **Bluetooth** | Best, hvis kraftmåleren sender Bluetooth | Ingenting ekstra. Velg enheten under «Søk etter Bluetooth-enhet». |
| **ANT+** | Konsollet sender ANT+ | ANT-radio i telefonen *eller* USB ANT+-pinne + appene «ANT Radio Service», «ANT+ Plugins Service» og «ANT USB Service». Galaxy A56 har ikke innebygd ANT+. |
| **Kamera** | Reserve når trådløst ikke virker | Holder på styret, bakkamera mot konsollet, **lyset på konsollet på**. Juster rammen i «Juster kamera». |
| **Simulator** | Prøve appen uten sykkel | Ingenting. |

Prøv kildene i denne rekkefølgen: Bluetooth, så ANT+, så kamera.

## Første gang på sykkelen

1. Velg **Bluetooth** og trykk «Søk etter Bluetooth-enhet» mens du tråkker. Ser du en enhet merket «Watt», velger du den og trykker «Test datakilden».
2. Hvis ikke, velg **Kamera**, trykk «Juster kamera», slå på lyset på konsollet og dra den blå rammen over LCD-glasset. Avlesningen vises nederst.
3. Kjør **FTP-test 20 min** for å få riktig FTP. Appen foreslår ny FTP etter testen.

## Personvern og lagring

- Ingen konto og ingen innlogging. Appen har ikke tilgang til internett.
- Øktene lagres bare på telefonen, som en kort oppsummering (ca. 0,3 kB per økt). Ingen data per sekund.
- Gamle økter slettes automatisk (standard: etter 1 år; kan endres til 3 eller 6 måneder). Maks 1000 økter.
- «Historikk og lagring» på forsiden: slett enkeltøkter eller alt.
- Sikkerhetskopi av appdata er slått av.

## Spotify

Appen styrer Spotify-appen på telefonen med vanlige medieknapper. Det krever ingen innlogging eller registrering.
- Lim inn lenken til en spilleliste (Spotify: ⋯ → Del → Kopier lenke) og trykk «Åpne lista i Spotify».
- Musikken starter og pauser med økta. Du kan velge ny sang ved hvert intervall.
- ⏮ ⏯ ⏭ vises på øktskjermen.

## Utvikling

- `core/`: ren Kotlin-logikk (sensorparsing, 7-segment-leser, økter og øktmotor) med tester: `./gradlew :core:test`
- `app/`: Android-appen (Jetpack Compose, CameraX). Bygges når Android SDK finnes, og i GitHub Actions.
- ANT+-biblioteket i `app/libs/` er lisensiert under ANT+ Shared Source License (se `app/libs/ANT-LICENSE.txt`).
