EKORN – SPRITEPAKKE MED OVERGANGER

Atlas: 384 × 384 px, gjennomsiktig RGBA, 6 kolonner × 6 rader. Hver rute er 64 × 64 px.
Radrekkefølge: idle, run, sprint_bouncy, climb_loop, run_to_climb, climb_over_crest_to_run.

Alle bilder er sentrert på samme faste ankerpunkt i cellen. Overgangssekvensene
gjenbruker eksakte enderammer fra løpe- og klatreanimasjonen. Klatresløyfen går
fram og tilbake gjennom posene 1-2-3-4-3-2, slik at siste ramme går naturlig over
til første.

GIF-forhåndsvisningene er for vurdering. Bruk PNG-atlasen eller de separate
PNG-rammene i appen. Hastighetene er startverdier. FTP/watt/RPM-terskler er ikke
lagt inn i grafikkfilene.

Illustrasjonene er AI-generert med den opplastede ekornfiguren som referanse.
Kildesiden oppgir CC0 for originalressursen; dette bør ikke automatisk forstås som
en juridisk avklaring for den AI-genererte videreutviklingen.


Overgangsstyring
control_rules.json innehåller redigerbara exempelvärden. run_to_climb spelas én gang når
appens regler bytter til klatring; climb_loop går deretter kontinuerlig fram og tilbake.
Når appens regler sier at klatringen er over, spilles climb_over_crest_to_run én gang
før vanlig løping. FTP/RPM alene kan bare gi en visuell heuristikk for bakketopp; bruk
rutehelling eller intervallstatus fra appen dersom slike data finnes.
