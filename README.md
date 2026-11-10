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

## Økter

- **Oppvarming** er ikke en del av øktene. Når du trykker Start, velger du 0, 5, 8, 10 eller 15 min oppvarming (appen husker valget).
- **Lengde:** Velg f.eks. 40 min på en 50-minutters økt. Da blir alle intervallene 20 % kortere (avrundet til 5 s, minst 15 s).
- **Stå opp:** Intervaller kan ha ståperioder. Øktskjermen viser «Gjør deg klar» 10 s før, et stort «STÅ OPP»-banner med nedtelling, og «SITT NED» etterpå, med pip og vibrasjon. Ingen opplesning.
- **Neste intervall ⏭** og **Hopp over oppvarming** på øktskjermen.
- **Øktbygger:** «+ Lag ny økt», eller «Lag egen versjon» av en ferdig økt. Endre navn, tid, % av FTP, kadens og ståperiode per intervall, kopier, flytt og slett. Egne økter lagres bare på telefonen.

## Ekornet og Sprite-studio

- `tools/sprite-studio/index.html` er et redigeringsverktøy for ekornets spritepakke (ankerpunkt og varighet per bilde, løkspeil, forhåndsvisning i appstørrelse). Det lagrer en oppdatert `animations.json`.
- **Flytt, roter, skaler og speilvend** hvert bilde (dra på lerretet eller skriv tall), for ett bilde eller hele klippet. Angre/gjør om med Ctrl+Z. Importen bruker formen når bildene lages.
- **Auto-anker** i studioet: sett ankeret på bilde 1, så følger verktøyet samme punkt gjennom resten av klippet (overganger starter fra løkka de kommer fra). **Finn hakk** markerer bilder som endrer seg mye mer enn resten (rød strek i tidslinja).
- Appen blander hvert bilde mykt over i det neste (kan slås av under Profil → Ekornet).
- Appen bruker bare de fire løkkene (står, løper, løper fort, klatrer). Ved bytte tones forrige løkke ut over den nye; overgangene i pakken tas ikke inn.
- `python3 tools/import_squirrel_sprites.py <mappe>` tar pakken inn i appen: bildene plasseres etter ankerpunktet og varighetene skrives til `assets/squirrel/timing.json`.

## Personvern og lagring

- Ingen konto og ingen innlogging i SpinApp. Spotifys innloggingsbibliotek gir appen internettillatelse, men den brukes bare til Spotify-innloggingen hvis Spotify-appen ikke kan ta den selv. Treningsdata sendes aldri noe sted.
- Øktene lagres bare på telefonen, som en kort oppsummering (ca. 0,3 kB per økt). Ingen data per sekund.
- Gamle økter slettes automatisk (standard: etter 1 år; kan endres til 3 eller 6 måneder). Maks 1000 økter.
- «Historikk og lagring» på forsiden: slett enkeltøkter eller alt.
- Sikkerhetskopi av appdata er slått av.

## Spotify

Appen styrer Spotify-appen på telefonen. Det krever ingen innlogging eller registrering.
- **Tempo-lister:** Lag tre lister i Spotify, «Spin Tung» (under 78 rpm), «Spin Middels» (78–97 rpm) og «Spin Rask» (98+ rpm). Når et hoveddrag starter, spiller appen lista som passer målkadensen. Pauser, oppvarming og sprinter under 1 min beholder sangen.
- **Ny sang inn i hoveddragene:** Når lista allerede passer, byttes sangen ved starten av hardt drag (minst 85 % av FTP, minst 1 min). Aldri oftere enn hvert 45. sekund, og hardt-til-hardt bare etter 3 min.
- Musikken starter og pauser med økta. ⏮ ⏯ ⏭ vises på øktskjermen.
- Trykk «Test» ved hver liste i Musikk-kortet for å sjekke at riktig liste spiller.

## Utvikling

- `core/`: ren Kotlin-logikk (sensorparsing, 7-segment-leser, økter og øktmotor) med tester: `./gradlew :core:test`
- `app/`: Android-appen (Jetpack Compose, CameraX). Bygges når Android SDK finnes, og i GitHub Actions.
- ANT+-biblioteket i `app/libs/` er lisensiert under ANT+ Shared Source License (se `app/libs/ANT-LICENSE.txt`).
