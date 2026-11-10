# Lastebørs – transportanbud for anlegget

Lastebørs er en app i Uber-stil for transportlaster som står på anlegget. Anlegget legger ut lastene, og bare **prekvalifiserte transportører** kan gi bud eller ta oppdrag.

Appen er mobilvennlig og kan installeres på hjemskjermen som en PWA.

## Slik fungerer det

**Anlegget (transportleder)**
1. Legger inn en last: fraksjon, vekt, hentested, leveringssted, tidsvindu og hvilke krav transportøren må oppfylle (f.eks. ADR, avfallstransport, krokløft).
2. Velger prisform:
   - **Anbud:** et lukket anbud med frist. Transportørene ser ikke hverandres bud.
   - **⚡ Fastpris:** første prekvalifiserte transportør som trykker «Ta oppdraget» får lasten, som en taxi-bestilling.
3. Prekvalifiserte transportører får varsel straks lasten legges ut.
4. Ser budene sortert på pris, med kvalifikasjonsstatus for hver transportør, og tildeler oppdraget. Vinneren og de andre budgiverne får beskjed.
5. Følger oppdraget gjennom statusene *tildelt → hentet → levert*, med historikk.
6. Godkjenner eller avviser transportører og dokumentasjonen deres.

**Transportør**
1. Registrerer firmaet med organisasjonsnummer og laster opp dokumentasjon (PDF eller bilde).
2. Venter til anlegget har godkjent firmaet og dokumentene.
3. Ser ledige laster. Laster de ikke er kvalifisert for, vises låst med en forklaring på hva som mangler.
4. Gir bud eller tar fastprisoppdrag, og melder fra når lasten er hentet og levert.
5. Får varsel 30 dager før et dokument går ut.

## Prekvalifisering

En transportør kan bare gi bud når alle disse er oppfylt:

| Krav | Når |
|---|---|
| Firmaet er godkjent av anlegget | Alltid |
| **Grunnkrav:** løyve, ansvars-/vareforsikring, skatteattest, HMS-egenerklæring | Alltid. Må være godkjent og ikke utløpt |
| **Lastens krav:** avfallstransportør, ADR/farlig gods, krokløft, kranbil, tankbil/sug | Når lasten krever det |

Serveren sjekker kravene både når et bud legges inn og på nytt når oppdraget tildeles. Hvis en transportør har fått et dokument utløpt etter at budet ble gitt, kan budet ikke vinne. Et dokument som sendes inn på nytt må godkjennes igjen.

Kravtypene ligger i `server/qualification.js` og er enkle å endre.

## Kom i gang

Krever Node.js 22.13 eller nyere. Det er ingen avhengigheter å installere (appen bruker innebygd `node:sqlite`).

```bash
npm start          # http://localhost:3000
npm test           # kjør testene
```

Første gang legges det inn demodata. Passordet er `demo1234` for alle kontoene:

| Konto | Rolle |
|---|---|
| `anlegg@demo.no` | Anlegget / transportleder |
| `nordtrans@demo.no` | Prekvalifisert transportør med ADR |
| `fjordbil@demo.no` | Prekvalifisert uten ADR. Forsikringen utløper snart |
| `nykommer@demo.no` | Registrert, venter på prekvalifisering |

Miljøvariabler: `PORT` (standard 3000), `DB_FILE` (standard `data/lastebors.db`) og `SEED=false` for å slå av demodata.

## Struktur

```
server.js                 oppstart
server/app.js             API: innlogging, laster, bud, tildeling, prekvalifisering, varsler
server/db.js              SQLite-skjema og demodata
server/qualification.js   regler for prekvalifisering (ren logikk, testet)
public/                   mobil web-app (HTML/CSS/JS, PWA)
test/                     API- og regeltester
```

## Før produksjon

- Varsler vises i appen. Push-varsler, SMS og e-post er ikke koblet på ennå.
- Brukere på anlegget opprettes i databasen. Det finnes ingen administrasjon av dem i appen.
- Appen bør kjøres bak HTTPS. Da bør `Secure` legges til på sesjonscookien.
- Oppslag mot Brønnøysundregistrene for organisasjonsnummer og mot løyveregisteret kan automatisere deler av prekvalifiseringen.
