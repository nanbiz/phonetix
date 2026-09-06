package io.github.nanbiz.phonetix.debug

/** The words the test pages are written with, shared by all of them. */
object TestWords {

    /**
     * Lines in which no word appears twice.
     *
     * A page that repeats itself cannot be measured through a movement: a test pairing
     * a transcription with the one it was a moment ago has several identical candidates
     * to choose between, and picks by position, which is the very thing under test. With
     * every word on the page its own, a transcription is identified by what it says.
     */
    val DISTINCT = listOf(
        "apple bridge candle dolphin ember forest garden hammer",
        "island jacket kettle lantern meadow needle orchard pencil",
        "quiver ribbon saddle tunnel umbrella velvet window yellow",
        "zebra anchor basket copper diamond engine falcon granite",
        "harbour ivory jungle kernel ladder magnet nectar oyster",
        "pillow quartz rocket silver timber violet walnut zephyr",
        "almond blanket cactus dagger eagle fabric glacier helmet",
        "insect jigsaw koala lemon marble noodle ocean parrot",
        "quilt rabbit sapphire trumpet unicorn valley whistle yoghurt",
        "acorn bamboo cinnamon donkey elephant feather gravel hostel",
        "igloo jasmine kayak lilac mustard nutmeg opal pepper",
        "quiche raccoon sandal thistle upright vanilla wagon yarn",
        "azure beetle carrot dandelion emerald flannel goblin hazel",
        "iodine jockey kitten lettuce mango nickel octopus parsley",
        "quarry rhubarb saffron tulip urgent vinegar walrus yeast",
        "abbey burrow cavern dungeon estuary furnace gallery hollow",
        "inlet jetty knoll lagoon marsh notch orchid plateau",
        "quay ravine summit thicket upland vista wharf yonder",
        "anvil bellows chisel drill emery file gauge hinge",
        "ingot joint kiln lever mallet nozzle oiler pulley",
    )

    /**
     * Ordinary German, which the overlay has no dictionary for.
     *
     * Whether a line is in the language the dictionary is for cannot be tested with words
     * chosen to make the point: these are plain sentences of the kind that fill a page a
     * reader is actually looking at, and several of them carry words English has too -
     * "war", "hat", "man", "die", "in", "so" - because those are exactly what made a German
     * page come back covered in English pronunciations.
     */
    val GERMAN = listOf(
        "Die Wiedergabe wurde angehalten und der Titel gespeichert",
        "Er hat die ganze Nacht an dem Lied gearbeitet",
        "Das war ein guter Abend für alle die dabei waren",
        "Man kann die Lautstärke oben rechts wieder ändern",
        "Alle Kommentare zu diesem Video ansehen",
        "Der Sänger schreibt seine Texte meistens selbst",
        "Wir haben die Aufnahme im Studio noch einmal gehört",
        "Sie ist mit dem Zug nach Hause gefahren",
        "Es gibt keinen Grund das jetzt schon zu entscheiden",
        "Diese Einstellung gilt nur für dieses Gerät",
    )
}
