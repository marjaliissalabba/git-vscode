# Plan: Spinning-app med kameralesing, FTP-basert trening og Spotify-spilleliste

**Mål:** En mobilapp som
1. leser telleren på spinnesykkelen med kameraet (RPM, watt, motstand, tid),
2. viser en stor digital teller med **hvor du er** og **hvor du burde ligge**,
3. lager treningsprogram ut fra din FTP og det du faktisk har syklet (i stil med Stages Flight),
4. lager en Spotify-spilleliste der sangene passer intensiteten: raske sanger på høy kadens, roligere på lav, basert på det du allerede liker.

---

## Status (9. nov 2026, versjon 0.4)

| Fase | Status |
|---|---|
| 0. Avklaring | ✅ Stages SC-sykkel med ANT+-konsoll, Galaxy A56 (Android), uten pulsbelte foreløpig |
| 1. Lese data | ✅ Bluetooth, ANT+, kamera og simulator i appen. Kameraleseren leser bildet av konsollet riktig. ⏳ Ikke testet på selve sykkelen |
| 2. Teller + økt | ✅ Rund måler i % av FTP med 8 sonefarger (100 % = oransje), hvit målbue og pil, RPM under, intervalltid og neste intervall, tale og vibrasjon, hopp over oppvarming |
| 3. FTP + treningsmotor | ✅ 7 økter inkl. FTP-test, ukeplan, oppsummering med forslag til ny FTP. ⏳ Mangler lagret historikk |
| Innlogging | ✅ E-post + engangskode (bare e-post lagres). ⏳ Venter på Supabase-oppsett |
| 4. Spotify | ⏳ Ikke startet |
| 5. Tilpasning | ⏳ Ikke startet (FTP-forslag finnes, men ingen historikk ennå) |
| 6. Polering | Delvis: tale og mørkt tema er på plass. Strava-eksport mangler |

**Neste steg, i rekkefølge**
1. Installere versjon 0.4 og prøve en økt med simulatoren
2. Teste på sykkelen: Bluetooth først, så kamera
3. Kjøre FTP-testen for å få riktig FTP
4. Lagre historikk over økter (grunnlaget for tilpasning)
5. Spotify-spilleliste etter kadens og intensitet
6. Sette opp Supabase, så innloggingen slås på

---

## Funn etter bilder av sykkelen (9. nov 2026)

- **Sykkel:** Stages Cycling innendørssykkel (SC-serien) med Stages-konsoll. Konsollet har «LINK HERE – ANT+»-merke.
- **Telefon:** Samsung Galaxy A56 5G (Android). A-serien har normalt **ikke innebygd ANT+**-radio, så ANT+ krever trolig en USB ANT+-pinne. Bluetooth-testen (nRF Connect) avgjør om vi klarer oss uten. **Pulsbelte:** ikke ennå (kommer senere, velg et med både Bluetooth og ANT+).
- **Konsollet viser ekte watt** (fra kraftmåleren i kranken). Vi trenger altså **ikke** estimere watt fra motstand.
- **Felter på displayet** (venstre = nå, høyre = snitt): kJ (punktmatrise øverst), WATTS, RPM, SPEED (km/t), KCAL og KCAL/HR, TIME (t:mm:ss), DIST (km).
  Alt unntatt kJ er 7-segment-tall. Oppsettet er fast, så vi kan ha en **ferdig mal for Stages-konsollet** og slipper å markere feltene for hånd. Du justerer bare plasseringen.
- **Motstandsnivå vises ikke** (rattet er analogt). Programmet styres derfor på watt og RPM, ikke på motstand.
- **Kameraforhold:** Uten bakgrunnslys er displayet grått med lav kontrast og gjenskinn (bilde 2). Med lyset på (lyspære-knappen) er det skarpt og lett å lese (bilde 1). **Bakgrunnslyset må stå på**, og telefonen må stå rett over displayet, ikke på skrå.

