/**
 * IPA Source Comparison Script — Sentence-Based Evaluation
 *
 * Runs curated sentences through every available IPA source and
 * outputs a side-by-side comparison. Each sentence targets specific
 * IPA difficulties for its language.
 *
 * Usage: node scripts/evaluate-ipa.mjs
 */

import { readFileSync, writeFileSync, existsSync, writeSync } from "fs";
import { execSync } from "child_process";
import { fileURLToPath } from "url";
import { dirname, join } from "path";
import { tmpdir } from "os";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, "..");
const DATA_DIR = join(__dirname, "data");

// ─── Test Sentences by Language ──────────────────────────────────────

const TEST_SENTENCES = {
  English: {
    lang: "en",
    espeakAccents: ["en-gb", "en-us", "en-gb-scotland"],
    sentences: [
      {
        text: "I read the book yesterday, but I'll read another one tomorrow.",
        difficulty: "Homograph: read /ɹɛd/ (past) vs /ɹiːd/ (present)",
      },
      {
        text: "The lead singer took the lead in the race.",
        difficulty: "Homograph: lead /liːd/ (verb) vs /lɛd/ (noun/metal)",
      },
      {
        text: "The wind began to wind through the narrow valley.",
        difficulty: "Homograph: wind /wɪnd/ (noun) vs /waɪnd/ (verb)",
      },
      {
        text: "She shed a tear when she saw the tear in her dress.",
        difficulty: "Homograph: tear /tɪə/ (cry) vs /tɛə/ (rip)",
      },
      {
        text: "The bass player caught a bass in the lake.",
        difficulty: "Homograph: bass /beɪs/ (music) vs /bæs/ (fish)",
      },
      {
        text: "They desert their post to cross the desert.",
        difficulty: "Homograph: desert /dɪˈzɜːt/ (verb) vs /ˈdɛzət/ (noun) — stress shift",
      },
      {
        text: "The knight knew the answer through thorough thought.",
        difficulty: "Silent letters (k, gh), -ough variations (through/thorough/thought)",
      },
      {
        text: "She won't accept their excuse; they shouldn't've been absent.",
        difficulty: "Contractions, weak forms, elision",
      },
      {
        text: "The colonel's recipe called for a unique herb and some quinoa.",
        difficulty: "Irregular spellings: colonel=/ˈkɜːnəl/, herb (h silent US), quinoa",
      },
      {
        text: "The photographer's comfortable chauffeur drove through the queue.",
        difficulty: "Unstressed syllables, French loanwords, silent letters",
      },
      {
        text: "I live a good life while watching the live broadcast.",
        difficulty: "Homograph: live /lɪv/ (verb) vs /laɪv/ (adjective)",
      },
      {
        text: "She had to bow her head near the bow of the ship.",
        difficulty: "Homograph: bow /baʊ/ (bend) vs /boʊ/ (front of ship)",
      },
    ],
  },

  German: {
    lang: "de",
    espeakAccents: [],  // phonemizer.js doesn't support German
    sentences: [
      {
        text: "Die Straße durch den Wald führt zum Schloss.",
        difficulty: "ß=/s/, sch=/ʃ/, ss vs ß, vowel length (führt)",
      },
      {
        text: "Ich möchte ein Brötchen mit Käse, bitte.",
        difficulty: "Umlauts: ö=/ø/, ä=/ɛ/, ch after front vowel =/ç/",
      },
      {
        text: "Der Schmetterling fliegt über den Stacheldrahtzaun.",
        difficulty: "Compound nouns, ü=/y/, consonant clusters (Stacheldrahtzaun)",
      },
      {
        text: "Die Geschwindigkeit des Zuges war beeindruckend schnell.",
        difficulty: "Long compound, sch=/ʃ/, ei=/aɪ/, z=/ts/",
      },
      {
        text: "Mein Bruder spricht fließend Französisch und Chinesisch.",
        difficulty: "ie=/iː/ vs ei=/aɪ/, ch before i =/ç/ vs after a =/x/",
      },
      {
        text: "Die Eichhörnchen verstecken sich zwischen den Büschen.",
        difficulty: "örnchen diminutive, Eichhörnchen compound, ü in Büschen",
      },
      {
        text: "Er hat seine Schlüssel in der Küche vergessen.",
        difficulty: "ü=/y/, sch=/ʃ/, ch=/ç/ after front vowel",
      },
      {
        text: "Das Mädchen pflückte Gänseblümchen auf der Wiese.",
        difficulty: "ä=/ɛ/, ü=/y/, pfl- cluster, -chen diminutive /çən/",
      },
      {
        text: "Der Krankenwagen raste durch die Fußgängerzone.",
        difficulty: "Compound nouns with four+ morphemes, ä, z=/ts/",
      },
      {
        text: "Ich brauche eine Entschuldigung für die Verspätung.",
        difficulty: "ent- prefix, sch=/ʃ/, ä=/ɛ/, -ung suffix",
      },
    ],
  },

  Spanish: {
    lang: "es",
    espeakAccents: [],  // phonemizer.js doesn't support Spanish
    sentences: [
      {
        text: "El perro corre rápido por la carretera.",
        difficulty: "rr=/r/ trill vs r=/ɾ/ tap, stress on rápido",
      },
      {
        text: "Los desarrolladores trabajan en proyectos de inteligencia artificial.",
        difficulty: "ll=/ʎ/ or /ʝ/ (regional), rr, stress patterns",
      },
      {
        text: "Mi abuelo fue al hospital porque tenía un resfriado.",
        difficulty: "b/v neutralization, silent h, ía hiatus",
      },
      {
        text: "La universidad ofrece una variedad de cursos interesantes.",
        difficulty: "Vowel hiatus (u-ni-ver-si-dad), d=/ð/ intervocalic",
      },
      {
        text: "El murciélago voló sobre la ciudad durante la noche.",
        difficulty: "ié diphthong, stress shift, ci=/θi/ (Castilian) or /si/ (LatAm)",
      },
      {
        text: "¿Cuándo llegará el avión de Buenos Aires?",
        difficulty: "ll regional variation, nasal vowels, diphthongs",
      },
      {
        text: "Los niños jugaban en la playa mientras sus padres descansaban.",
        difficulty: "ñ=/ɲ/, j=/x/, intervocalic d weakening",
      },
      {
        text: "Mi hermano y yo fuimos al cine a ver una película extranjera.",
        difficulty: "j=/x/, x=/ks/, stress on película (esdrújula)",
      },
      {
        text: "El gobierno anunció nuevas medidas de seguridad ciudadana.",
        difficulty: "g+vowel: go=/ɡo/ vs ge=/xe/, ue diphthong, d=/ð/",
      },
      {
        text: "La señora González preparó una exquisita cena para la fiesta.",
        difficulty: "ñ=/ɲ/, z=/θ/ (Castilian) or /s/ (seseo), x=/ks/",
      },
    ],
  },

  French: {
    lang: "fr",
    espeakAccents: [],  // phonemizer.js doesn't support French
    sentences: [
      {
        text: "Le pain français est vraiment bon, n'est-ce pas?",
        difficulty: "Nasal vowels: ain=/ɛ̃/, an=/ɑ̃/, on=/ɔ̃/, liaison est_un",
      },
      {
        text: "Mon frère habite dans un appartement au cinquième étage.",
        difficulty: "è=/ɛ/, silent consonants, nasal (an/in), -ment",
      },
      {
        text: "Les enfants jouent dans le jardin pendant que leurs parents travaillent.",
        difficulty: "Liaison (les_enfants), nasal vowels, -ent silent in verb",
      },
      {
        text: "Je voudrais un croissant et un café, s'il vous plaît.",
        difficulty: "oi=/wa/, liaison, silent letters (croissant)",
      },
      {
        text: "La bibliothèque nationale possède des manuscrits très anciens.",
        difficulty: "è=/ɛ/, nasal vowels, silent final consonants",
      },
      {
        text: "Ils sont allés aux Champs-Élysées hier soir pour une promenade.",
        difficulty: "Liaison, -ée, nasal (Champs=/ʃɑ̃/), -er infinitive",
      },
      {
        text: "Le professeur a enseigné la linguistique à l'université.",
        difficulty: "gn=/ɲ/, nasal vowels, silent letters, gu=/ɡ/",
      },
      {
        text: "Beaucoup de gens pensent que le français est une langue difficile.",
        difficulty: "eau=/o/, nasal vowels (en/an), gue=/ɡ/, silent endings",
      },
      {
        text: "Ma fille a acheté un beau bouquet de chrysanthèmes jaunes.",
        difficulty: "ill=/ij/, silent letters, è, ch=/k/ (Greek origin), -es silent",
      },
      {
        text: "L'heure d'été commence quand on avance les horloges d'une heure.",
        difficulty: "h muet, liaison, eu=/ø/, -ent silent in verb, elision",
      },
    ],
  },
};

