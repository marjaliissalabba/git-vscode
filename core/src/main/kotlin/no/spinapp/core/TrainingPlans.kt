package no.spinapp.core

/** En kilde (studie eller oppsummering) som vises i planen. */
data class PlanSource(val label: String, val url: String)

/**
 * En forskningsbasert treningsplan. [weeks] inneholder øktenes id-er uke for uke, i rekkefølge.
 * Alle øktene finnes også som vanlige økter i [WorkoutLibrary].
 */
data class TrainingPlan(
    val id: String,
    val name: String,
    /** Hvem planen bygger på (forskningsmiljø/forsker). */
    val origin: String,
    /** Kort om hva planen er og hvem den passer for. */
    val summary: String,
    /** Hva studien(e) fant. */
    val research: String,
    /** Forbehold: hvor sikre funnene er, og hvordan planen avviker fra studien. */
    val caveats: String,
    val sources: List<PlanSource>,
    val weeks: List<List<String>>,
) {
    val sessions: List<String> get() = weeks.flatten()
    val weekCount get() = weeks.size
    /** Vanlig antall økter i uka (flest uker). */
    val perWeek get() = weeks.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: 0

    /** Uke (0-basert) og plass i uka for økt nummer [step]. Null når planen er ferdig. */
    fun locate(step: Int): Pair<Int, Int>? {
        var s = step
        weeks.forEachIndexed { w, list ->
            if (s < list.size) return w to s
            s -= list.size
        }
        return null
    }

    fun workoutAt(step: Int): Workout? = sessions.getOrNull(step)?.let { WorkoutLibrary.byId(it) }
}

object TrainingPlans {
    private const val FTP = "ftptest"
    private const val EASY = "grunn45"
    private const val REST = "restitusjon"

    val ntnu4x4 = TrainingPlan(
        id = "plan-ntnu4x4",
        name = "NTNU 4×4",
        origin = "NTNU, CERG (Helgerud, Hoff, Wisløff m.fl.)",
        summary = "8 uker med tre 4×4-økter i uka. Klassikeren fra NTNU for å heve maksimalt oksygenopptak (VO2maks) – «motoren». Starter og slutter med FTP-test.",
        research = "Helgerud m.fl. (2007) sammenlignet fire treningsformer med like mye arbeid, 3 dager i uka i 8 uker. 4×4 min på 90–95 % av makspuls ga omtrent 7 % høyere VO2maks og ca. 10 % større slagvolum i hjertet. Rolig langkjøring og terskeltrening ga ingen signifikant økning.",
        caveats = "Studien styrte etter puls (90–95 % av makspuls). Appen bruker 106–112 % av FTP som tilnærming. Forsøkspersonene var moderat trente unge menn.",
        sources = listOf(
            PlanSource("Helgerud m.fl. 2007, Med Sci Sports Exerc (sammendrag)", "https://coachsci.sdsu.edu/csa/vol131/helgerud.htm"),
            PlanSource("Duke Scholars: Helgerud 2007", "https://scholars.duke.edu/individual/pub719238"),
        ),
        weeks = List(8) { w ->
            when (w) {
                0 -> listOf(FTP, "ntnu4x4", "ntnu4x4")
                7 -> listOf("ntnu4x4", "ntnu4x4", FTP)
                else -> listOf("ntnu4x4", "ntnu4x4", "ntnu4x4")
            }
        },
    )

    val ntnu1x4 = TrainingPlan(
        id = "plan-ntnu1x4",
        name = "NTNU 1×4 – lite tid",
        origin = "NTNU, CERG / K.G. Jebsen-senteret (Tjønna m.fl.)",
        summary = "10 uker med tre korte økter i uka: ett eneste 4-minutters drag. Under 20 min per økt med oppvarming. For travle perioder.",
        research = "Tjønna m.fl. (2013) lot inaktive, overvektige menn trene 3 ganger i uka i 10 uker. Ett 4-minutters drag på 90 % av makspuls ga ca. 10 % høyere VO2maks, mot ca. 13 % med 4×4. Forskjellen var ikke statistisk sikker. Blodtrykk og fastende blodsukker gikk også ned.",
        caveats = "Liten studie (24 personer) på utrente menn. Godt trente får trolig mindre effekt av så lite trening.",
        sources = listOf(
            PlanSource("Tjønna m.fl. 2013, PLoS ONE (PubMed)", "https://pubmed.ncbi.nlm.nih.gov/23734250/"),
            PlanSource("ScienceDaily: korte økter ga effekt", "https://sciencedaily.com/releases/2013/05/130529190821.htm"),
        ),
        weeks = List(10) { w ->
            when (w) {
                0 -> listOf(FTP, "ntnu1x4", "ntnu1x4")
                9 -> listOf("ntnu1x4", "ntnu1x4", FTP)
                else -> listOf("ntnu1x4", "ntnu1x4", "ntnu1x4")
            }
        },
    )

