package io.github.nanbiz.phonetix.debug

/**
 * Answers as the core writes them, one per state the card has to draw.
 *
 * Written as the core's own JSON rather than as Kotlin objects, so what is being looked at is
 * what would actually arrive: a state the parser mishandles shows up here rather than in a
 * reader's hands.
 */
object CardSamples {
    val ALL = listOf(
        // A word that joined: one answer, one sense, nothing to disambiguate.
        """{"state":"Entry","spelling":"perro","lemma":null,"pos":"noun",
           "ipa":["ˈpe.ro"],"says":["Hund"],"glosses":["dog"],
           "example":"El perro ladra.","source":"es","target":"de"}""",
        // A form: the lemma is what to memorise and the tapped word is the small part.
        """{"state":"Form","spelling":"perros","lemma":"perro","pos":"noun",
           "ipa":["ˈpe.ro"],"says":["Hund"],"glosses":["dog"],
           "source":"es","target":"de"}""",
        // Several senses: two are shown and the rest are counted.
        """{"state":"Entry","spelling":"banco","lemma":null,"pos":"noun",
           "ipa":["ˈbaŋ.ko"],"says":["Bank"],
           "glosses":["bench","a financial institution","a shoal of fish","a workbench"],
           "source":"es","target":"de"}""",
        // A join that reached two words equally well, which is no dictionary answer at all:
        // the English gloss is the anchor and the engine has not answered yet.
        """{"state":"IpaOnly","spelling":"banco","lemma":null,"pos":"noun",
           "ipa":["ˈbaŋ.ko"],"says":[],"glosses":["bench"],
           "source":"es","target":"de"}""",
        // A machine's answer, labelled.
        """{"state":"Guess","spelling":"ornitorrinco","lemma":null,"pos":"noun",
           "ipa":["oɾ.ni.toˈrin.ko"],"says":["Schnabeltier"],"glosses":["platypus"],
           "source":"es","target":"de"}""",
        // Nothing found, which is one sentence and no empty rows.
        """{"state":"NoPack","spelling":"perro","lemma":null,"pos":null,
           "ipa":[],"says":[],"glosses":[],"source":"es","target":"de"}""",
    )
}
