# Lastbørs Norden

Lastbørs Norden er en app der oppdragsgivere legger ut last som står igjen, og **prekvalifiserte transportører** tar den til **fastpris** (først til mølla) eller gir bud på **lukket anbud**. Den er laget etter produktspesifikasjonen *Lastbørs Norden – produktspesifikasjon for utvikling*.

Appen er en mobil web-app (PWA) som kan installeres på hjemskjermen. Den har én app med to roller, transportør og oppdragsgiver, og man bytter rolle i Profil. I tillegg finnes en admin-rolle for dokumentkontroll og testpanelet.

## Kom i gang

Krever Node.js 22.13 eller nyere. Ingen avhengigheter må installeres.

```bash
npm start      # http://localhost:3000
npm test       # 24 tester: regler, integrasjoner og hele API-flyten
```

Demokontoer (passord `demo1234`):

| Konto | Hva du kan prøve |
|---|---|
| `anlegg@demo.no` | Oppdragsgiver: legg ut last, se bud, tildel |
| `nordtrans@demo.no` | Transportør med alle krav godkjent. Har et tildelt oppdrag som venter på bekreftelse |
| `polar@demo.no` | Transportør uten ADR. Løyvet utløper snart |
| `fjordbil@demo.no` | Transportør med løyve til kontroll. Kan ikke by |
| `admin@demo.no` | Dokumentkontroll, sperring av firma og **testpanel for integrasjoner** |

## Teste uten å installere noe

**GitHub Codespaces** (kjører i nettleseren):
1. Gå til repoet på GitHub og velg branchen med appen.
2. Trykk **Code › Codespaces › Create codespace on …**.
3. Appen starter av seg selv (`npm start`), og port 3000 åpnes i en ny fane.
4. Vil du åpne appen på mobilen: gå til fanen **Ports**, høyreklikk port 3000 og velg **Port Visibility › Public**. Adressen ser slik ut: `https://<navn>-3000.app.github.dev`.

**Render** (fast offentlig adresse):
1. Logg inn på render.com med GitHub.
2. Trykk **New › Blueprint**, velg dette repoet og branchen. Da brukes `render.yaml`.
3. Du får en adresse som `https://lastbors-norden.onrender.com`.

På gratisplanen sovner appen etter 15 minutter uten bruk og bruker da et halvt minutt på å våkne. Databasen nullstilles ved omstart, og demodataene legges inn på nytt.

## Teste koblingene (Brønnøysund m.fl.)

Integrasjonene kjører i **simulert modus** som standard, slik at du kan teste uten nett og uten avtaler.

**Under registrering:** På firmasteget vises en testboks med testnumre. Trykk på et nummer for å se hva som skjer:

| Org.nr | Resultat |
|---|---|
| `920000002` | Aktivt firma (Testfrakt AS) |
| `930000000` | Konkurs. Registrering avvises |
| `940000009` | Under avvikling. Registrering avvises |
| `950000007` | Slettet fra Enhetsregisteret |
| `960000005` | Ikke funnet |
| Andre gyldige org.nr | Et generert testfirma |

**Testpanelet** (logg inn som `admin@demo.no`, fanen *Integrasjoner*):

- Bytt hver kobling mellom **Simulert** og **Ekte API**.
- Velg scenario: *automatisk*, *alltid funnet*, *ikke funnet*, *tidsavbrudd* eller *tjenesten er nede*. Du kan også sette svartiden.
- Trykk **Kjør test** med et org.nr og se svaret (JSON), svartiden og eventuell feilkode.
- **Logg** viser alle kall appen har gjort mot registrene, også de fra registrering og dokumentkontroll.

Scenarioet gjelder hele appen. Sett for eksempel Brønnøysund til *tjenesten er nede* og registrer et nytt firma. Da tilbyr appen å fortsette uten verifisering. Firmaet kan verifiseres senere fra Profil, og en oppdragsgiver kan ikke legge ut last før firmaet er verifisert.

| Kobling | Ekte API | Simulert |
|---|---|---|
| Brønnøysundregistrene (Enhetsregisteret) | Ja, åpent API: `data.brreg.no` | Ja |
| Statens vegvesen – løyveregisteret | Nei, krever avtale | Ja |
| Miljødirektoratet – avfallstransportører | Nei, krever avtale | Ja |

`INTEGRATIONS_MODE=live` starter med ekte Brønnøysund-oppslag som standard.

## Regler som håndheves på serveren

- **Prekvalifisering:** løyve (`lov`) kreves for alt. Avfallstransportør (`avf`) og ADR (`adr`) låser opp flere laster. Et dokument må være godkjent og ikke utløpt. Når et dokument lastes opp på nytt, må det kontrolleres igjen.
- **Fastpris:** «Ta lasten» låses atomisk. Den som kommer for sent, får «Lasten er allerede tatt».
- **Lukket anbud:** transportøren ser antall bud og fristen, men aldri andres beløp. Et bud kan endres før fristen og kan ikke være over makspris. Oppdragsgiver kan tildele når som helst, og laveste bud trenger ikke vinne.
- **Bekreftelse:** vinneren må bekrefte innen `CONFIRM_HOURS` (standard 2 timer). Ved avslag eller utløpt frist kan oppdragsgiver tildele neste budgiver. Avslag telles på firmaprofilen.
- **Varsler i appen:** ny last som matcher kvalifikasjonene, anbudsfrist om 1 time, vunnet eller tapt bud, dokument godkjent, avvist eller utløper om 30 dager, nytt bud, fastprislast tatt, bekreftet eller avslått tildeling, og anbudsfrist ute uten tildeling.

## Konfigurasjon

| Variabel | Standard | Betydning |
|---|---|---|
| `PORT` | `3000` | Port |
| `DB_FILE` | `data/lastbors-norden.db` | SQLite-fil |
| `SEED` | `true` | Legg inn demodata i en tom database |
| `INTEGRATIONS_MODE` | `simulated` | `live` gir ekte Brønnøysund-oppslag |
| `CONFIRM_HOURS` | `2` | Frist for å bekrefte et tildelt anbud |
| `IMAGE_BASE_URL` | `/imagery/` | Hvor fotoflisene (`2025/09/Papp-ikon.jpg` osv.) hentes fra |
| `COOKIE_SECURE` | – | Sett til `true` bak HTTPS |

Fotoflisene fra designsystemet legges i `public/imagery/` med samme sti som i `assets/imagery.json`. Mangler et bilde, viser appen en farget flis med forbokstaver. Kartet bruker Leaflet med kart fra Kartverket.

## Struktur

```
server.js                  oppstart
server/app.js              API (ruter som i spesifikasjonen § 10, under /api)
server/integrations.js     Brønnøysund, Vegvesen og Miljødirektoratet: ekte og simulert, scenarier og logg
server/qualification.js    regler for prekvalifisering
server/catalog.js          lasttyper, kategorier, utstyr
server/places.js           steder med koordinater og avstandsestimat
server/db.js               SQLite-skjema og demodata
public/                    app (HTML/CSS/JS), Lucide-ikoner og Leaflet
test/                      tester
```

## Ikke laget ennå

- BankID/Vipps-innlogging. Innlogging skjer med e-post og passord.
- Push-varsler. Varsler vises i appen og oppdateres hvert 30. sekund.
- Ekte koblinger mot Vegvesen og Miljødirektoratet. De er klare til å kobles på i `server/integrations.js`.
- Varsler filtrert på område, faktisk kjørerute (avstand er luftlinje × 1,3), og flere brukere per firma.
- Alt under spesifikasjonen § 11: gjennomføring med QR, veiing og signatur, faktura og vurderinger.
