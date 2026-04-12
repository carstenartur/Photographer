package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.repository.SpeciesRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service that normalizes species names across different AI providers and languages.
 *
 * <p>Maintains an in-memory lookup table mapping common/alternate names (German, English,
 * or informal) to a canonical German normalized form. When an AI result arrives with a raw
 * label, this service finds or creates the corresponding {@link SpeciesRecordEntity}.</p>
 */
@Service
public class SpeciesNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(SpeciesNormalizationService.class);

    /**
     * Lookup table: raw/alternate name (lower-case) → [normalizedNameDe, normalizedNameEn, scientificName, category].
     * At least 20 common European bird species and 10 common pollinators are included.
     */
    private static final Map<String, String[]> LOOKUP = Map.ofEntries(
            // Birds — German name variants → [normalizedNameDe, normalizedNameEn, scientificName, category]
            entry("kohlmeise",         "Kohlmeise",        "Great Tit",            "Parus major",             "bird"),
            entry("great tit",         "Kohlmeise",        "Great Tit",            "Parus major",             "bird"),
            entry("blaumeise",         "Blaumeise",        "Blue Tit",             "Cyanistes caeruleus",     "bird"),
            entry("blue tit",          "Blaumeise",        "Blue Tit",             "Cyanistes caeruleus",     "bird"),
            entry("amsel",             "Amsel",            "Common Blackbird",     "Turdus merula",           "bird"),
            entry("blackbird",         "Amsel",            "Common Blackbird",     "Turdus merula",           "bird"),
            entry("common blackbird",  "Amsel",            "Common Blackbird",     "Turdus merula",           "bird"),
            entry("buchfink",          "Buchfink",         "Common Chaffinch",     "Fringilla coelebs",       "bird"),
            entry("chaffinch",         "Buchfink",         "Common Chaffinch",     "Fringilla coelebs",       "bird"),
            entry("common chaffinch",  "Buchfink",         "Common Chaffinch",     "Fringilla coelebs",       "bird"),
            entry("haussperling",      "Haussperling",     "House Sparrow",        "Passer domesticus",       "bird"),
            entry("house sparrow",     "Haussperling",     "House Sparrow",        "Passer domesticus",       "bird"),
            entry("spatzen",           "Haussperling",     "House Sparrow",        "Passer domesticus",       "bird"),
            entry("spatz",             "Haussperling",     "House Sparrow",        "Passer domesticus",       "bird"),
            entry("rotkehlchen",       "Rotkehlchen",      "European Robin",       "Erithacus rubecula",      "bird"),
            entry("robin",             "Rotkehlchen",      "European Robin",       "Erithacus rubecula",      "bird"),
            entry("european robin",    "Rotkehlchen",      "European Robin",       "Erithacus rubecula",      "bird"),
            entry("star",              "Star",             "Common Starling",      "Sturnus vulgaris",        "bird"),
            entry("starling",          "Star",             "Common Starling",      "Sturnus vulgaris",        "bird"),
            entry("common starling",   "Star",             "Common Starling",      "Sturnus vulgaris",        "bird"),
            entry("elster",            "Elster",           "Eurasian Magpie",      "Pica pica",               "bird"),
            entry("magpie",            "Elster",           "Eurasian Magpie",      "Pica pica",               "bird"),
            entry("eurasian magpie",   "Elster",           "Eurasian Magpie",      "Pica pica",               "bird"),
            entry("rabenkrähe",        "Rabenkrähe",       "Carrion Crow",         "Corvus corone",           "bird"),
            entry("krähe",             "Rabenkrähe",       "Carrion Crow",         "Corvus corone",           "bird"),
            entry("carrion crow",      "Rabenkrähe",       "Carrion Crow",         "Corvus corone",           "bird"),
            entry("grünfink",          "Grünfink",         "European Greenfinch",  "Chloris chloris",         "bird"),
            entry("greenfinch",        "Grünfink",         "European Greenfinch",  "Chloris chloris",         "bird"),
            entry("hausrotschwanz",    "Hausrotschwanz",   "Common Redstart",      "Phoenicurus ochruros",    "bird"),
            entry("redstart",          "Hausrotschwanz",   "Common Redstart",      "Phoenicurus ochruros",    "bird"),
            entry("mehlschwalbe",      "Mehlschwalbe",     "Common House Martin",  "Delichon urbicum",        "bird"),
            entry("house martin",      "Mehlschwalbe",     "Common House Martin",  "Delichon urbicum",        "bird"),
            entry("rauchschwalbe",     "Rauchschwalbe",    "Barn Swallow",         "Hirundo rustica",         "bird"),
            entry("barn swallow",      "Rauchschwalbe",    "Barn Swallow",         "Hirundo rustica",         "bird"),
            entry("swallow",           "Rauchschwalbe",    "Barn Swallow",         "Hirundo rustica",         "bird"),
            entry("kleiber",           "Kleiber",          "Eurasian Nuthatch",    "Sitta europaea",          "bird"),
            entry("nuthatch",          "Kleiber",          "Eurasian Nuthatch",    "Sitta europaea",          "bird"),
            entry("buntspecht",        "Buntspecht",       "Great Spotted Woodpecker", "Dendrocopos major",   "bird"),
            entry("great spotted woodpecker", "Buntspecht", "Great Spotted Woodpecker", "Dendrocopos major",  "bird"),
            entry("zaunkönig",         "Zaunkönig",        "Eurasian Wren",        "Troglodytes troglodytes","bird"),
            entry("wren",              "Zaunkönig",        "Eurasian Wren",        "Troglodytes troglodytes","bird"),
            entry("eurasian wren",     "Zaunkönig",        "Eurasian Wren",        "Troglodytes troglodytes","bird"),
            // Pollinators
            entry("honigbiene",        "Honigbiene",       "Honey Bee",            "Apis mellifera",          "bee"),
            entry("biene",             "Honigbiene",       "Honey Bee",            "Apis mellifera",          "bee"),
            entry("honey bee",         "Honigbiene",       "Honey Bee",            "Apis mellifera",          "bee"),
            entry("bee",               "Honigbiene",       "Honey Bee",            "Apis mellifera",          "bee"),
            entry("hummel",            "Erdhummel",        "Buff-tailed Bumblebee","Bombus terrestris",       "bumblebee"),
            entry("bumblebee",         "Erdhummel",        "Buff-tailed Bumblebee","Bombus terrestris",       "bumblebee"),
            entry("erdhummel",         "Erdhummel",        "Buff-tailed Bumblebee","Bombus terrestris",       "bumblebee"),
            entry("schwebfliege",      "Schwebfliege",     "Hoverfly",             "Syrphidae",               "hoverfly"),
            entry("hoverfly",          "Schwebfliege",     "Hoverfly",             "Syrphidae",               "hoverfly"),
            entry("wespe",             "Gemeine Wespe",    "Common Wasp",          "Vespula vulgaris",        "wasp"),
            entry("wasp",              "Gemeine Wespe",    "Common Wasp",          "Vespula vulgaris",        "wasp"),
            entry("common wasp",       "Gemeine Wespe",    "Common Wasp",          "Vespula vulgaris",        "wasp"),
            entry("tagpfauenauge",     "Tagpfauenauge",    "Peacock Butterfly",    "Aglais io",               "butterfly"),
            entry("peacock butterfly", "Tagpfauenauge",    "Peacock Butterfly",    "Aglais io",               "butterfly"),
            entry("admiral",           "Admiral",          "Red Admiral",          "Vanessa atalanta",        "butterfly"),
            entry("red admiral",       "Admiral",          "Red Admiral",          "Vanessa atalanta",        "butterfly"),
            entry("zitronenfalter",    "Zitronenfalter",   "Brimstone",            "Gonepteryx rhamni",       "butterfly"),
            entry("brimstone",         "Zitronenfalter",   "Brimstone",            "Gonepteryx rhamni",       "butterfly"),
            entry("marienkäfer",       "Marienkäfer",      "Ladybird",             "Coccinellidae",           "beetle"),
            entry("ladybird",          "Marienkäfer",      "Ladybird",             "Coccinellidae",           "beetle"),
            entry("ladybug",           "Marienkäfer",      "Ladybird",             "Coccinellidae",           "beetle")
    );

    private final SpeciesRecordRepository speciesRepository;

    public SpeciesNormalizationService(SpeciesRecordRepository speciesRepository) {
        this.speciesRepository = speciesRepository;
    }

    /**
     * Normalizes a raw AI label to a canonical German species name.
     *
     * @param rawLabel the raw label from an AI provider (may be in German, English, or informal)
     * @return the canonical German name, or the original {@code rawLabel} if not found
     */
    public String normalizeSpeciesName(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return rawLabel;
        }
        String[] entry = LOOKUP.get(rawLabel.toLowerCase().trim());
        return entry != null ? entry[0] : rawLabel;
    }

    /**
     * Finds or creates a {@link SpeciesRecordEntity} for the given raw label.
     *
     * <p>The raw label is normalized first. If a record with the normalized name already
     * exists, it is returned (and its sightings counter incremented). Otherwise, a new
     * record is created and persisted.</p>
     *
     * @param rawLabel raw AI label
     * @param category fallback category if the species is not in the lookup table
     * @return the persisted (or updated) species record
     */
    @Transactional
    public SpeciesRecordEntity getOrCreateSpecies(String rawLabel, String category) {
        String normalized = normalizeSpeciesName(rawLabel);
        return speciesRepository.findByNormalizedNameDe(normalized)
                .map(record -> {
                    record.setLastSeen(java.time.Instant.now());
                    record.incrementSightings();
                    return speciesRepository.save(record);
                })
                .orElseGet(() -> {
                    SpeciesRecordEntity record = new SpeciesRecordEntity(normalized, category);
                    // Populate English/scientific names from lookup table if available
                    String[] meta = LOOKUP.get(rawLabel.toLowerCase().trim());
                    if (meta != null) {
                        record.setNormalizedNameEn(meta[1]);
                        record.setScientificName(meta[2]);
                    }
                    log.info("Created new species record: '{}' ({})", normalized, category);
                    return speciesRepository.save(record);
                });
    }

    // ---- helpers ----

    private static Map.Entry<String, String[]> entry(String key, String de, String en, String sci, String cat) {
        return Map.entry(key, new String[]{de, en, sci, cat});
    }
}
