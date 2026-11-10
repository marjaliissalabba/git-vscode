'use strict';

/**
 * Innebygd stedsliste for kart og avstandsestimat (spesifikasjonen § 4: fromGeo/toGeo, distanceKm).
 * Koordinatene er omtrentlige bysentre. Avstanden er luftlinje × veifaktor, til en
 * kartintegrasjon (Kartverket/Mapbox/Google) gir faktisk kjørerute.
 */
const PLACES = {
  // Norge
  'Oslo': [59.913, 10.752], 'Drammen': [59.744, 10.204], 'Fredrikstad': [59.218, 10.930], 'Sarpsborg': [59.284, 11.110],
  'Moss': [59.434, 10.658], 'Hamar': [60.795, 11.068], 'Lillehammer': [61.115, 10.466], 'Gjøvik': [60.795, 10.692],
  'Kongsberg': [59.668, 9.650], 'Tønsberg': [59.267, 10.408], 'Sandefjord': [59.131, 10.217], 'Larvik': [59.053, 10.027],
  'Skien': [59.209, 9.609], 'Porsgrunn': [59.141, 9.656], 'Arendal': [58.461, 8.772], 'Kristiansand': [58.146, 7.996],
  'Stavanger': [58.970, 5.733], 'Sandnes': [58.852, 5.735], 'Haugesund': [59.414, 5.268], 'Bergen': [60.392, 5.324],
  'Førde': [61.452, 5.857], 'Ålesund': [62.472, 6.155], 'Molde': [62.737, 7.161], 'Kristiansund': [63.110, 7.728],
  'Trondheim': [63.430, 10.395], 'Steinkjer': [64.015, 11.495], 'Namsos': [64.467, 11.495], 'Mosjøen': [65.837, 13.190],
  'Mo i Rana': [66.313, 14.142], 'Bodø': [67.280, 14.405], 'Fauske': [67.259, 15.393], 'Narvik': [68.438, 17.427],
  'Harstad': [68.798, 16.541], 'Sortland': [68.697, 15.413], 'Finnsnes': [69.230, 17.981], 'Tromsø': [69.649, 18.956],
  'Alta': [69.969, 23.272], 'Hammerfest': [70.663, 23.682], 'Honningsvåg': [70.982, 25.970], 'Lakselv': [70.051, 24.971],
  'Karasjok': [69.472, 25.511], 'Gassanjarga': [70.300, 28.000], 'Tana': [70.197, 28.190], 'Vadsø': [70.074, 29.749],
  'Vardø': [70.371, 31.110], 'Kirkenes': [69.727, 30.045], 'Kautokeino': [69.012, 23.042],
  // Sverige
  'Stockholm': [59.329, 18.069], 'Göteborg': [57.709, 11.975], 'Malmö': [55.605, 13.004], 'Uppsala': [59.859, 17.639],
  'Sundsvall': [62.391, 17.307], 'Umeå': [63.826, 20.263], 'Luleå': [65.584, 22.155], 'Kiruna': [67.856, 20.225],
  'Haparanda': [65.836, 24.137],
  // Finland
  'Helsingfors': [60.170, 24.938], 'Tampere': [61.498, 23.761], 'Oulu': [65.012, 25.465], 'Rovaniemi': [66.503, 25.729],
  'Ivalo': [68.658, 27.540],
  // Danmark
  'København': [55.676, 12.568], 'Aarhus': [56.163, 10.204], 'Aalborg': [57.048, 9.919], 'Odense': [55.403, 10.402],
};

const ROAD_FACTOR = 1.3;

function findPlace(name) {
  if (!name) return null;
  const n = String(name).trim().toLowerCase();
  const key = Object.keys(PLACES).find(k => k.toLowerCase() === n);
  return key ? { name: key, geo: PLACES[key] } : null;
}

function haversineKm([lat1, lng1], [lat2, lng2]) {
  const rad = d => (d * Math.PI) / 180;
  const a = Math.sin(rad(lat2 - lat1) / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lng2 - lng1) / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(a));
}

/** Estimert kjøreavstand i hele km, eller null hvis et av stedene er ukjent. */
function estimateDistance(fromGeo, toGeo) {
  if (!fromGeo || !toGeo) return null;
  return Math.max(1, Math.round(haversineKm(fromGeo, toGeo) * ROAD_FACTOR));
}

module.exports = { PLACES, findPlace, estimateDistance, haversineKm };
