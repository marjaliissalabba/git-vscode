# Ekorn – spritepakke

To PNG-ark med transparent bakgrunn og én motoruavhengig JSON-fil.
Dette er genererte animasjonsutkast, ikke ferdig pikseljusterte produksjonssprites.

## Bevegelse på stedet

Hold figurens posisjon (X/Y) fast i prosjektet. Animasjonen bytter bare bilde.
Slå av root motion og unngå kode som flytter figuren ved løp eller klatring.
Ekornet skal se ut som det løper eller klatrer på stedet. Hopp uttrykkes med
sammenkrøpet og utstrakt kropp, bein og hale, uten flytting av spillobjektet.

## Hovedanimasjoner – sprites/animations.png

1774 × 887 piksler, 8 kolonner og 4 rader. Les hver rad fra venstre mot høyre.

| Rad | JSON-navn | Bevegelse | Foreslått hastighet |
|---|---|---|---|
| 1 | slow_run | Rolig løp med korte steg og rolig halebevegelse | 10 bilder/s |
| 2 | fast_run | Raskt løp med ekornets typiske sammentrekking og utstrekking i hopp | 16 bilder/s |
| 3 | climb | Oppreist klatring med vekselvis tak og skyv fra potene | 10 bilder/s |
| 4 | idle | Hvile med små bevegelser, pust og blunk | 6 bilder/s |

Alle fire spilles i løkke. Hastighetene er anbefalte startverdier.

## Overganger – sprites/transitions.png

887 × 1774 piksler, 6 kolonner og 12 rader.
Spill hver overgang én gang fra venstre mot høyre, deretter mål-animasjonen i løkke.
Forslag: 14 bilder/s, omtrent 0,43 sekunder per overgang.

| Rad | Fra → til | Hva bevegelsen viser |
|---|---|---|
| 1 | idle → slow_run | Lener seg frem og begynner rolige steg |
| 2 | slow_run → idle | Kortere steg og rolig oppretting |
| 3 | idle → fast_run | Komprimerer kroppen og strekker ut i et løpesteg |
| 4 | fast_run → idle | Samler beina og finner hvileposisjonen |
| 5 | slow_run → fast_run | Øker steglengden og strekker kroppen |
| 6 | fast_run → slow_run | Samler kroppen og reduserer steglengden |
| 7 | idle → climb | Reiser overkroppen og strekker forpotene opp |
| 8 | climb → idle | Senker overkroppen tilbake til hvile |
| 9 | slow_run → climb | Går fra løpestilling til oppreist klatrepose |
| 10 | climb → slow_run | Vipper tilbake til horisontal kropp og rolige steg |
| 11 | fast_run → climb | Samler kroppen og roterer til klatrepose |
| 12 | climb → fast_run | Roterer ned og strekker kroppen i løpestilling |

JSON-navnene bruker mønsteret `idle_to_slow_run` og tilsvarende for alle par.

## Import i et prosjekt

1. Kopier sprites-mappen og animations.json til prosjektets ressursmappe.
2. Last PNG-ene som RGBA-bilder med alfa/transparens.
3. Bruk rektanglene i `clips[navn].frames` til å velge utsnitt.
4. Spill bilder med klippets `fps`. Ved `loop: true` begynner klippet på nytt.
5. Når et overgangsklipp er ferdig, spill klippet angitt i `next` fra bilde 0.
6. Tegn alle utsnitt med et fast anker ved samme skjermposisjon og samme skala.

Eksempel: idle → idle_to_fast_run (én gang) → fast_run (løkke).
Ved ny tilstand midt i en overgang kan du la overgangen fullføres og deretter
velge neste overgang fra tilstanden den nettopp endte i.

## Kjente begrensninger og finjustering

Arkene er ikke delelige i helt like heltallsruter. JSON bruker derfor avrundede,
proporsjonale grenser: rutene er omtrent 222 × 222 og 148 × 148 piksler.
Dette er en beregnet ruteinndeling; illustrasjonene er ikke manuelt segmentert.

Fast X/Y for spillobjektet garanterer ikke at selve tegningen står pikselstille.
Poseankrene varierer synlig mellom enkelte bilder. JSON-pivoten (0.5, 0.5) er
bare rutens midtpunkt, ikke et målt bekkenanker. For helt rolig avspilling må
hvert bilde finjusteres med en individuell tegneforskyvning, slik at bekkenet
ligger på samme punkt. Gjør dette også mellom hovedklipp og overgangsklipp.
Unngå automatisk sentrering etter silhuetten: halen endrer yttergrensene.

Det finnes også fargede kantartefakter rundt enkelte figurer som bør renses
før endelig bruk. PNG-ene er tatt med uendret. Sømløse løkker og overganger
er ikke kontrollert i en spillmotor. Kontroller utsnitt og anker før publisering.

## Opphav

Utgangspunktet er referansebildet brukeren oppga som CC0. PNG-ene i pakken er
KI-genererte varianter basert på dette. Originalens kilde-URL og lisensbevis var
ikke vedlagt; legg gjerne disse til i prosjektets egen dokumentasjon.