    val ronnestad = TrainingPlan(
        id = "plan-ronnestad",
        name = "Rønnestad 30/15 – rask FTP",
        origin = "Høgskolen i Innlandet (Rønnestad m.fl.)",
        summary = "4 uker der hovedøkta er 30/15-intervaller, ni ganger totalt som i studien. Den mest målrettede planen for å heve FTP raskt.",
        research = "Rønnestad m.fl. (2020) lot godt trente syklister gjøre 9 økter på 3 uker. 3 serier med 13 × 30 s / 15 s ga +4,7 % snitteffekt på 20-minutters test, mens 4 × 5 min med samme innsats ga −1,4 %. Effekten kom på terskelen (effekt ved 4 mmol laktat); VO2maks endret seg ikke.",
        caveats = "Liten studie (9 per gruppe) med svært godt trente syklister rett etter en periode med mye rolig trening. Tre harde økter i uka er tøft – ta en rolig uke om du blir sliten.",
        sources = listOf(
            PlanSource("Rønnestad m.fl. 2020, Scand J Med Sci Sports", "https://zendy.io/title/10.1111/sms.13627"),
            PlanSource("Rønnestad m.fl. 2021: kort vs. lang i én uke", "https://e-space.mmu.ac.uk/630338/1/Ronnestad%202021%20-%20Superior%20Physiological%20Adaptations%20After%20a%20Microcycle%20of%20Short%20Intervals%20Versus%20Long%20Intervals%20in%20Cyclists.pdf"),
        ),
        weeks = listOf(
            listOf(FTP, "ronnestad3015", "ronnestad3015"),
            listOf("ronnestad3015", "ronnestad3015", "ronnestad3015"),
            listOf("ronnestad3015", "ronnestad3015", "ronnestad3015"),
            listOf("ronnestad3015", REST, FTP),
        ),
    )

    val seiler4x8 = TrainingPlan(
        id = "plan-seiler4x8",
        name = "Seiler 4×8 – terskel og VO2maks",
        origin = "Universitetet i Agder (Seiler m.fl.)",
        summary = "7 uker med to 4×8-økter og to rolige økter i uka. Lengre drag rett over terskel.",
        research = "Seiler m.fl. (2013) fordelte 35 syklister på 4×4, 4×8, 4×16 eller bare rolig trening, 2 harde økter i uka i 7 uker. 4×8 ga størst økning i VO2maks, effekt ved VO2maks og effekt ved 4 mmol laktat.",
        caveats = "Omtrent 9 per gruppe. En større studie fra samme miljø (2016, 63 syklister) fant ingen forskjell mellom ulike intervallformer – det viktigste er å trene hardt to ganger i uka.",
        sources = listOf(
            PlanSource("Seiler m.fl. 2013 (sammendrag)", "https://lida.sport-iat.de/ta/Record/4048240?lng=en"),
            PlanSource("Knowledgeiswatt: 4×8 forklart", "https://knowledgeiswatt.substack.com/p/42-hit-long-intervals-4x8-better"),
        ),
        weeks = List(7) { w ->
            when (w) {
                0 -> listOf(FTP, "seiler4x8", EASY, "seiler4x8")
                6 -> listOf("seiler4x8", EASY, "seiler4x8", FTP)
                else -> listOf("seiler4x8", EASY, "seiler4x8", "sone2")
            }
        },
    )

