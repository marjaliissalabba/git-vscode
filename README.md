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

## Innlogging (e-post + engangskode)

Innlogging er bare e-post og en kode på e-post. Ingen navn og ingen passord. Appen lagrer bare e-postadressen, og treningsdataene ligger på telefonen.
Innloggingen går via [Supabase](https://supabase.com) (gratis nivå). Er den ikke satt opp, virker appen uten innlogging.

Slik slår du den på (én gang):
1. Opprett et gratis prosjekt på supabase.com (velg region EU, f.eks. Frankfurt).
2. **Authentication → Sign In / Providers → Email**: la «Enable Email provider» stå på. «Confirm email» kan stå på.
3. **Authentication → Emails → Templates → «Magic Link»**: bytt innholdet til for eksempel
   `<h2>Din SpinApp-kode</h2><p>Koden din er: <strong>{{ .Token }}</strong></p>`
   og gjør det samme for «Confirm signup». Da får du en sekssifret kode i stedet for en lenke.
4. **Project Settings → API**: kopier *Project URL* og *anon public key*.
5. I GitHub-repoet: **Settings → Secrets and variables → Actions → Variables**: lag `SUPABASE_URL` og `SUPABASE_ANON_KEY` med verdiene over.
6. Kjør bygget på nytt (Actions → «Bygg Android-app» → Run workflow). Den nye APK-en krever innlogging.

Merk: Supabase sin innebygde e-posttjeneste tåler bare noen få e-poster i timen. Det holder for deg selv. For flere brukere kobler du til en egen SMTP-tjeneste (f.eks. Resend eller Brevo) under Authentication → Emails → SMTP.

## Utvikling

- `core/`: ren Kotlin-logikk (sensorparsing, 7-segment-leser, økter og øktmotor) med tester: `./gradlew :core:test`
- `app/`: Android-appen (Jetpack Compose, CameraX). Bygges når Android SDK finnes, og i GitHub Actions.
- ANT+-biblioteket i `app/libs/` er lisensiert under ANT+ Shared Source License (se `app/libs/ANT-LICENSE.txt`).