### Ny førsteprioritet: dataene trådløst
Konsollet sender **ANT+** (strøm og kadens), og kraftmåleren kan også sende Bluetooth. Det er langt mer presist enn kamera:
1. Sjekk om telefonen støtter ANT+: installer «ANT Radio Service» og «ANT+ Plugin Sampler»/«ANT Tester» fra Play Butikk. Mange eldre Samsung-telefoner har ANT+ innebygd, nyere ofte ikke. Ellers fungerer en **USB ANT+-pinne med USB-C-adapter** (ca. 300 kr).
2. Sjekk Bluetooth: installer **nRF Connect**, tråkk på sykkelen og se om det dukker opp en enhet som heter «Stages …» med *Cycling Power*-tjeneste.
3. Fungerer én av disse, bruker appen den som hovedkilde. **Kameralesingen blir da reserve** (eller kan droppes), og fase 1 blir mye kortere.

---

## 0. Avklaringer før vi starter (viktig)

| Spørsmål | Hvorfor det betyr noe |
|---|---|
| **Hvilken sykkel/teller er det?** Merke, modell og bilde av displayet. | Mange spinnesykler (Keiser M3i, Schwinn, Stages, Wattbike, nyere modeller) sender data over **Bluetooth** (FTMS/BLE). Da trenger vi ikke kamera i det hele tatt, og det blir mye mer presist. Kamera bør være reserveløsningen. |
| **Viser telleren watt, eller bare RPM og motstandsnivå?** | Uten watt må vi beregne effekt ut fra RPM + motstand (se kalibrering under). |
| **Android eller iPhone?** | Avgjør teknologi og Spotify-SDK. |
| **Har du pulsbelte?** | Puls over Bluetooth gir bedre tilpasning av programmet. Valgfritt. |
| **Spotify Premium?** | Kreves for å styre avspilling fra appen. |

---

## 1. Arkitektur

```
 ┌────────────── Telefon ──────────────┐
 │  Kamera (bak) ──► Bildebehandling   │
 │                   (utsnitt + OCR)   │
 │  BLE (sykkel/puls) ─┐      │        │
 │                     ▼      ▼        │
 │               Datastrøm: rpm, watt, │
 │               motstand, puls, tid   │
 │                     │               │
 │  Treningsmotor ◄────┤               │
 │  (FTP, soner, mål)  ▼               │
 │               Skjerm: digital teller│
 │               + mål-sone + graf     │
 │  Musikkmotor ──► Spotify (spilleliste│
 │                  + avspillingskontroll)
 │  Lokal database (økter, FTP-historikk)
 └─────────────────────────────────────┘
```

**Anbefalt teknologi:** Flutter (én kodebase for Android og iOS)
- Kamera: `camera`-pakken med bildestrøm
- Tekstgjenkjenning: Google ML Kit (on-device) + egen liten TFLite-modell for 7-segment-tall
- Bluetooth: `flutter_blue_plus` (FTMS, Cycling Power, Heart Rate)
- Lagring: SQLite (`drift`)
- Spotify: Web API (spillelister) + Spotify App Remote SDK (avspilling)

Alt kjører lokalt på telefonen, ingen egen server trengs i første versjon.

---

## 2. Lese telleren med kamera

**Montering:** Telefonen festes i holder på styret, foran/over telleren. **Bakkameraet peker mot telleren, skjermen peker mot deg**, så kamera og skjerm brukes samtidig uten konflikt. Appen erstatter i praksis telleren visuelt.

**Steg:**
1. **Oppsett (én gang):** Du tar et bilde og drar rammer rundt hvert felt (RPM, watt, motstand, tid). Appen husker posisjonene.
2. **Stabilisering:** Finn hjørnene på displayet automatisk (kontur-/perspektivkorrigering) slik at små bevegelser i styret ikke ødelegger lesingen.
3. **Gjenkjenning:** Telleren er ofte 7-segment LCD, som vanlig OCR er dårlig på. Bruk:
   - forbehandling (gråtoner, kontrast, terskling, fjerne gjenskinn),
   - en liten egen sifferklassifiserer for 7-segment (trenes på bilder fra din teller, ~10 siffer × noen hundre eksempler),
   - ML Kit som reserve for vanlig tekst.