    val polarized = TrainingPlan(
        id = "plan-polarisert",
        name = "Polarisert 80/20",
        origin = "Seiler; Stöggl & Sperlich (Universitetet i Salzburg / Würzburg)",
        summary = "9 uker der det meste er rolig sone 2 og resten er hardt (4×4 og 30/15), nesten ingenting midt imellom. Tredje hver uke er lettere.",
        research = "Stöggl & Sperlich (2014) delte 48 godt trente utholdenhetsutøvere i fire grupper i 9 uker. Polarisert trening ga størst framgang: +11,7 % VO2peak, +17,4 % tid til utmattelse og +5,1 % toppeffekt. Terskeltrening og mye volum ga ikke ytterligere framgang.",
        caveats = "Grupper på ca. 12, og utøverne trente mer i uka enn de fleste mosjonister. Fordelingen 80/20 regnes i tid.",
        sources = listOf(
            PlanSource("Stöggl & Sperlich 2014, Front Physiol (PubMed)", "https://pubmed.ncbi.nlm.nih.gov/24550842/"),
            PlanSource("Full artikkel (Frontiers)", "https://www.frontiersin.org/articles/10.3389/fphys.2014.00033/full"),
        ),
        weeks = List(9) { w ->
            val easyWeek = w % 3 == 2
            val base = if (easyWeek) listOf("sone2", "ntnu4x4", REST) else listOf("sone2", "ntnu4x4", "sone2", "ronnestad3015")
            when (w) {
                0 -> listOf(FTP) + base.drop(1)
                8 -> base.dropLast(1) + FTP
                else -> base
            }
        },
    )

    val sprint = TrainingPlan(
        id = "plan-sprint",
        name = "Sprint 2 uker",
        origin = "McMaster University (Gibala-laben)",
        summary = "6 sprintøkter på 2 uker (4–6 × 30 s alt du har), så FTP-test. Svært lite tid, men svært tungt. Ikke for nybegynnere.",
        research = "Studiene fra Gibala-laben viste at 6 økter på 2 uker med 4–6 × 30 s «all-out» bedret VO2maks, musklenes oksidative kapasitet og utholdenhetsprestasjon. En samleanalyse (Sloth m.fl.) av 13 studier fant 4–13 % høyere VO2maks.",
        caveats = "Studiene brukte ofte Wingate-sykler med fast motstand. Sprintene krever full innsats; vent med denne til du er vant med hard trening.",
        sources = listOf(
            PlanSource("Gist m.fl. 2014, Sports Med (PubMed)", "https://pubmed.ncbi.nlm.nih.gov/24743927/"),
            PlanSource("Sloth m.fl.: meta-analyse", "https://paulogentil.com/pdf/Effects%20of%20sprint%20interval%20training%20on%20VO2max%20and%20aerobic%20exercise%20performance%20-%20A%20systematic%20review%20and%20meta-analysis.pdf"),
        ),
        weeks = listOf(
            listOf(FTP, "sit4", "sit4", "sit5"),
            listOf("sit5", "sit6", "sit6"),
            listOf(REST, FTP),
        ),
    )

    val tabata = TrainingPlan(
        id = "plan-tabata",
        name = "Tabata 6 uker",
        origin = "National Institute of Fitness and Sports, Japan (Tabata m.fl.)",
        summary = "6 uker med to Tabata-økter og én rolig økt i uka. Bygger både VO2maks og evnen til å tåle svært hardt arbeid.",
        research = "Tabata m.fl. (1996) lot skøyteløpere gjøre 8 × 20 s på ca. 170 % av VO2maks med 10 s pause, 5 dager i uka i 6 uker. VO2maks økte ca. 14 % og anaerob kapasitet ca. 28 %. En gruppe med jevn trening fikk lignende VO2maks-økning, men ingen økning i anaerob kapasitet.",
        caveats = "Liten studie på unge idrettsutøvere som trente 5 dager i uka; planen her har 2 Tabata-økter i uka. Tallene i omtaler varierer noe.",
        sources = listOf(
            PlanSource("ACE: Er Tabata så bra som det sies?", "https://www.acefitness.org/prosourcearticle/3743/"),
            PlanSource("Tabata 1996 (japansk sammendrag)", "https://www.jstage.jst.go.jp/article/jspfsm1949/45/5/45_5_495/_article"),
        ),
        weeks = List(6) { w ->
            when (w) {
                0 -> listOf(FTP, "tabata", EASY, "tabata")
                5 -> listOf("tabata", EASY, "tabata", FTP)
                else -> listOf("tabata", EASY, "tabata")
            }
        },
    )

    val all = listOf(ntnu4x4, ntnu1x4, ronnestad, seiler4x8, polarized, sprint, tabata)

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}
