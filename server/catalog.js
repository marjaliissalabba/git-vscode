'use strict';

/** Lasttyper (spesifikasjonen § 6 «Legg ut last») med kategori, forhåndsvalgte krav og fotoflis (§ 8). */
const LOAD_TYPES = [
  { key: 'krok', name: 'Krokcontainer', category: 'Avfall', requirements: ['avf'], image: '2025/09/Krokcontainer-300x300-ikon.png', equipment: 'Krokbil' },
  { key: 'papp', name: 'Balert papp/plast', category: 'Avfall', requirements: ['avf'], image: '2025/09/Papp-ikon.jpg', equipment: 'Semitrailer' },
  { key: 'metall', name: 'Metall/skrap', category: 'Avfall', requirements: ['avf'], image: '2025/09/Jern_og_metaller-ikon.jpg', equipment: 'Krokbil eller semitrailer' },
  { key: 'farlig', name: 'Farlig avfall', category: 'Avfall · ADR', requirements: ['avf', 'adr'], image: '2025/09/Farlig_avfall-ikon.jpg', equipment: 'ADR-merket bil' },
  { key: 'tre', name: 'Trevirke/flis', category: 'Avfall', requirements: ['avf'], image: '2025/09/Trevirke-ikon.jpg', equipment: 'Semitrailer' },
  { key: 'rest', name: 'Restavfall', category: 'Avfall', requirements: ['avf'], image: '2025/09/Brennbar-rest-ikon.jpg', equipment: 'Krokbil' },
  { key: 'glass', name: 'Glass/metall', category: 'Avfall', requirements: ['avf'], image: '2025/09/Glassemballasje-ikon.jpg', equipment: 'Krokbil' },
  { key: 'bygg', name: 'Byggevarer', category: 'Byggevarer', requirements: [], image: '2025/09/Gips-ikon.jpg', equipment: 'Semitrailer m/ kran' },
  { key: 'bulk', name: 'Bulk', category: 'Bulk', requirements: [], image: '2025/09/Apen-container-300x300-ikon.png', equipment: 'Tippbil' },
  { key: 'gods', name: 'Annet gods', category: 'Gods', requirements: [], image: null, equipment: 'Semitrailer' },
];
const LOAD_TYPE_BY_KEY = Object.fromEntries(LOAD_TYPES.map(t => [t.key, t]));
const CATEGORIES = ['Avfall', 'Avfall · ADR', 'Byggevarer', 'Bulk', 'Gods'];
const EQUIPMENT = ['Krokbil', 'Semitrailer', 'Kran', 'ADR-merket', 'Tippbil', 'Walking floor'];

module.exports = { LOAD_TYPES, LOAD_TYPE_BY_KEY, CATEGORIES, EQUIPMENT };