4. **Plausibilitetsfilter:** RPM kan ikke hoppe fra 85 til 18 på 0,3 sek. Bruk median-/Kalman-filter og forkast umulige verdier. Vis siste gode verdi.
5. **Ytelse:** Analyser 3–5 bilder i sekundet i lav oppløsning. Spar batteri, unngå overoppheting. Hold skjermen på, og anbefal lader i økta.

**Reserve hvis watt mangler:** Kalibreringsøkt der du sykler fast RPM på hvert motstandsnivå. Effekt estimeres som `watt ≈ f(motstand) × rpm` (tabell per sykkel). Merk tydelig i appen at det er et estimat.

**Akseptkriterium MVP:** ≥ 98 % riktige avlesninger over en 30-minutters økt, under 1 sek forsinkelse.

---

## 3. Skjermen under økta

- **Stor digital teller:** RPM og watt i store tall.
- **Målbånd:** «Du burde ligge 85–95 rpm / 180–200 W». Farge: grønn = i sonen, gul = litt utenfor, rød = langt unna.
- **Sonefarge** for hele skjermen (Z1 grå → Z6 rød), som Stages Flight.
- **Profil-graf** over hele økta med markør for hvor du er, og hva som kommer (nedtelling til neste intervall).
- **Lyd/vibrasjon** ved intervallbytte, og talemelding («30 sekunder igjen, opp til 100 rpm»).
- Etter økta: oppsummering (snitt-watt, NP, tid i hver sone, treff-prosent på mål).

---

## 4. Treningsmotor (FTP og faktiske data)

**FTP:**
- FTP-test i appen: 20-minutters test (FTP = 0,95 × snitt-watt) eller rampetest (FTP ≈ 0,75 × beste 1-minutt).
- Automatisk justering: hvis du gjentatte ganger klarer mål med god margin, eller sykler høyere enn forventet på lange drag, foreslår appen ny FTP (du godkjenner).

**Soner (% av FTP):**

| Sone | Watt | Typisk kadens | Bruk |
|---|---|---|---|
| Z1 Restitusjon | < 55 % | 80–90 | Oppvarming/nedtrapping |
| Z2 Utholdenhet | 56–75 % | 85–95 | Grunntrening |
| Z3 Tempo | 76–90 % | 85–95 | Tempo |
| Z4 Terskel | 91–105 % | 80–95 | Terskelintervaller |
| Z5 VO2maks | 106–120 % | 95–110 | Korte harde drag |
| Z6 Anaerob | > 120 % | 100–120 eller tung klatring 60–75 | Sprint/stigning |

**Programgenerering:**
- Du velger mål (f.eks. bedre FTP, kondisjon, vekt), antall økter per uke og varighet.
- Ukeplan med variasjon: rolig, terskel, VO2, lang Z2, + restitusjonsuke hver 4. uke.
- Øktmaler bygges av blokker (oppvarming, intervaller, pauser, klatring/sprint, nedtrapping). Hver blokk har mål for watt **og** kadens.
- **Tilpasning etter faktiske data:** Treningsbelastning (TSS/CTL/ATL), treff-prosent og hvordan økta kjentes (1–10 etter økta). Bommet du mye → lettere neste gang. Ble det for lett → hardere.

---

## 5. Spotify-spilleliste etter intensitet

**Prinsipp:** Sangens tempo (BPM) matches mot planlagt kadens. 90 rpm ↔ 90 BPM eller 180 BPM (dobbelt tempo teller også). Høy intensitet → energiske sanger, rolige deler → roligere sanger.

