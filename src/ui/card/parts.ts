// What a card says, part by part, as the reader set it under Look up.

export interface CardParts {
  /** How the word is said: the strip with its play button, transcription and accent. */
  sound: boolean;
  /** What it means: the word in the reader's language, or what it is where theirs has none. */
  meaning: boolean;
  /** Which kind of word and which form of which word it is. Only with the meaning. */
  grammar: boolean;
  /** The other words the spelling can be. Only with the meaning. */
  others: boolean;
  /** What the word is, said in the reader's language, under every word rather than only under
   *  one their language has no word for or says the same way. */
  describe: boolean;
}

export const EVERY_PART: CardParts = {
  sound: true,
  meaning: true,
  grammar: true,
  others: true,
  describe: false,
};

/** The parts as the settings hold them. */
export function partsOf(settings: {
  showSound: boolean;
  showMeaning: boolean;
  showGrammar: boolean;
  showOthers: boolean;
  describeAll: boolean;
}): CardParts {
  return {
    sound: settings.showSound,
    meaning: settings.showMeaning,
    grammar: settings.showMeaning && settings.showGrammar,
    others: settings.showMeaning && settings.showOthers,
    describe: settings.showMeaning && settings.describeAll,
  };
}