// Build flat word list from all sentences (for dictionary lookups)
function extractWords(text) {
  return text
    .replace(/[.,;:!?¿¡"'()—–\-]/g, " ")
    .split(/\s+/)
    .filter((w) => w.length > 0);
}

// ─── Source Runners ──────────────────────────────────────────────────

const results = {}; // { langName: { sentenceText: { sourceName: result } } }

// 1a. espeak-ng via @echogarden/espeak-ng-emscripten — ALL languages
async function runEspeakFull() {
  console.log("  [1a] espeak-ng FULL (@echogarden, 140 voices)...");
  try {
    const EspeakInit = (await import("@echogarden/espeak-ng-emscripten")).default;
    const m = await EspeakInit();
    const worker = new m.eSpeakNGWorker();

    function textToIPA(text, language) {
      worker.set_voice(language, "");
      const resultPtr = worker.text_to_phonemes(text, 1); // 1 = IPA
      const ptr = resultPtr.ptr;
      const heap = m.HEAPU8;
      let end = ptr;
      while (heap[end] !== 0 && end < ptr + 50000) end++;
      return new TextDecoder("utf-8").decode(heap.slice(ptr, end)).replace(/_/g, "");
    }

    // Test with multiple accents per language
    const langAccents = {
      English: [
        { code: "en", label: "espeak en" },
        { code: "en-gb", label: "espeak en-gb" },
        { code: "en-us", label: "espeak en-us" },
        { code: "en-gb-scotland", label: "espeak en-scotland" },
      ],
      German: [{ code: "de", label: "espeak de" }],
      Spanish: [
        { code: "es", label: "espeak es" },
        { code: "es-419", label: "espeak es-latam" },
      ],
      French: [{ code: "fr", label: "espeak fr" }],
    };

    for (const [langName, accents] of Object.entries(langAccents)) {
      if (!results[langName]) results[langName] = {};
      const sentences = TEST_SENTENCES[langName]?.sentences || [];

      for (const { code, label } of accents) {
        for (const { text } of sentences) {
          if (!results[langName][text]) results[langName][text] = {};
          try {
            const ipa = textToIPA(text, code);
            results[langName][text][label] = ipa;
          } catch (e) {
            results[langName][text][label] = `ERR: ${e.message.slice(0, 80)}`;
          }
        }
      }
    }

    console.log("    Done. All languages processed.");
  } catch (e) {
    console.log(`    SKIP: ${e.message}`);
  }
}

// 1b. espeak-ng via phonemizer.js (Xenova) — English only (for comparison)
async function runEspeakXenova() {
  console.log("  [1b] espeak-ng Xenova (English only, for comparison)...");
  try {
    const { phonemize } = await import("phonemizer");

    for (const { text } of TEST_SENTENCES.English.sentences) {
      if (!results.English[text]) results.English[text] = {};
      try {
        const ipa = await phonemize(text, "en-gb");
        results.English[text]["espeak-xenova en-gb"] = ipa?.[0] || "—";
      } catch (e) {
        results.English[text]["espeak-xenova en-gb"] = `ERR: ${e.message.slice(0, 80)}`;
      }
    }
  } catch (e) {
    console.log(`    SKIP: ${e.message}`);
  }
}

// 2. hans00/phonemize (pure JS rule-based) — English only
async function runHans00() {
  console.log("  [2] hans00/phonemize (pure JS)...");
  try {
    const { toIPA } = await import("phonemize");

    for (const { text } of TEST_SENTENCES.English.sentences) {
      if (!results.English[text]) results.English[text] = {};
      try {
        const words = extractWords(text);
        const ipas = words.map((w) => {
          try { return toIPA(w); } catch { return `[${w}]`; }
        });
        results.English[text]["hans00/phonemize"] = ipas.join(" ");
      } catch {
        results.English[text]["hans00/phonemize"] = "—";
      }
    }
  } catch (e) {
    console.log(`    SKIP: ${e.message}`);
  }
}

// 3. IPA-dict (open-dict-data) — all languages, word-by-word
function runIPADict() {
  console.log("  [3] IPA-dict (word-by-word lookup)...");

  function loadTSV(filename) {
    const filepath = join(DATA_DIR, filename);
    if (!existsSync(filepath)) return null;
    const dict = {};
    for (const line of readFileSync(filepath, "utf8").split("\n")) {
      const tab = line.indexOf("\t");
      if (tab === -1) continue;
      dict[line.slice(0, tab).toLowerCase()] = line.slice(tab + 1).trim();
    }
    return dict;
  }

  const dicts = {
    English: { "IPA-dict en_US": loadTSV("en_US.txt"), "IPA-dict en_UK": loadTSV("en_UK.txt") },
    German: { "IPA-dict de": loadTSV("de.txt") },
    Spanish: { "IPA-dict es": loadTSV("es_ES.txt") },
    French: { "IPA-dict fr": loadTSV("fr_FR.txt") },
  };

  for (const [langName, langData] of Object.entries(TEST_SENTENCES)) {
    if (!results[langName]) results[langName] = {};
    const langDicts = dicts[langName] || {};

    for (const { text } of langData.sentences) {
      if (!results[langName][text]) results[langName][text] = {};
      const words = extractWords(text);

      for (const [dictName, dict] of Object.entries(langDicts)) {
        if (!dict) continue;
        const ipas = words.map((w) => dict[w.toLowerCase()] || `[${w}]`);
        const found = words.filter((w) => dict[w.toLowerCase()]).length;
        results[langName][text][dictName] = `${ipas.join(" ")}  (${found}/${words.length} words)`;
      }
    }
  }
}

// 4. CMU Pronouncing Dictionary — English only, word-by-word
async function runCMUDict() {
  console.log("  [4] CMU Dict (word-by-word)...");
  try {
    const cmuMod = await import("cmu-pronouncing-dictionary");
    const cmudict = cmuMod.dictionary || cmuMod.default?.dictionary || cmuMod;

    for (const { text } of TEST_SENTENCES.English.sentences) {
      if (!results.English[text]) results.English[text] = {};
      const words = extractWords(text);
      const ipas = words.map((w) => {
        const entry = cmudict[w.toLowerCase()];
        return entry || `[${w}]`;
      });
      const found = words.filter((w) => cmudict[w.toLowerCase()]).length;
      results.English[text]["CMU Dict"] = `${ipas.join(" | ")}  (${found}/${words.length})`;
    }
  } catch (e) {
    console.log(`    SKIP: ${e.message}`);
  }
}

// 5. Wiktionary bundled data — word-by-word with POS/regional tags
async function runWiktionaryBundled() {
  console.log("  [5] Wiktionary bundled data (word-by-word)...");

  const langFiles = {
    English: "en.json",
    German: "de.json",
    Spanish: "es.json",
  };

  for (const [langName, filename] of Object.entries(langFiles)) {
    const filepath = join(ROOT, "src/public/languages_without_audio", filename);
    if (!existsSync(filepath)) {
      console.log(`    SKIP: ${filename} not found`);
      continue;
    }

    let raw;
    try {
      raw = JSON.parse(readFileSync(filepath, "utf8"));
    } catch (e) {
      console.log(`    SKIP ${filename}: ${e.message}`);
      continue;
    }

    const langSentences = TEST_SENTENCES[langName]?.sentences || [];
    if (!results[langName]) results[langName] = {};

    for (const { text } of langSentences) {
      if (!results[langName][text]) results[langName][text] = {};
      const words = extractWords(text);

      const ipas = words.map((w) => {
        const entry = raw[w.toLowerCase()] || raw[w];
        if (!entry) return `[${w}]`;
        // Get first IPA from first POS
        for (const posData of Object.values(entry)) {
          if (posData.ipas?.[0]?.ipa) {
            const tags = posData.ipas[0].tags?.join(",") || "";
            return `${posData.ipas[0].ipa}${tags ? `{${tags}}` : ""}`;
          }
        }
        return `[${w}]`;
      });
      const found = words.filter((w) => raw[w.toLowerCase()] || raw[w]).length;
      results[langName][text][`Wikt bundled`] = `${ipas.join(" ")}  (${found}/${words.length})`;
    }
  }
}

// 6. Wiktionary API — full sentences, queries per word (English only to save API calls)
async function runWiktionaryAPI() {
  console.log("  [6] Wiktionary API (live, English only to save rate limit)...");

  // Only query unique English words
  const allEnWords = new Set();
  for (const { text } of TEST_SENTENCES.English.sentences) {
    for (const w of extractWords(text)) allEnWords.add(w.toLowerCase());
  }

  const cache = {};
  let fetched = 0;
  for (const word of allEnWords) {
    try {
      const url = `https://en.wiktionary.org/w/api.php?action=parse&page=${encodeURIComponent(word)}&prop=wikitext&format=json&origin=*`;
      const resp = await fetch(url);
      const data = await resp.json();

      if (data.error) { cache[word] = null; continue; }

      const wikitext = data.parse?.wikitext?.["*"] || "";
      const ipaRegex = /\{\{IPA\|([^}]+)\}\}/g;
      const ipas = [];
      let match;
      while ((match = ipaRegex.exec(wikitext)) !== null) {
        const parts = match[1].split("|");
        for (let i = 1; i < parts.length; i++) {
          const p = parts[i].trim();
          if (p.startsWith("/") || p.startsWith("[") || p.includes("ˈ") || p.includes("ː")) {
            ipas.push(p);
          }
        }
      }
      cache[word] = ipas.length > 0 ? ipas.slice(0, 3).join(", ") : null;
      fetched++;
      if (fetched % 20 === 0) console.log(`    ...fetched ${fetched}/${allEnWords.size} words`);
      await new Promise((r) => setTimeout(r, 80));
    } catch {
      cache[word] = null;
    }
  }

  for (const { text } of TEST_SENTENCES.English.sentences) {
    if (!results.English[text]) results.English[text] = {};
    const words = extractWords(text);
    const ipas = words.map((w) => cache[w.toLowerCase()] || `[${w}]`);
    const found = words.filter((w) => cache[w.toLowerCase()]).length;
    results.English[text]["Wikt API"] = `${ipas.join(" ")}  (${found}/${words.length})`;
  }
}

// 7. Python-based sources
async function runPythonSources() {
  console.log("  [7] Python-based sources (epitran, gruut, deep-phonemizer)...");

  // Build a Python script that processes all sentences
  const allSentences = {};
  for (const [langName, langData] of Object.entries(TEST_SENTENCES)) {
    allSentences[langName] = {
      lang: langData.lang,
      sentences: langData.sentences.map((s) => s.text),
    };
  }

  const pythonScript = `
import json, sys

data = json.loads(open(sys.argv[1]).read())
results = {}

# Epitran (word-by-word, rule-based G2P)
try:
    import epitran
    lang_map = {'en': 'eng-Latn', 'de': 'deu-Latn', 'es': 'spa-Latn', 'fr': 'fra-Latn'}
    results['epitran'] = {}
    for lang_name, lang_data in data.items():
        lang_code = lang_data['lang']
        epi_code = lang_map.get(lang_code)
        if not epi_code:
            continue
        try:
            epi = epitran.Epitran(epi_code)
        except Exception as e:
            results['epitran'][lang_name] = {'error': str(e)}
            continue
        results['epitran'][lang_name] = {}
        for sent in lang_data['sentences']:
            try:
                ipa = epi.transliterate(sent)
                results['epitran'][lang_name][sent] = ipa
            except Exception as e:
                results['epitran'][lang_name][sent] = f'ERR: {e}'
except ImportError as e:
    results['epitran_error'] = str(e)

# Gruut (English only, hybrid dict+rules)
try:
    from gruut import sentences as gruut_sentences
    results['gruut'] = {}
    for sent in data.get('English', {}).get('sentences', []):
        try:
            phonemes = []
            for gruut_sent in gruut_sentences(sent, lang='en-us'):
                for word_obj in gruut_sent:
                    if word_obj.phonemes:
                        phonemes.append(''.join(word_obj.phonemes))
                    elif word_obj.text.strip():
                        phonemes.append(f'[{word_obj.text}]')
            results['gruut'][sent] = ' '.join(phonemes)
        except Exception as e:
            results['gruut'][sent] = f'ERR: {e}'
except ImportError as e:
    results['gruut_error'] = str(e)

# DeepPhonemizer
try:
    from dp.phonemizer import Phonemizer
    import os
    ckpt_paths = [
        os.path.expanduser('~/.cache/dp/en_us_cmudict_ipa_forward.pt'),
        os.path.expanduser('~/.cache/dp/latin_ipa_forward.pt'),
    ]
    ckpt = next((p for p in ckpt_paths if os.path.exists(p)), None)
    if ckpt:
        dp = Phonemizer.from_checkpoint(ckpt)
        results['deep_phonemizer'] = {}
        for sent in data.get('English', {}).get('sentences', []):
            try:
                words = sent.replace(',', ' ').replace('.', ' ').replace(';', ' ').split()
                words = [w for w in words if w]
                ipas = [dp.phonemise_list([w])[0] for w in words]
                results['deep_phonemizer'][sent] = ' '.join(ipas)
            except Exception as e:
                results['deep_phonemizer'][sent] = f'ERR: {e}'
    else:
        results['deep_phonemizer_error'] = 'No checkpoint found'
except ImportError as e:
    results['deep_phonemizer_error'] = str(e)

print(json.dumps(results))
`;

  try {
    const tmpScript = join(tmpdir(), "phonetix-eval-py.py");
    const tmpData = join(tmpdir(), "phonetix-eval-data.json");
    writeFileSync(tmpScript, pythonScript);
    writeFileSync(tmpData, JSON.stringify(allSentences));

    const result = execSync(
      `python3 "${tmpScript}" "${tmpData}"`,
      { encoding: "utf8", timeout: 300000, maxBuffer: 50 * 1024 * 1024 }
    );

    const pyResults = JSON.parse(result.trim());

    // Integrate epitran results
    if (pyResults.epitran) {
      for (const [langName, langResults] of Object.entries(pyResults.epitran)) {
        if (langResults.error) {
          console.log(`    Epitran ${langName}: ${langResults.error}`);
          continue;
        }
        if (!results[langName]) results[langName] = {};
        for (const [sent, ipa] of Object.entries(langResults)) {
          if (!results[langName][sent]) results[langName][sent] = {};
          results[langName][sent]["Epitran"] = ipa;
        }
      }
    }

    // Integrate gruut results
    if (pyResults.gruut) {
      for (const [sent, ipa] of Object.entries(pyResults.gruut)) {
        if (!results.English) results.English = {};
        if (!results.English[sent]) results.English[sent] = {};
        results.English[sent]["Gruut"] = ipa;
      }
    }

    // Integrate deep_phonemizer results
    if (pyResults.deep_phonemizer) {
      for (const [sent, ipa] of Object.entries(pyResults.deep_phonemizer)) {
        if (!results.English) results.English = {};
        if (!results.English[sent]) results.English[sent] = {};
        results.English[sent]["DeepPhonemizer"] = ipa;
      }
    }

    // Log errors
    for (const [key, val] of Object.entries(pyResults)) {
      if (key.endsWith("_error")) console.log(`    ${key}: ${val}`);
    }
  } catch (e) {
    console.log(`    Python failed: ${e.message.slice(0, 200)}`);
  }
}

// ─── Output ──────────────────────────────────────────────────────────

function printResults() {
  console.log(`\n${"═".repeat(100)}`);
  console.log("IPA SOURCE COMPARISON — SENTENCE-BASED EVALUATION");
  console.log(`${"═".repeat(100)}\n`);

  for (const [langName, langData] of Object.entries(TEST_SENTENCES)) {
    console.log(`\n${"━".repeat(100)}`);
    console.log(`  ${langName.toUpperCase()} SENTENCES`);
    console.log(`${"━".repeat(100)}\n`);

    const langResults = results[langName] || {};

    for (let i = 0; i < langData.sentences.length; i++) {
      const { text, difficulty } = langData.sentences[i];
      const sentResults = langResults[text] || {};

      console.log(`  [${i + 1}] "${text}"`);
      console.log(`      Difficulty: ${difficulty}`);
      console.log();

      const sourceNames = Object.keys(sentResults);
      if (sourceNames.length === 0) {
        console.log("      (no sources available for this language)\n");
        continue;
      }

      for (const source of sourceNames) {
        const result = sentResults[source];
        // Wrap long lines
        if (result.length > 90) {
          console.log(`      ${source}:`);
          console.log(`        ${result}`);
        } else {
          console.log(`      ${source.padEnd(22)} ${result}`);
        }
      }
      console.log();
    }
  }

  // Coverage summary per source per language
  console.log(`\n${"═".repeat(100)}`);
  console.log("SOURCE AVAILABILITY SUMMARY");
  console.log(`${"═".repeat(100)}\n`);

  for (const [langName, langData] of Object.entries(TEST_SENTENCES)) {
    const langResults = results[langName] || {};
    const allSources = new Set();
    for (const sentResults of Object.values(langResults)) {
      for (const source of Object.keys(sentResults)) allSources.add(source);
    }

    console.log(`  ${langName}:`);
    for (const source of allSources) {
      const available = langData.sentences.filter(
        (s) => langResults[s.text]?.[source] && !langResults[s.text][source].startsWith("ERR")
      ).length;
      console.log(`    ${source.padEnd(25)} ${available}/${langData.sentences.length} sentences`);
    }
    console.log();
  }
}

// ─── Main ────────────────────────────────────────────────────────────

async function main() {
  const totalSentences = Object.values(TEST_SENTENCES).reduce(
    (sum, l) => sum + l.sentences.length, 0
  );
  console.log(`IPA Sentence-Based Evaluation`);
  console.log(`Testing ${totalSentences} sentences across 4 languages...\n`);

  // Initialize results structure
  for (const [langName, langData] of Object.entries(TEST_SENTENCES)) {
    results[langName] = {};
    for (const { text } of langData.sentences) {
      results[langName][text] = {};
    }
  }

  await runEspeakFull();
  await runEspeakXenova();
  await runHans00();
  runIPADict();
  await runCMUDict();
  await runWiktionaryBundled();
  await runWiktionaryAPI();
  await runPythonSources();

  printResults();

  // Save JSON
  const outputPath = join(DATA_DIR, "evaluation-results.json");
  writeFileSync(
    outputPath,
    JSON.stringify(
      { testSentences: TEST_SENTENCES, results, timestamp: new Date().toISOString() },
      null,
      2
    )
  );
  console.log(`\nRaw results saved to: ${outputPath}`);
}

main().catch(console.error);
