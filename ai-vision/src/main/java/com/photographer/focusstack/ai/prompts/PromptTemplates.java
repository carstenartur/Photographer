package com.photographer.focusstack.ai.prompts;

/**
 * Built-in prompt templates for AI vision analysis.
 * All prompts request JSON responses for reliable parsing.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    public static final String WILDLIFE_CLASSIFY = """
            Analysiere dieses Foto und bestimme das sichtbare Tier.
            Antworte ausschließlich als JSON:
            {
              "primaryLabel": "Artname (deutsch)",
              "alternativeLabels": ["Alternative1", "Alternative2"],
              "confidence": 0.85,
              "summary": "Kurze Beschreibung des Tieres und seines Verhaltens",
              "category": "bird|mammal|reptile|amphibian|other"
            }
            Falls kein Tier sichtbar ist, setze primaryLabel auf "none".
            """;

    public static final String MACRO_CLASSIFY = """
            Analysiere dieses Makro-Foto einer Blüte und bestimme den sichtbaren Bestäuber.
            Unterscheide: Honigbiene, Hummel, Schwebfliege, Wespe, Schmetterling, Käfer, Ameise.
            Antworte ausschließlich als JSON:
            {
              "primaryLabel": "Insektenart (deutsch)",
              "alternativeLabels": ["Alternative1", "Alternative2"],
              "confidence": 0.85,
              "summary": "Beschreibung mit sichtbaren Merkmalen",
              "category": "bee|bumblebee|hoverfly|wasp|butterfly|beetle|ant|other"
            }
            Falls kein Insekt sichtbar ist, setze primaryLabel auf "none".
            """;

    public static final String RANK_BEST = """
            Du siehst %d Fotos derselben Szene. Wähle das beste Foto aus.
            Kriterien: Schärfe, Motivzentrierung, Verdeckung, Belichtung.
            Antworte als JSON: { "bestIndex": 0, "reason": "..." }
            """;

    /** Returns the classification prompt for the given mode. */
    public static String forMode(String mode) {
        return "macro_pollinator".equals(mode) ? MACRO_CLASSIFY : WILDLIFE_CLASSIFY;
    }
}