**Steg:**
1. Logg inn med Spotify (OAuth PKCE).
2. Hent **dine favoritter** som referanse: en valgt spilleliste, «Likte sanger» og toppsanger/artister.
3. Finn tempo per sang.
   ⚠️ Spotify har stengt *Audio Features* (tempo/energi) og *Recommendations* for nye apper (nov. 2024). Tempo må derfor hentes fra en ekstern tjeneste (f.eks. GetSongBPM-API) eller beregnes selv. Svar mellomlagres lokalt.
4. **Matching:** For hver blokk i økta velges sanger med
   - BPM nær målkadensen (eller dobbel),
   - «energinivå» som passer sonen (fra BPM, sjanger og ekstern energi-score),
   - total lengde som dekker blokken (sangskifte ≈ intervallskifte),
   - variasjon (ikke samme artist to ganger på rad).
5. Lag spillelisten i Spotify-kontoen din («Spin – Terskel 45 min – 9. nov»).
6. **Synk under økta** (App Remote SDK): musikken starter når økta starter, pauser når du pauser, og hopper ved behov så sangskifter treffer intervallene.
7. Utvidelse: også forslag til *nye* sanger fra artister du liker, når favorittlista har for få sanger i riktig tempo.

**Merk:** Spotify-apper i utviklermodus er begrenset til noen få testbrukere. Det holder fint for personlig bruk, men en offentlig app krever søknad hos Spotify.

---

## 6. Faser og rekkefølge

| Fase | Innhold | Ferdig når |
|---|---|---|
| **0. Avklaring** (1–2 dager) | Bilde/modell av sykkelen, sjekk Bluetooth, velg plattform | Vet om vi bruker BLE, kamera eller begge |
| **1. Lese-prototyp** (1–2 uker) | Kamera-utsnitt, OCR av RPM/watt, filter, rå visning | Stabil avlesning i 30 min |
| **2. Digital teller + manuell økt** (1 uke) | Stor teller, sonefarger, en håndlaget økt med mål | Du kan sykle en hel økt etter appen |
| **3. FTP + treningsmotor** (2 uker) | FTP-test, soner, øktmaler, ukeplan, lagring og historikk | Appen lager en ukeplan for deg |
| **4. Spotify** (1–2 uker) | Innlogging, hente favoritter, BPM-oppslag, matching, lage spilleliste, avspillingskontroll | Ferdig spilleliste som følger økta |
| **5. Tilpasning** (1–2 uker) | Automatisk FTP-justering, belastning, vurdering etter økt, tilpasning av neste økt | Programmet endrer seg etter dine data |
| **6. Polering** | Talecoach, eksport til Strava (.fit), mørk modus, deling | — |

Estimatene gjelder én utvikler på deltid.

---

## 7. Risikoer og tiltak

| Risiko | Tiltak |
|---|---|
| Gjenskinn eller dårlig lys på LCD | Kontrastforbehandling, vinkel på holderen, reserve via BLE |
| Telefonen rister på styret | Perspektivkorrigering hver frame, solid holder |
| Batteri/varme ved kamera + skjerm | Lav oppløsning, 3–5 fps, lader under økt |
| Sykkelen viser ikke watt | Kalibrert estimat ut fra RPM og motstand |
| Spotify-API-begrensninger (tempo-data stengt) | Ekstern BPM-kilde + lokal cache |
| Usikre FTP-estimater | Alltid be deg bekrefte endringer, ny test hver 6.–8. uke |

---

## 8. Neste konkrete steg

1. Send **bilde av telleren** (gjerne mens du sykler) og **modellnavn** på sykkelen.
2. Si om du har **Android eller iPhone**, og om du har **pulsbelte**.
3. Del gjerne **favorittlista** på Spotify, så kan vi teste tempo-matchingen tidlig.
4. Deretter bygger vi Fase 1: en prototyp som bare leser RPM fra kamera og viser det stort på skjermen.
