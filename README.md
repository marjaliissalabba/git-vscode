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

**I en sal med mange sykler:** tråkk, hold telefonen helt inntil konsollet til én enhet merkes «Nærmest deg», trykk på den og sjekk at watt og rpm følger deg. Gi den gjerne et navn (f.eks. «Sykkel 8») – navnet lagres bare på telefonen og vises i lista neste gang.

## Første gang på sykkelen

1. Velg **Bluetooth** og trykk «Søk etter Bluetooth-enhet» mens du tråkker. Ser du en enhet merket «Watt», velger du den og trykker «Test datakilden».
2. Hvis ikke, velg **Kamera**, trykk «Juster kamera», slå på lyset på konsollet og dra den blå rammen over LCD-glasset. Avlesningen vises nederst.
3. Kjør **FTP-test 20 min** for å få riktig FTP. Appen foreslår ny FTP etter testen.

## Økter

- **Oppvarming** er ikke en del av øktene. Når du trykker Start, velger du 0, 5, 8, 10 eller 15 min oppvarming (appen husker valget).
- **Lengde:** Velg f.eks. 40 min på en 50-minutters økt. Da blir alle intervallene 20 % kortere (avrundet til 5 s, minst 15 s).
- **Stå opp:** Intervaller kan ha ståperioder. Intervallruta viser når og hvor lenge («Stå opp om 1:20 · i 1:00»), så «STÅ OPP» med nedtelling og «SITT NED» etterpå. Starter neste intervall stående, står det i «Neste». Ingen opplesning og ingen vibrasjon (telefonen ligger på konsollet).
- **Lyder:** nytt intervall = bip-bip-BIIP, stå opp = fire stigende toner, sitt ned = tre synkende toner. Faller stå opp sammen med et nytt intervall, spilles bare lyden for nytt intervall. Lydene spilles på alarmvolumet og demper musikken kort, så de høres over sangen.
- **Neste intervall ⏭** og **Hopp over oppvarming** på øktskjermen.
- **Liggende visning:** under økta kan telefonen snus. Da står måleren til venstre og intervall, mål og profil til høyre – fint for skjermdeling (Smart View / Cast skjerm) til TV.
- **Øktbygger:** «+ Lag ny økt», eller «Lag egen versjon» av en ferdig økt. Endre navn, tid, % av FTP, kadens og ståperiode per intervall, kopier, flytt og slett. Egne økter lagres bare på telefonen.

## Treningsplaner

Under **Økter → Planer** ligger forskningsbaserte planer. Hver plan viser hva studien fant, forbehold og kilder, og alle planene starter og slutter med FTP-test. Trykk «Start planen», så viser Tren-fanen neste økt i planen og teller fram når du har kjørt den. Øktene finnes også som vanlige økter i lista.

| Plan | Bygger på | Lengde |
|---|---|---|
| NTNU 4×4 | Helgerud m.fl. 2007 (NTNU/CERG) | 8 uker, 3/uke |
| NTNU 1×4 – lite tid | Tjønna m.fl. 2013 (NTNU/CERG) | 10 uker, 3/uke |
| Rønnestad 30/15 | Rønnestad m.fl. 2020 | 4 uker, 3/uke |
| Seiler 4×8 | Seiler m.fl. 2013 | 7 uker, 4/uke |
| Polarisert 80/20 | Stöggl & Sperlich 2014 | 9 uker, 3–4/uke |
| Sprint 2 uker | Gibala-laben (Gist m.fl. 2014) | 2 uker + test |
| Tabata 6 uker | Tabata m.fl. 1996 | 6 uker, 3/uke |

Prosentene av FTP er omregnet fra pulsmålene i studiene. Alle øktene har kadensmål og skifter kadens og watt underveis.

## Figuren ved måleren

- Figurene er fra **Pixel Adventure 1** av Pixel Frog (CC0, se `app/src/main/assets/mascot/LISENS.txt`), pluss en rev fra et eget spriteark. Velg Frosk, Maske, Rosa, Virtuell eller Rev under Profil → Figuren.
- Figuren løper fritt i en boks fra venstre til høyre kant og fra under buen til over buen. Gulvet er under buen. Hopper den mot buen, spretter den av og snur, som mot veggene.
- **Står** ved pause eller lav kadens, **løper** fram og tilbake (farten følger kadensen), **løper fort og hopper** (av og til dobbelthopp) ved høy kadens, og **klatrer** opp veggen ved mye kraft og lav kadens, og hopper ut over kanten på toppen.
- Tersklene og reaksjonstiden justeres under Profil → Figuren.
- Ekorn-verktøyene (`tools/sprite-studio/`, `tools/import_squirrel_sprites.py`) ligger igjen, men brukes ikke av appen nå.

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
