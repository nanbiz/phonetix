var background = function() {
  "use strict";
  var _a, _b;
  function defineBackground(arg) {
    if (arg == null || typeof arg === "function") return { main: arg };
    return arg;
  }
  var _MatchPattern = class {
    constructor(matchPattern) {
      if (matchPattern === "<all_urls>") {
        this.isAllUrls = true;
        this.protocolMatches = [..._MatchPattern.PROTOCOLS];
        this.hostnameMatch = "*";
        this.pathnameMatch = "*";
      } else {
        const groups = /(.*):\/\/(.*?)(\/.*)/.exec(matchPattern);
        if (groups == null)
          throw new InvalidMatchPattern(matchPattern, "Incorrect format");
        const [_, protocol, hostname, pathname] = groups;
        validateProtocol(matchPattern, protocol);
        validateHostname(matchPattern, hostname);
        this.protocolMatches = protocol === "*" ? ["http", "https"] : [protocol];
        this.hostnameMatch = hostname;
        this.pathnameMatch = pathname;
      }
    }
    includes(url) {
      if (this.isAllUrls)
        return true;
      const u = typeof url === "string" ? new URL(url) : url instanceof Location ? new URL(url.href) : url;
      return !!this.protocolMatches.find((protocol) => {
        if (protocol === "http")
          return this.isHttpMatch(u);
        if (protocol === "https")
          return this.isHttpsMatch(u);
        if (protocol === "file")
          return this.isFileMatch(u);
        if (protocol === "ftp")
          return this.isFtpMatch(u);
        if (protocol === "urn")
          return this.isUrnMatch(u);
      });
    }
    isHttpMatch(url) {
      return url.protocol === "http:" && this.isHostPathMatch(url);
    }
    isHttpsMatch(url) {
      return url.protocol === "https:" && this.isHostPathMatch(url);
    }
    isHostPathMatch(url) {
      if (!this.hostnameMatch || !this.pathnameMatch)
        return false;
      const hostnameMatchRegexs = [
        this.convertPatternToRegex(this.hostnameMatch),
        this.convertPatternToRegex(this.hostnameMatch.replace(/^\*\./, ""))
      ];
      const pathnameMatchRegex = this.convertPatternToRegex(this.pathnameMatch);
      return !!hostnameMatchRegexs.find((regex) => regex.test(url.hostname)) && pathnameMatchRegex.test(url.pathname);
    }
    isFileMatch(url) {
      throw Error("Not implemented: file:// pattern matching. Open a PR to add support");
    }
    isFtpMatch(url) {
      throw Error("Not implemented: ftp:// pattern matching. Open a PR to add support");
    }
    isUrnMatch(url) {
      throw Error("Not implemented: urn:// pattern matching. Open a PR to add support");
    }
    convertPatternToRegex(pattern) {
      const escaped = this.escapeForRegex(pattern);
      const starsReplaced = escaped.replace(/\\\*/g, ".*");
      return RegExp(`^${starsReplaced}$`);
    }
    escapeForRegex(string) {
      return string.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    }
  };
  var MatchPattern = _MatchPattern;
  MatchPattern.PROTOCOLS = ["http", "https", "file", "ftp", "urn"];
  var InvalidMatchPattern = class extends Error {
    constructor(matchPattern, reason) {
      super(`Invalid match pattern "${matchPattern}": ${reason}`);
    }
  };
  function validateProtocol(matchPattern, protocol) {
    if (!MatchPattern.PROTOCOLS.includes(protocol) && protocol !== "*")
      throw new InvalidMatchPattern(
        matchPattern,
        `${protocol} not a valid protocol (${MatchPattern.PROTOCOLS.join(", ")})`
      );
  }
  function validateHostname(matchPattern, hostname) {
    if (hostname.includes(":"))
      throw new InvalidMatchPattern(matchPattern, `Hostname cannot include a port`);
    if (hostname.includes("*") && hostname.length > 1 && !hostname.startsWith("*."))
      throw new InvalidMatchPattern(
        matchPattern,
        `If using a wildcard (*), it must go at the start of the hostname`
      );
  }
  const browser = (
    // @ts-expect-error
    ((_b = (_a = globalThis.browser) == null ? void 0 : _a.runtime) == null ? void 0 : _b.id) == null ? globalThis.chrome : (
      // @ts-expect-error
      globalThis.browser
    )
  );
  const avgScore = {
    am: 0.0661,
    ar: 0.0237,
    az: 0.0269,
    be: 0.0227,
    bg: 0.0234,
    bn: 0.1373,
    ca: 0.0246,
    cs: 0.0242,
    da: 0.0277,
    de: 0.0275,
    el: 0.0369,
    en: 0.0378,
    es: 0.0252,
    et: 0.0253,
    eu: 0.0369,
    fa: 0.0213,
    fi: 0.026,
    fr: 0.0253,
    gu: 0.1197,
    he: 0.0402,
    hi: 0.0578,
    hr: 0.0201,
    hu: 0.0208,
    hy: 0.0439,
    is: 0.032,
    it: 0.0251,
    ja: 0.0375,
    ka: 0.1383,
    kn: 0.1305,
    ko: 0.0222,
    ku: 0.0256,
    lo: 0.3488,
    lt: 0.0246,
    lv: 0.0264,
    ml: 0.1322,
    mr: 0.0571,
    ms: 0.0251,
    nl: 0.0342,
    no: 0.0266,
    or: 0.1269,
    pa: 0.1338,
    pl: 0.0275,
    pt: 0.0252,
    ro: 0.0247,
    ru: 0.0184,
    sk: 0.024,
    sl: 0.0253,
    sq: 0.0353,
    sr: 0.0234,
    sv: 0.033,
    ta: 0.1513,
    te: 0.1547,
    th: 0.0882,
    tl: 0.0368,
    tr: 0.0258,
    uk: 0.0206,
    ur: 0.0282,
    vi: 0.0467,
    yo: 0.0329,
    zh: 0.0152
  };
  const languageData = {
    langCodes: {},
    langScore: [],
    ngrams: {},
    type: "",
    avgScore
  };
  async function loadNgrams(size) {
    return import("./ngrams/ngrams" + size + "60.js").then((module) => {
      setNgrams(module.ngramsData);
      if (languageData.type) {
        return true;
      }
    });
  }
  function setNgrams(data) {
    languageData.langCodes = data.languages;
    languageData.langScore = Array(Object.keys(data.languages).length).fill(0);
    languageData.ngrams = data.ngrams;
    languageData.type = data.type;
  }
  const unicodeRegex = {
    "L": {
      "alias": "Letter",
      "bmp": "A-Za-zªµºÀ-ÖØ-öø-ˁˆ-ˑˠ-ˤˬˮͰ-ʹͶͷͺ-ͽͿΆΈ-ΊΌΎ-ΡΣ-ϵϷ-ҁҊ-ԯԱ-Ֆՙՠ-ֈא-תׯ-ײؠ-يٮٯٱ-ۓەۥۦۮۯۺ-ۼۿܐܒ-ܯݍ-ޥޱߊ-ߪߴߵߺࠀ-ࠕࠚࠤࠨࡀ-ࡘࡠ-ࡪࡰ-ࢇࢉ-ࢎࢠ-ࣉऄ-हऽॐक़-ॡॱ-ঀঅ-ঌএঐও-নপ-রলশ-হঽৎড়ঢ়য়-ৡৰৱৼਅ-ਊਏਐਓ-ਨਪ-ਰਲਲ਼ਵਸ਼ਸਹਖ਼-ੜਫ਼ੲ-ੴઅ-ઍએ-ઑઓ-નપ-રલળવ-હઽૐૠૡૹଅ-ଌଏଐଓ-ନପ-ରଲଳଵ-ହଽଡ଼ଢ଼ୟ-ୡୱஃஅ-ஊஎ-ஐஒ-கஙசஜஞடணதந-பம-ஹௐఅ-ఌఎ-ఐఒ-నప-హఽౘ-ౚౝౠౡಀಅ-ಌಎ-ಐಒ-ನಪ-ಳವ-ಹಽೝೞೠೡೱೲഄ-ഌഎ-ഐഒ-ഺഽൎൔ-ൖൟ-ൡൺ-ൿඅ-ඖක-නඳ-රලව-ෆก-ะาำเ-ๆກຂຄຆ-ຊຌ-ຣລວ-ະາຳຽເ-ໄໆໜ-ໟༀཀ-ཇཉ-ཬྈ-ྌက-ဪဿၐ-ၕၚ-ၝၡၥၦၮ-ၰၵ-ႁႎႠ-ჅჇჍა-ჺჼ-ቈቊ-ቍቐ-ቖቘቚ-ቝበ-ኈኊ-ኍነ-ኰኲ-ኵኸ-ኾዀዂ-ዅወ-ዖዘ-ጐጒ-ጕጘ-ፚᎀ-ᎏᎠ-Ᏽᏸ-ᏽᐁ-ᙬᙯ-ᙿᚁ-ᚚᚠ-ᛪᛱ-ᛸᜀ-ᜑᜟ-ᜱᝀ-ᝑᝠ-ᝬᝮ-ᝰក-ឳៗៜᠠ-ᡸᢀ-ᢄᢇ-ᢨᢪᢰ-ᣵᤀ-ᤞᥐ-ᥭᥰ-ᥴᦀ-ᦫᦰ-ᧉᨀ-ᨖᨠ-ᩔᪧᬅ-ᬳᭅ-ᭌᮃ-ᮠᮮᮯᮺ-ᯥᰀ-ᰣᱍ-ᱏᱚ-ᱽᲀ-ᲈᲐ-ᲺᲽ-Ჿᳩ-ᳬᳮ-ᳳᳵᳶᳺᴀ-ᶿḀ-ἕἘ-Ἕἠ-ὅὈ-Ὅὐ-ὗὙὛὝὟ-ώᾀ-ᾴᾶ-ᾼιῂ-ῄῆ-ῌῐ-ΐῖ-Ίῠ-Ῥῲ-ῴῶ-ῼⁱⁿₐ-ₜℂℇℊ-ℓℕℙ-ℝℤΩℨK-ℭℯ-ℹℼ-ℿⅅ-ⅉⅎↃↄⰀ-ⳤⳫ-ⳮⳲⳳⴀ-ⴥⴧⴭⴰ-ⵧⵯⶀ-ⶖⶠ-ⶦⶨ-ⶮⶰ-ⶶⶸ-ⶾⷀ-ⷆⷈ-ⷎⷐ-ⷖⷘ-ⷞⸯ々〆〱-〵〻〼ぁ-ゖゝ-ゟァ-ヺー-ヿㄅ-ㄯㄱ-ㆎㆠ-ㆿㇰ-ㇿ㐀-䶿一-ꒌꓐ-ꓽꔀ-ꘌꘐ-ꘟꘪꘫꙀ-ꙮꙿ-ꚝꚠ-ꛥꜗ-ꜟꜢ-ꞈꞋ-ꟊꟐꟑꟓꟕ-ꟙꟲ-ꠁꠃ-ꠅꠇ-ꠊꠌ-ꠢꡀ-ꡳꢂ-ꢳꣲ-ꣷꣻꣽꣾꤊ-ꤥꤰ-ꥆꥠ-ꥼꦄ-ꦲꧏꧠ-ꧤꧦ-ꧯꧺ-ꧾꨀ-ꨨꩀ-ꩂꩄ-ꩋꩠ-ꩶꩺꩾ-ꪯꪱꪵꪶꪹ-ꪽꫀꫂꫛ-ꫝꫠ-ꫪꫲ-ꫴꬁ-ꬆꬉ-ꬎꬑ-ꬖꬠ-ꬦꬨ-ꬮꬰ-ꭚꭜ-ꭩꭰ-ꯢ가-힣ힰ-ퟆퟋ-ퟻ豈-舘並-龎ﬀ-ﬆﬓ-ﬗיִײַ-ﬨשׁ-זּטּ-לּמּנּסּףּפּצּ-ﮱﯓ-ﴽﵐ-ﶏﶒ-ﷇﷰ-ﷻﹰ-ﹴﹶ-ﻼＡ-Ｚａ-ｚｦ-ﾾￂ-ￇￊ-ￏￒ-ￗￚ-ￜ"
    }
  };
  const separators = new RegExp("[^" + unicodeRegex.L.bmp + "]+(?<![\\x27\\x60\\u2019])", "gu");
  const matchDomains = new RegExp("([A-Za-z0-9-]+.)+com(/S*|[^" + unicodeRegex.L.bmp + "])", "g");
  const dictionary = [
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    "'",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    "`",
    "a",
    "b",
    "c",
    "d",
    "e",
    "f",
    "g",
    "h",
    "i",
    "j",
    "k",
    "l",
    "m",
    "n",
    "o",
    "p",
    "q",
    "r",
    "s",
    "t",
    "u",
    "v",
    "w",
    "x",
    "y",
    "z",
    " ",
    " ",
    " ",
    " ",
    " ",
    "M",
    "2",
    "R",
    "J",
    "O",
    "P",
    "{",
    "ä",
    ">",
    "â",
    "ü",
    "é",
    "_",
    "Q",
    "á",
    "ô",
    "ë",
    "å",
    "õ",
    "è",
    "ï",
    "Z",
    "û",
    "}",
    "à",
    "3",
    "ù",
    "É",
    "Y",
    "î",
    "í",
    "]",
    "|",
    ")",
    "ÿ",
    "~",
    "1",
    "V",
    "D",
    "T",
    "4",
    "8",
    "F",
    "I",
    "K",
    "7",
    "W",
    "S",
    "/",
    "E",
    "B",
    "5",
    ";",
    "N",
    "C",
    "ê",
    "*",
    "X",
    "=",
    "^",
    ":",
    "[",
    "H",
    "ò",
    " ",
    " ",
    "¢",
    "!",
    "(",
    ",",
    "ß",
    " ",
    "ø",
    "ó",
    " ",
    " ",
    " ",
    " ",
    "U",
    "ö",
    "6",
    "@",
    "À",
    "Á",
    " ",
    "<",
    "ý",
    "G",
    "-",
    "A",
    "ñ",
    "ú",
    " ",
    " ",
    " ",
    " ",
    "$",
    "L",
    "æ",
    "?",
    "0",
    '"',
    "#",
    "%",
    "&",
    "+",
    "ì",
    "9",
    ".",
    "ç",
    " ",
    "µ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " ",
    " "
  ];
  function isoLanguages(languageSet, defaultLanguages) {
    let languageCodes = {};
    for (let langID of languageSet) {
      languageCodes[langID] = defaultLanguages[langID];
    }
    return languageCodes;
  }
  class LanguageResult {
    /**
     * Creates an instance of LanguageResult.
     *
     * @param {string} language
     * @param {Object} results
     * @param {number} numNgrams
     * @param {Object} langCodes
     * @memberof LanguageResult
     */
    constructor(language, results, numNgrams, langCodes) {
      this.language = language;
      this.getScores = () => getScores(results, langCodes);
      this.isReliable = () => isReliable(results, numNgrams, language);
    }
  }
  function isReliable(results, numNgrams, language) {
    if (!results.length || numNgrams < 3) {
      return false;
    }
    const nextScore = results.length > 1 ? results[1][0] : 0;
    return !(avgScore[language] * 0.24 > results[0][1] / numNgrams || 0.01 > Math.abs(results[0][1] - nextScore));
  }
  function getScores(results, langCodes) {
    let scores = {};
    let key;
    for (key in results) {
      let score = results[key][1];
      if (score === 0) {
        break;
      }
      scores[langCodes[results[key][0]]] = score;
    }
    return scores;
  }
  const saveLanguageSubset = /* @__PURE__ */ function() {
    function saveSubset2(langArray, ngrams, defaultLanguages, type) {
      if (!langArray.length) {
        return "No languages found";
      }
      let newNgrams = JSON.parse(JSON.stringify(ngrams));
      const file = "ngrams" + type + "-" + langArray.length + "_" + Date.now() + ".js";
      for (let ngram in newNgrams) {
        for (let id in newNgrams[ngram]) {
          if (langArray.indexOf(parseInt(id)) === -1) {
            delete newNgrams[ngram][id];
          }
        }
        if (Object.keys(newNgrams[ngram]).length === 0) {
          delete newNgrams[ngram];
        }
      }
      download('// Copyright 2023 Nito T.M. [ Apache 2.0 Licence https://www.apache.org/licenses/LICENSE-2.0 ]\nexport const ngramsData = {\n   type: "' + type + '",\n   languages: ' + JSON.stringify(isoLanguages(langArray, defaultLanguages)) + ",\n   isSubset: true,\n   ngrams: " + ngramExport(newNgrams) + "\n}", file, "js");
    }
    function ngramExport(ngrams) {
      if (typeof ngrams === "object" && ngrams) {
        let toImplode = [];
        for (const property in ngrams) {
          toImplode.push("'" + property.replace(/'/g, "\\'") + "':" + joinNumbers(ngrams[property]));
        }
        return "{" + toImplode.join(",") + "}";
      }
    }
    function joinNumbers(obj) {
      let toImplode = [];
      for (const property in obj) {
        toImplode.push(property + ":" + obj[property]);
      }
      return "{" + toImplode.join(",") + "}";
    }
    function download(data, filename, type) {
      const file = new Blob([data], { type });
      if (typeof window === "undefined") {
        console.log("saveSubset() is only available at the Web Browser");
        return;
      }
      if (window.navigator.msSaveOrOpenBlob) {
        window.navigator.msSaveOrOpenBlob(file, filename);
      } else {
        let a = document.createElement("a");
        let url = URL.createObjectURL(file);
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        setTimeout(function() {
          document.body.removeChild(a);
          window.URL.revokeObjectURL(url);
        }, 0);
      }
    }
    return {
      saveSubset: saveSubset2
    };
  }();
  const eld = /* @__PURE__ */ function() {
    async function init(size = "M") {
      await loadNgrams(size);
    }
    return {
      init,
      detect,
      cleanText,
      dynamicLangSubset,
      saveSubset,
      loadNgrams,
      info
    };
  }();
  let subset = false;
  let doCleanText = false;
  function detect(text) {
    if (typeof text !== "string") return new LanguageResult("", 0, 0, {});
    if (doCleanText) {
      text = getCleanTxt(text);
    }
    const byteWords = textProcessor(text);
    const byteNgrams = getByteNgrams(byteWords);
    const numNgrams = Object.keys(byteNgrams).length;
    let results = calculateScores(byteNgrams, numNgrams);
    let language = "";
    if (subset) {
      results = filterLangSubset(results);
    }
    if (results.length > 0) {
      results.sort((a, b) => b[1] - a[1]);
      language = languageData.langCodes[results[0][0]];
    }
    return new LanguageResult(language, results, numNgrams, languageData.langCodes);
  }
  function cleanText(bool) {
    doCleanText = Boolean(bool);
  }
  function getCleanTxt(str) {
    str = str.replace(/[hw]((ttps?:\/\/(www\.)?)|ww\.)([^\s/?.#-]+\.?)+(\/\S*)?/gi, " ");
    str = str.replace(/[a-zA-Z0-9.!$%&’+_`-]+@[A-Za-z0-9.-]+\.[A-Za-z0-9-]{2,64}/g, " ");
    str = str.replace(matchDomains, " ");
    str = str.replace(/[a-zA-Z]*[0-9]+[a-zA-Z0-9]*/g, " ");
    return str;
  }
  function textProcessor(text) {
    text = text.substring(0, 1e3);
    text = text.replace(separators, " ");
    text = text.trim().toLowerCase();
    return strToUtf8Bytes(text);
  }
  function getByteNgrams(words) {
    let byteNgrams = {};
    let countNgrams = 0;
    let thisBytes;
    let j;
    for (let key in words) {
      let word = words[key];
      let len = word.length;
      if (len > 70) {
        len = 70;
      }
      for (j = 0; j + 4 < len; j += 3, ++countNgrams) {
        thisBytes = (j === 0 ? " " : "") + word.substring(j, j + 4);
        byteNgrams[thisBytes] = typeof byteNgrams[thisBytes] !== "undefined" ? byteNgrams[thisBytes] + 1 : 1;
      }
      thisBytes = (j === 0 ? " " : "") + word.substring(len !== 3 ? len - 4 : 0) + " ";
      byteNgrams[thisBytes] = typeof byteNgrams[thisBytes] !== "undefined" ? byteNgrams[thisBytes] + 1 : 1;
      countNgrams++;
    }
    for (let bytes in byteNgrams) {
      byteNgrams[bytes] = byteNgrams[bytes] / countNgrams * 13200;
    }
    return byteNgrams;
  }
  function calculateScores(byteNgrams, numNgrams) {
    let bytes, globalFrequency, relevancy, langCount, frequency, lang, thisByte;
    let langScore = [...languageData.langScore];
    for (bytes in byteNgrams) {
      frequency = byteNgrams[bytes];
      thisByte = languageData.ngrams[bytes];
      if (thisByte) {
        langCount = Object.keys(thisByte).length;
        if (langCount === 1) {
          relevancy = 27;
        } else {
          if (langCount < 16) {
            relevancy = (16 - langCount) / 2 + 1;
          } else {
            relevancy = 1;
          }
        }
        for (lang in thisByte) {
          globalFrequency = thisByte[lang];
          langScore[lang] += (frequency > globalFrequency ? globalFrequency / frequency : frequency / globalFrequency) * relevancy + 2;
        }
      }
    }
    let resultDivisor = numNgrams * 3.2;
    let results = [];
    for (lang in langScore) {
      if (langScore[lang]) {
        results.push([parseInt(lang), langScore[lang] / resultDivisor]);
      }
    }
    return results;
  }
  function strToUtf8Bytes(str) {
    let encoded = "";
    let words = [];
    let countBytes = 0;
    const cutAfter = 350;
    const enforceCutAfter = 380;
    for (let ii = 0; ii < str.length; ii++) {
      let charCode = str.charCodeAt(ii);
      if (charCode < 128) {
        if (charCode === 32) {
          if (encoded !== "") {
            words.push(encoded);
            encoded = "";
          }
          if (countBytes > cutAfter) {
            break;
          }
        } else {
          encoded += str[ii];
        }
        countBytes++;
      } else if (charCode < 2048) {
        encoded += dictionary[192 | charCode >> 6] + dictionary[128 | charCode & 63];
        countBytes += 2;
      } else if (charCode < 55296 || charCode >= 57344) {
        encoded += dictionary[224 | charCode >> 12] + dictionary[128 | charCode >> 6 & 63] + dictionary[128 | charCode & 63];
        countBytes += 3;
      } else {
        ii++;
        charCode = 65536 + ((charCode & 1023) << 10 | str.charCodeAt(ii) & 1023);
        encoded += dictionary[240 | charCode >> 18] + dictionary[128 | charCode >> 12 & 63] + dictionary[128 | charCode >> 6 & 63] + dictionary[128 | charCode & 63];
        countBytes += 4;
      }
      if (countBytes > enforceCutAfter) {
        break;
      }
    }
    if (encoded !== "") {
      words.push(encoded);
    }
    return words;
  }
  function filterLangSubset(results) {
    let subResults = [];
    for (let key in results) {
      if (subset.indexOf(results[key][0]) > -1) {
        subResults.push(results[key]);
      }
    }
    return subResults;
  }
  function makeSubset(languages) {
    if (languages) {
      subset = [];
      for (let key in languages) {
        let lang = Object.keys(languageData.langCodes).find((lkey) => languageData.langCodes[lkey] === languages[key]);
        if (lang) {
          subset.push(parseInt(lang));
        }
      }
      if (subset.length) {
        subset.sort();
      } else {
        subset = false;
      }
    } else {
      subset = false;
    }
    return subset;
  }
  function dynamicLangSubset(languages) {
    let result2 = makeSubset(languages);
    if (result2) {
      return isoLanguages(result2, languageData.langCodes);
    }
    return {};
  }
  function saveSubset(languages) {
    const langArray = makeSubset(languages);
    makeSubset(false);
    saveLanguageSubset.saveSubset(langArray, languageData.ngrams, languageData.langCodes, languageData.type);
  }
  function info() {
    return {
      "Data type": languageData.type,
      "Languages": languageData.langCodes,
      "Dynamic subset": subset ? isoLanguages(subset, languageData.langCodes) : false
    };
  }
  let enabled = false;
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message.type === "enabled") {
      enabled = Boolean(message.payload);
      console.log(enabled);
    }
  });
  eld.init().then(() => {
    console.log(eld.detect("hallo").language);
  });
  const definition = defineBackground(() => {
    console.log("Hello background!", { id: browser.runtime.id });
  });
  background;
  function initPlugins() {
  }
  function print(method, ...args) {
    if (typeof args[0] === "string") {
      const message = args.shift();
      method(`[wxt] ${message}`, ...args);
    } else {
      method("[wxt]", ...args);
    }
  }
  const logger = {
    debug: (...args) => print(console.debug, ...args),
    log: (...args) => print(console.log, ...args),
    warn: (...args) => print(console.warn, ...args),
    error: (...args) => print(console.error, ...args)
  };
  let ws;
  function getDevServerWebSocket() {
    if (ws == null) {
      const serverUrl = `${"ws:"}//${"localhost"}:${3e3}`;
      logger.debug("Connecting to dev server @", serverUrl);
      ws = new WebSocket(serverUrl, "vite-hmr");
      ws.addWxtEventListener = ws.addEventListener.bind(ws);
      ws.sendCustom = (event, payload) => ws == null ? void 0 : ws.send(JSON.stringify({ type: "custom", event, payload }));
      ws.addEventListener("open", () => {
        logger.debug("Connected to dev server");
      });
      ws.addEventListener("close", () => {
        logger.debug("Disconnected from dev server");
      });
      ws.addEventListener("error", (event) => {
        logger.error("Failed to connect to dev server", event);
      });
      ws.addEventListener("message", (e) => {
        try {
          const message = JSON.parse(e.data);
          if (message.type === "custom") {
            ws == null ? void 0 : ws.dispatchEvent(
              new CustomEvent(message.event, { detail: message.data })
            );
          }
        } catch (err) {
          logger.error("Failed to handle message", err);
        }
      });
    }
    return ws;
  }
  function keepServiceWorkerAlive() {
    setInterval(async () => {
      await browser.runtime.getPlatformInfo();
    }, 5e3);
  }
  function reloadContentScript(payload) {
    const manifest = browser.runtime.getManifest();
    if (manifest.manifest_version == 2) {
      void reloadContentScriptMv2();
    } else {
      void reloadContentScriptMv3(payload);
    }
  }
  async function reloadContentScriptMv3({
    registration,
    contentScript
  }) {
    if (registration === "runtime") {
      await reloadRuntimeContentScriptMv3(contentScript);
    } else {
      await reloadManifestContentScriptMv3(contentScript);
    }
  }
  async function reloadManifestContentScriptMv3(contentScript) {
    const id = `wxt:${contentScript.js[0]}`;
    logger.log("Reloading content script:", contentScript);
    const registered = await browser.scripting.getRegisteredContentScripts();
    logger.debug("Existing scripts:", registered);
    const existing = registered.find((cs) => cs.id === id);
    if (existing) {
      logger.debug("Updating content script", existing);
      await browser.scripting.updateContentScripts([{ ...contentScript, id }]);
    } else {
      logger.debug("Registering new content script...");
      await browser.scripting.registerContentScripts([{ ...contentScript, id }]);
    }
    await reloadTabsForContentScript(contentScript);
  }
  async function reloadRuntimeContentScriptMv3(contentScript) {
    logger.log("Reloading content script:", contentScript);
    const registered = await browser.scripting.getRegisteredContentScripts();
    logger.debug("Existing scripts:", registered);
    const matches = registered.filter((cs) => {
      var _a2, _b2;
      const hasJs = (_a2 = contentScript.js) == null ? void 0 : _a2.find((js) => {
        var _a3;
        return (_a3 = cs.js) == null ? void 0 : _a3.includes(js);
      });
      const hasCss = (_b2 = contentScript.css) == null ? void 0 : _b2.find((css) => {
        var _a3;
        return (_a3 = cs.css) == null ? void 0 : _a3.includes(css);
      });
      return hasJs || hasCss;
    });
    if (matches.length === 0) {
      logger.log(
        "Content script is not registered yet, nothing to reload",
        contentScript
      );
      return;
    }
    await browser.scripting.updateContentScripts(matches);
    await reloadTabsForContentScript(contentScript);
  }
  async function reloadTabsForContentScript(contentScript) {
    const allTabs = await browser.tabs.query({});
    const matchPatterns = contentScript.matches.map(
      (match) => new MatchPattern(match)
    );
    const matchingTabs = allTabs.filter((tab) => {
      const url = tab.url;
      if (!url)
        return false;
      return !!matchPatterns.find((pattern) => pattern.includes(url));
    });
    await Promise.all(
      matchingTabs.map(async (tab) => {
        try {
          await browser.tabs.reload(tab.id);
        } catch (err) {
          logger.warn("Failed to reload tab:", err);
        }
      })
    );
  }
  async function reloadContentScriptMv2(_payload) {
    throw Error("TODO: reloadContentScriptMv2");
  }
  {
    try {
      const ws2 = getDevServerWebSocket();
      ws2.addWxtEventListener("wxt:reload-extension", () => {
        browser.runtime.reload();
      });
      ws2.addWxtEventListener("wxt:reload-content-script", (event) => {
        reloadContentScript(event.detail);
      });
      if (true) {
        ws2.addEventListener(
          "open",
          () => ws2.sendCustom("wxt:background-initialized")
        );
        keepServiceWorkerAlive();
      }
    } catch (err) {
      logger.error("Failed to setup web socket connection with dev server", err);
    }
    browser.commands.onCommand.addListener((command) => {
      if (command === "wxt:reload-extension") {
        browser.runtime.reload();
      }
    });
  }
  let result;
  try {
    initPlugins();
    result = definition.main();
    if (result instanceof Promise) {
      console.warn(
        "The background's main() function return a promise, but it must be synchronous"
      );
    }
  } catch (err) {
    logger.error("The background crashed on startup!");
    throw err;
  }
  const result$1 = result;
  return result$1;
}();
background;
//# sourceMappingURL=data:application/json;charset=utf-8;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiYmFja2dyb3VuZC5qcyIsInNvdXJjZXMiOlsiLi4vLi4vbm9kZV9tb2R1bGVzLy5wbnBtL3d4dEAwLjE5LjIyL25vZGVfbW9kdWxlcy93eHQvZGlzdC9zYW5kYm94L2RlZmluZS1iYWNrZ3JvdW5kLm1qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9Ad2ViZXh0LWNvcmUrbWF0Y2gtcGF0dGVybnNAMS4wLjMvbm9kZV9tb2R1bGVzL0B3ZWJleHQtY29yZS9tYXRjaC1wYXR0ZXJucy9saWIvaW5kZXguanMiLCIuLi8uLi9ub2RlX21vZHVsZXMvLnBucG0vd3h0QDAuMTkuMjIvbm9kZV9tb2R1bGVzL3d4dC9kaXN0L2Jyb3dzZXIvY2hyb21lLm1qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9AeXV0ZW5namluZytlbGRAMC4wLjIvbm9kZV9tb2R1bGVzL0B5dXRlbmdqaW5nL2VsZC9zcmMvYXZnU2NvcmUuanMiLCIuLi8uLi9ub2RlX21vZHVsZXMvLnBucG0vQHl1dGVuZ2ppbmcrZWxkQDAuMC4yL25vZGVfbW9kdWxlcy9AeXV0ZW5namluZy9lbGQvc3JjL2xhbmd1YWdlRGF0YS5qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9AeXV0ZW5namluZytlbGRAMC4wLjIvbm9kZV9tb2R1bGVzL0B5dXRlbmdqaW5nL2VsZC9zcmMvcmVnZXhQYXR0ZXJucy5qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9AeXV0ZW5namluZytlbGRAMC4wLjIvbm9kZV9tb2R1bGVzL0B5dXRlbmdqaW5nL2VsZC9zcmMvZGljdGlvbmFyeS5qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9AeXV0ZW5namluZytlbGRAMC4wLjIvbm9kZV9tb2R1bGVzL0B5dXRlbmdqaW5nL2VsZC9zcmMvaXNvTGFuZ3VhZ2VzLmpzIiwiLi4vLi4vbm9kZV9tb2R1bGVzLy5wbnBtL0B5dXRlbmdqaW5nK2VsZEAwLjAuMi9ub2RlX21vZHVsZXMvQHl1dGVuZ2ppbmcvZWxkL3NyYy9MYW5ndWFnZVJlc3VsdC5qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9AeXV0ZW5namluZytlbGRAMC4wLjIvbm9kZV9tb2R1bGVzL0B5dXRlbmdqaW5nL2VsZC9zcmMvc2F2ZUxhbmd1YWdlU3Vic2V0LmRldi5qcyIsIi4uLy4uL25vZGVfbW9kdWxlcy8ucG5wbS9AeXV0ZW5namluZytlbGRAMC4wLjIvbm9kZV9tb2R1bGVzL0B5dXRlbmdqaW5nL2VsZC9zcmMvbGFuZ3VhZ2VEZXRlY3Rvci5qcyIsIi4uLy4uL3NyYy9lbnRyeXBvaW50cy9iYWNrZ3JvdW5kLnRzIl0sInNvdXJjZXNDb250ZW50IjpbImV4cG9ydCBmdW5jdGlvbiBkZWZpbmVCYWNrZ3JvdW5kKGFyZykge1xuICBpZiAoYXJnID09IG51bGwgfHwgdHlwZW9mIGFyZyA9PT0gXCJmdW5jdGlvblwiKSByZXR1cm4geyBtYWluOiBhcmcgfTtcbiAgcmV0dXJuIGFyZztcbn1cbiIsIi8vIHNyYy9pbmRleC50c1xudmFyIF9NYXRjaFBhdHRlcm4gPSBjbGFzcyB7XG4gIGNvbnN0cnVjdG9yKG1hdGNoUGF0dGVybikge1xuICAgIGlmIChtYXRjaFBhdHRlcm4gPT09IFwiPGFsbF91cmxzPlwiKSB7XG4gICAgICB0aGlzLmlzQWxsVXJscyA9IHRydWU7XG4gICAgICB0aGlzLnByb3RvY29sTWF0Y2hlcyA9IFsuLi5fTWF0Y2hQYXR0ZXJuLlBST1RPQ09MU107XG4gICAgICB0aGlzLmhvc3RuYW1lTWF0Y2ggPSBcIipcIjtcbiAgICAgIHRoaXMucGF0aG5hbWVNYXRjaCA9IFwiKlwiO1xuICAgIH0gZWxzZSB7XG4gICAgICBjb25zdCBncm91cHMgPSAvKC4qKTpcXC9cXC8oLio/KShcXC8uKikvLmV4ZWMobWF0Y2hQYXR0ZXJuKTtcbiAgICAgIGlmIChncm91cHMgPT0gbnVsbClcbiAgICAgICAgdGhyb3cgbmV3IEludmFsaWRNYXRjaFBhdHRlcm4obWF0Y2hQYXR0ZXJuLCBcIkluY29ycmVjdCBmb3JtYXRcIik7XG4gICAgICBjb25zdCBbXywgcHJvdG9jb2wsIGhvc3RuYW1lLCBwYXRobmFtZV0gPSBncm91cHM7XG4gICAgICB2YWxpZGF0ZVByb3RvY29sKG1hdGNoUGF0dGVybiwgcHJvdG9jb2wpO1xuICAgICAgdmFsaWRhdGVIb3N0bmFtZShtYXRjaFBhdHRlcm4sIGhvc3RuYW1lKTtcbiAgICAgIHZhbGlkYXRlUGF0aG5hbWUobWF0Y2hQYXR0ZXJuLCBwYXRobmFtZSk7XG4gICAgICB0aGlzLnByb3RvY29sTWF0Y2hlcyA9IHByb3RvY29sID09PSBcIipcIiA/IFtcImh0dHBcIiwgXCJodHRwc1wiXSA6IFtwcm90b2NvbF07XG4gICAgICB0aGlzLmhvc3RuYW1lTWF0Y2ggPSBob3N0bmFtZTtcbiAgICAgIHRoaXMucGF0aG5hbWVNYXRjaCA9IHBhdGhuYW1lO1xuICAgIH1cbiAgfVxuICBpbmNsdWRlcyh1cmwpIHtcbiAgICBpZiAodGhpcy5pc0FsbFVybHMpXG4gICAgICByZXR1cm4gdHJ1ZTtcbiAgICBjb25zdCB1ID0gdHlwZW9mIHVybCA9PT0gXCJzdHJpbmdcIiA/IG5ldyBVUkwodXJsKSA6IHVybCBpbnN0YW5jZW9mIExvY2F0aW9uID8gbmV3IFVSTCh1cmwuaHJlZikgOiB1cmw7XG4gICAgcmV0dXJuICEhdGhpcy5wcm90b2NvbE1hdGNoZXMuZmluZCgocHJvdG9jb2wpID0+IHtcbiAgICAgIGlmIChwcm90b2NvbCA9PT0gXCJodHRwXCIpXG4gICAgICAgIHJldHVybiB0aGlzLmlzSHR0cE1hdGNoKHUpO1xuICAgICAgaWYgKHByb3RvY29sID09PSBcImh0dHBzXCIpXG4gICAgICAgIHJldHVybiB0aGlzLmlzSHR0cHNNYXRjaCh1KTtcbiAgICAgIGlmIChwcm90b2NvbCA9PT0gXCJmaWxlXCIpXG4gICAgICAgIHJldHVybiB0aGlzLmlzRmlsZU1hdGNoKHUpO1xuICAgICAgaWYgKHByb3RvY29sID09PSBcImZ0cFwiKVxuICAgICAgICByZXR1cm4gdGhpcy5pc0Z0cE1hdGNoKHUpO1xuICAgICAgaWYgKHByb3RvY29sID09PSBcInVyblwiKVxuICAgICAgICByZXR1cm4gdGhpcy5pc1Vybk1hdGNoKHUpO1xuICAgIH0pO1xuICB9XG4gIGlzSHR0cE1hdGNoKHVybCkge1xuICAgIHJldHVybiB1cmwucHJvdG9jb2wgPT09IFwiaHR0cDpcIiAmJiB0aGlzLmlzSG9zdFBhdGhNYXRjaCh1cmwpO1xuICB9XG4gIGlzSHR0cHNNYXRjaCh1cmwpIHtcbiAgICByZXR1cm4gdXJsLnByb3RvY29sID09PSBcImh0dHBzOlwiICYmIHRoaXMuaXNIb3N0UGF0aE1hdGNoKHVybCk7XG4gIH1cbiAgaXNIb3N0UGF0aE1hdGNoKHVybCkge1xuICAgIGlmICghdGhpcy5ob3N0bmFtZU1hdGNoIHx8ICF0aGlzLnBhdGhuYW1lTWF0Y2gpXG4gICAgICByZXR1cm4gZmFsc2U7XG4gICAgY29uc3QgaG9zdG5hbWVNYXRjaFJlZ2V4cyA9IFtcbiAgICAgIHRoaXMuY29udmVydFBhdHRlcm5Ub1JlZ2V4KHRoaXMuaG9zdG5hbWVNYXRjaCksXG4gICAgICB0aGlzLmNvbnZlcnRQYXR0ZXJuVG9SZWdleCh0aGlzLmhvc3RuYW1lTWF0Y2gucmVwbGFjZSgvXlxcKlxcLi8sIFwiXCIpKVxuICAgIF07XG4gICAgY29uc3QgcGF0aG5hbWVNYXRjaFJlZ2V4ID0gdGhpcy5jb252ZXJ0UGF0dGVyblRvUmVnZXgodGhpcy5wYXRobmFtZU1hdGNoKTtcbiAgICByZXR1cm4gISFob3N0bmFtZU1hdGNoUmVnZXhzLmZpbmQoKHJlZ2V4KSA9PiByZWdleC50ZXN0KHVybC5ob3N0bmFtZSkpICYmIHBhdGhuYW1lTWF0Y2hSZWdleC50ZXN0KHVybC5wYXRobmFtZSk7XG4gIH1cbiAgaXNGaWxlTWF0Y2godXJsKSB7XG4gICAgdGhyb3cgRXJyb3IoXCJOb3QgaW1wbGVtZW50ZWQ6IGZpbGU6Ly8gcGF0dGVybiBtYXRjaGluZy4gT3BlbiBhIFBSIHRvIGFkZCBzdXBwb3J0XCIpO1xuICB9XG4gIGlzRnRwTWF0Y2godXJsKSB7XG4gICAgdGhyb3cgRXJyb3IoXCJOb3QgaW1wbGVtZW50ZWQ6IGZ0cDovLyBwYXR0ZXJuIG1hdGNoaW5nLiBPcGVuIGEgUFIgdG8gYWRkIHN1cHBvcnRcIik7XG4gIH1cbiAgaXNVcm5NYXRjaCh1cmwpIHtcbiAgICB0aHJvdyBFcnJvcihcIk5vdCBpbXBsZW1lbnRlZDogdXJuOi8vIHBhdHRlcm4gbWF0Y2hpbmcuIE9wZW4gYSBQUiB0byBhZGQgc3VwcG9ydFwiKTtcbiAgfVxuICBjb252ZXJ0UGF0dGVyblRvUmVnZXgocGF0dGVybikge1xuICAgIGNvbnN0IGVzY2FwZWQgPSB0aGlzLmVzY2FwZUZvclJlZ2V4KHBhdHRlcm4pO1xuICAgIGNvbnN0IHN0YXJzUmVwbGFjZWQgPSBlc2NhcGVkLnJlcGxhY2UoL1xcXFxcXCovZywgXCIuKlwiKTtcbiAgICByZXR1cm4gUmVnRXhwKGBeJHtzdGFyc1JlcGxhY2VkfSRgKTtcbiAgfVxuICBlc2NhcGVGb3JSZWdleChzdHJpbmcpIHtcbiAgICByZXR1cm4gc3RyaW5nLnJlcGxhY2UoL1suKis/XiR7fSgpfFtcXF1cXFxcXS9nLCBcIlxcXFwkJlwiKTtcbiAgfVxufTtcbnZhciBNYXRjaFBhdHRlcm4gPSBfTWF0Y2hQYXR0ZXJuO1xuTWF0Y2hQYXR0ZXJuLlBST1RPQ09MUyA9IFtcImh0dHBcIiwgXCJodHRwc1wiLCBcImZpbGVcIiwgXCJmdHBcIiwgXCJ1cm5cIl07XG52YXIgSW52YWxpZE1hdGNoUGF0dGVybiA9IGNsYXNzIGV4dGVuZHMgRXJyb3Ige1xuICBjb25zdHJ1Y3RvcihtYXRjaFBhdHRlcm4sIHJlYXNvbikge1xuICAgIHN1cGVyKGBJbnZhbGlkIG1hdGNoIHBhdHRlcm4gXCIke21hdGNoUGF0dGVybn1cIjogJHtyZWFzb259YCk7XG4gIH1cbn07XG5mdW5jdGlvbiB2YWxpZGF0ZVByb3RvY29sKG1hdGNoUGF0dGVybiwgcHJvdG9jb2wpIHtcbiAgaWYgKCFNYXRjaFBhdHRlcm4uUFJPVE9DT0xTLmluY2x1ZGVzKHByb3RvY29sKSAmJiBwcm90b2NvbCAhPT0gXCIqXCIpXG4gICAgdGhyb3cgbmV3IEludmFsaWRNYXRjaFBhdHRlcm4oXG4gICAgICBtYXRjaFBhdHRlcm4sXG4gICAgICBgJHtwcm90b2NvbH0gbm90IGEgdmFsaWQgcHJvdG9jb2wgKCR7TWF0Y2hQYXR0ZXJuLlBST1RPQ09MUy5qb2luKFwiLCBcIil9KWBcbiAgICApO1xufVxuZnVuY3Rpb24gdmFsaWRhdGVIb3N0bmFtZShtYXRjaFBhdHRlcm4sIGhvc3RuYW1lKSB7XG4gIGlmIChob3N0bmFtZS5pbmNsdWRlcyhcIjpcIikpXG4gICAgdGhyb3cgbmV3IEludmFsaWRNYXRjaFBhdHRlcm4obWF0Y2hQYXR0ZXJuLCBgSG9zdG5hbWUgY2Fubm90IGluY2x1ZGUgYSBwb3J0YCk7XG4gIGlmIChob3N0bmFtZS5pbmNsdWRlcyhcIipcIikgJiYgaG9zdG5hbWUubGVuZ3RoID4gMSAmJiAhaG9zdG5hbWUuc3RhcnRzV2l0aChcIiouXCIpKVxuICAgIHRocm93IG5ldyBJbnZhbGlkTWF0Y2hQYXR0ZXJuKFxuICAgICAgbWF0Y2hQYXR0ZXJuLFxuICAgICAgYElmIHVzaW5nIGEgd2lsZGNhcmQgKCopLCBpdCBtdXN0IGdvIGF0IHRoZSBzdGFydCBvZiB0aGUgaG9zdG5hbWVgXG4gICAgKTtcbn1cbmZ1bmN0aW9uIHZhbGlkYXRlUGF0aG5hbWUobWF0Y2hQYXR0ZXJuLCBwYXRobmFtZSkge1xuICByZXR1cm47XG59XG5leHBvcnQge1xuICBJbnZhbGlkTWF0Y2hQYXR0ZXJuLFxuICBNYXRjaFBhdHRlcm5cbn07XG4iLCJleHBvcnQgY29uc3QgYnJvd3NlciA9IChcbiAgLy8gQHRzLWV4cGVjdC1lcnJvclxuICBnbG9iYWxUaGlzLmJyb3dzZXI/LnJ1bnRpbWU/LmlkID09IG51bGwgPyBnbG9iYWxUaGlzLmNocm9tZSA6IChcbiAgICAvLyBAdHMtZXhwZWN0LWVycm9yXG4gICAgZ2xvYmFsVGhpcy5icm93c2VyXG4gIClcbik7XG4iLCIvLyBBdmVyYWdlIHNjb3JlIG9mIGVhY2ggbGFuZ3VhZ2UgaW4gYSBjb3JyZWN0IGRldGVjdGlvbiwgZG9uZSB3aXRoIGFuIGV4dGVuZGVkIHZlcnNpb24gb2YgYmlnLXRlc3QgYmVuY2htYXJrLlxuZXhwb3J0IGNvbnN0IGF2Z1Njb3JlID0ge1xuICBhbTogMC4wNjYxLFxuICBhcjogMC4wMjM3LFxuICBhejogMC4wMjY5LFxuICBiZTogMC4wMjI3LFxuICBiZzogMC4wMjM0LFxuICBibjogMC4xMzczLFxuICBjYTogMC4wMjQ2LFxuICBjczogMC4wMjQyLFxuICBkYTogMC4wMjc3LFxuICBkZTogMC4wMjc1LFxuICBlbDogMC4wMzY5LFxuICBlbjogMC4wMzc4LFxuICBlczogMC4wMjUyLFxuICBldDogMC4wMjUzLFxuICBldTogMC4wMzY5LFxuICBmYTogMC4wMjEzLFxuICBmaTogMC4wMjYsXG4gIGZyOiAwLjAyNTMsXG4gIGd1OiAwLjExOTcsXG4gIGhlOiAwLjA0MDIsXG4gIGhpOiAwLjA1NzgsXG4gIGhyOiAwLjAyMDEsXG4gIGh1OiAwLjAyMDgsXG4gIGh5OiAwLjA0MzksXG4gIGlzOiAwLjAzMixcbiAgaXQ6IDAuMDI1MSxcbiAgamE6IDAuMDM3NSxcbiAga2E6IDAuMTM4MyxcbiAga246IDAuMTMwNSxcbiAga286IDAuMDIyMixcbiAga3U6IDAuMDI1NixcbiAgbG86IDAuMzQ4OCxcbiAgbHQ6IDAuMDI0NixcbiAgbHY6IDAuMDI2NCxcbiAgbWw6IDAuMTMyMixcbiAgbXI6IDAuMDU3MSxcbiAgbXM6IDAuMDI1MSxcbiAgbmw6IDAuMDM0MixcbiAgbm86IDAuMDI2NixcbiAgb3I6IDAuMTI2OSxcbiAgcGE6IDAuMTMzOCxcbiAgcGw6IDAuMDI3NSxcbiAgcHQ6IDAuMDI1MixcbiAgcm86IDAuMDI0NyxcbiAgcnU6IDAuMDE4NCxcbiAgc2s6IDAuMDI0LFxuICBzbDogMC4wMjUzLFxuICBzcTogMC4wMzUzLFxuICBzcjogMC4wMjM0LFxuICBzdjogMC4wMzMsXG4gIHRhOiAwLjE1MTMsXG4gIHRlOiAwLjE1NDcsXG4gIHRoOiAwLjA4ODIsXG4gIHRsOiAwLjAzNjgsXG4gIHRyOiAwLjAyNTgsXG4gIHVrOiAwLjAyMDYsXG4gIHVyOiAwLjAyODIsXG4gIHZpOiAwLjA0NjcsXG4gIHlvOiAwLjAzMjksXG4gIHpoOiAwLjAxNTIsXG59XG5cbi8qIERlcHJlY2F0ZWQgZm9yIG5vdzogU29tZSBsYW5ndWFnZXMgc2NvcmUgaGlnaGVyIHdpdGggdGhlIHNhbWUgYW1vdW50IG9mIHRleHQsIHRoaXMgbXVsdGlwbGllciBldmVucyBpdCBvdXQgZm9yXG4gKiAgbXVsdGktbGFuZ3VhZ2Ugc3RyaW5nc1xuICogc2NvcmVOb3JtYWxpemVyID0gWzAuNywgMSwgMSwgMSwgMSwgMC42LCAwLjk4LCAxLCAxLCAxLCAwLjksIDEsIDEsIDEsIDEsIDEsIDEsIDEsIDAuNiwgMSwgMC43LCAxLCAxLCAwLjksIDEsIDEsIDAuOCxcbiAqIDAuNiwgMC42LCAxLCAxLCAwLjUsIDEsIDEsIDAuNiwgMC43LCAxLCAwLjk1LCAxLCAwLjYsIDAuNiwgMSwgMSwgMSwgMSwgMSwgMSwgMC45LCAxLCAxLCAwLjYsIDAuNiwgMC43LCAwLjksIDEsIDEsIDEsXG4gKiAwLjgsIDEsIDEuN11cbiAqL1xuIiwiLypcbkNvcHlyaWdodCAyMDIzIE5pdG8gVC5NLlxuTGljZW5zZSBodHRwczovL3d3dy5hcGFjaGUub3JnL2xpY2Vuc2VzL0xJQ0VOU0UtMi4wIEFwYWNoZS0yLjBcbkF1dGhvciBOaXRvIFQuTS4gKGh0dHBzOi8vZ2l0aHViLmNvbS9uaXRvdG0pXG5QYWNrYWdlIG5wbWpzLmNvbS9wYWNrYWdlL2VsZFxuKi9cblxuaW1wb3J0IHsgYXZnU2NvcmUgfSBmcm9tICcuL2F2Z1Njb3JlLmpzJ1xuLy8gaW1wb3J0IHsgbmdyYW1zRGF0YSB9IGZyb20gXCIuL25ncmFtcy9uZ3JhbXNNNjAuanNcIlxuXG5leHBvcnQgY29uc3QgbGFuZ3VhZ2VEYXRhID0ge1xuICBsYW5nQ29kZXM6IHt9LCBsYW5nU2NvcmU6IFtdLCBuZ3JhbXM6IHt9LCB0eXBlOiAnJywgYXZnU2NvcmU6IGF2Z1Njb3JlXG59XG5cbi8qKlxuICogQHBhcmFtIHtzdHJpbmd9IGZpbGUgRmlsZSBpbnNpZGUgL25ncmFtcy8sIHdpdGggRUxEIG5ncmFtcyBkYXRhIGZvcm1hdFxuICogQHJldHVybnMge2Jvb2xlYW58dW5kZWZpbmVkfSB0cnVlIGlmIGZpbGUgd2FzIGxvYWRlZFxuICovXG5leHBvcnQgYXN5bmMgZnVuY3Rpb24gbG9hZE5ncmFtcyhzaXplKSB7XG4gIHJldHVybiBpbXBvcnQoJy4vbmdyYW1zL25ncmFtcycgKyBzaXplICsgJzYwLmpzJykudGhlbigobW9kdWxlKSA9PiB7XG4gICAgc2V0TmdyYW1zKG1vZHVsZS5uZ3JhbXNEYXRhKVxuICAgIGlmIChsYW5ndWFnZURhdGEudHlwZSkge1xuICAgICAgcmV0dXJuIHRydWVcbiAgICB9XG4gIH0pXG59XG4vLyBzZXROZ3JhbXMobmdyYW1zRGF0YSkgLy8gVXNlZCB0byBjcmVhdGUgbWluaWZpZWQgZmlsZXMgd2l0aCBpbXBvcnQgeyBuZ3JhbXNEYXRhIH1cblxuLyoqXG4gKiBAcGFyYW0ge09iamVjdH0gZGF0YVxuICovXG5mdW5jdGlvbiBzZXROZ3JhbXMoZGF0YSkge1xuICBsYW5ndWFnZURhdGEubGFuZ0NvZGVzID0gZGF0YS5sYW5ndWFnZXNcbiAgbGFuZ3VhZ2VEYXRhLmxhbmdTY29yZSA9IEFycmF5KE9iamVjdC5rZXlzKGRhdGEubGFuZ3VhZ2VzKS5sZW5ndGgpLmZpbGwoMClcbiAgbGFuZ3VhZ2VEYXRhLm5ncmFtcyA9IGRhdGEubmdyYW1zXG4gIGxhbmd1YWdlRGF0YS50eXBlID0gZGF0YS50eXBlXG59XG5cbi8qIElTTyA2MzktMSBjb2RlcywgZm9yIHRoZSA2MCBsYW5ndWFnZXMgc2V0LlxuICogWydhbScsICdhcicsICdheicsICdiZScsICdiZycsICdibicsICdjYScsICdjcycsICdkYScsICdkZScsICdlbCcsICdlbicsICdlcycsICdldCcsICdldScsICdmYScsICdmaScsICdmcicsICdndScsXG4gKiAnaGUnLCAnaGknLCAnaHInLCAnaHUnLCAnaHknLCAnaXMnLCAnaXQnLCAnamEnLCAna2EnLCAna24nLCAna28nLCAna3UnLCAnbG8nLCAnbHQnLCAnbHYnLCAnbWwnLCAnbXInLCAnbXMnLCAnbmwnLFxuICogJ25vJywgJ29yJywgJ3BhJywgJ3BsJywgJ3B0JywgJ3JvJywgJ3J1JywgJ3NrJywgJ3NsJywgJ3NxJywgJ3NyJywgJ3N2JywgJ3RhJywgJ3RlJywgJ3RoJywgJ3RsJywgJ3RyJywgJ3VrJywgJ3VyJyxcbiAqICd2aScsICd5bycsICd6aCddXG4gKlxuICogWydBbWhhcmljJywgJ0FyYWJpYycsICdBemVyYmFpamFuaSAoTGF0aW4pJywgJ0JlbGFydXNpYW4nLCAnQnVsZ2FyaWFuJywgJ0JlbmdhbGknLCAnQ2F0YWxhbicsICdDemVjaCcsICdEYW5pc2gnLFxuICogJ0dlcm1hbicsICdHcmVlaycsICdFbmdsaXNoJywgJ1NwYW5pc2gnLCAnRXN0b25pYW4nLCAnQmFzcXVlJywgJ1BlcnNpYW4nLCAnRmlubmlzaCcsICdGcmVuY2gnLCAnR3VqYXJhdGknLFxuICogJ0hlYnJldycsICdIaW5kaScsICdDcm9hdGlhbicsICdIdW5nYXJpYW4nLCAnQXJtZW5pYW4nLCAnSWNlbGFuZGljJywgJ0l0YWxpYW4nLCAnSmFwYW5lc2UnLCAnR2VvcmdpYW4nLCAnS2FubmFkYScsXG4gKiAnS29yZWFuJywgJ0t1cmRpc2ggKEFyYWJpYyknLCAnTGFvJywgJ0xpdGh1YW5pYW4nLCAnTGF0dmlhbicsICdNYWxheWFsYW0nLCAnTWFyYXRoaScsICdNYWxheSAoTGF0aW4pJywgJ0R1dGNoJyxcbiAqICdOb3J3ZWdpYW4nLCAnT3JpeWEnLCAnUHVuamFiaScsICdQb2xpc2gnLCAnUG9ydHVndWVzZScsICdSb21hbmlhbicsICdSdXNzaWFuJywgJ1Nsb3ZhaycsICdTbG92ZW5lJywgJ0FsYmFuaWFuJyxcbiAqICdTZXJiaWFuIChDeXJpbGxpYyknLCAnU3dlZGlzaCcsICdUYW1pbCcsICdUZWx1Z3UnLCAnVGhhaScsICdUYWdhbG9nJywgJ1R1cmtpc2gnLCAnVWtyYWluaWFuJywgJ1VyZHUnLFxuICogJ1ZpZXRuYW1lc2UnLCAnWW9ydWJhJywgJ0NoaW5lc2UnXVxuICovXG4iLCJjb25zdCB1bmljb2RlUmVnZXggPSB7XG4gICdMJzoge1xuICAgICdhbGlhcyc6ICdMZXR0ZXInLFxuICAgICdibXAnOiAnQS1aYS16XFx4QUFcXHhCNVxceEJBXFx4QzAtXFx4RDZcXHhEOC1cXHhGNlxceEY4LVxcdTAyQzFcXHUwMkM2LVxcdTAyRDFcXHUwMkUwLVxcdTAyRTRcXHUwMkVDXFx1MDJFRVxcdTAzNzAtXFx1MDM3NFxcdTAzNzZcXHUwMzc3XFx1MDM3QS1cXHUwMzdEXFx1MDM3RlxcdTAzODZcXHUwMzg4LVxcdTAzOEFcXHUwMzhDXFx1MDM4RS1cXHUwM0ExXFx1MDNBMy1cXHUwM0Y1XFx1MDNGNy1cXHUwNDgxXFx1MDQ4QS1cXHUwNTJGXFx1MDUzMS1cXHUwNTU2XFx1MDU1OVxcdTA1NjAtXFx1MDU4OFxcdTA1RDAtXFx1MDVFQVxcdTA1RUYtXFx1MDVGMlxcdTA2MjAtXFx1MDY0QVxcdTA2NkVcXHUwNjZGXFx1MDY3MS1cXHUwNkQzXFx1MDZENVxcdTA2RTVcXHUwNkU2XFx1MDZFRVxcdTA2RUZcXHUwNkZBLVxcdTA2RkNcXHUwNkZGXFx1MDcxMFxcdTA3MTItXFx1MDcyRlxcdTA3NEQtXFx1MDdBNVxcdTA3QjFcXHUwN0NBLVxcdTA3RUFcXHUwN0Y0XFx1MDdGNVxcdTA3RkFcXHUwODAwLVxcdTA4MTVcXHUwODFBXFx1MDgyNFxcdTA4MjhcXHUwODQwLVxcdTA4NThcXHUwODYwLVxcdTA4NkFcXHUwODcwLVxcdTA4ODdcXHUwODg5LVxcdTA4OEVcXHUwOEEwLVxcdTA4QzlcXHUwOTA0LVxcdTA5MzlcXHUwOTNEXFx1MDk1MFxcdTA5NTgtXFx1MDk2MVxcdTA5NzEtXFx1MDk4MFxcdTA5ODUtXFx1MDk4Q1xcdTA5OEZcXHUwOTkwXFx1MDk5My1cXHUwOUE4XFx1MDlBQS1cXHUwOUIwXFx1MDlCMlxcdTA5QjYtXFx1MDlCOVxcdTA5QkRcXHUwOUNFXFx1MDlEQ1xcdTA5RERcXHUwOURGLVxcdTA5RTFcXHUwOUYwXFx1MDlGMVxcdTA5RkNcXHUwQTA1LVxcdTBBMEFcXHUwQTBGXFx1MEExMFxcdTBBMTMtXFx1MEEyOFxcdTBBMkEtXFx1MEEzMFxcdTBBMzJcXHUwQTMzXFx1MEEzNVxcdTBBMzZcXHUwQTM4XFx1MEEzOVxcdTBBNTktXFx1MEE1Q1xcdTBBNUVcXHUwQTcyLVxcdTBBNzRcXHUwQTg1LVxcdTBBOERcXHUwQThGLVxcdTBBOTFcXHUwQTkzLVxcdTBBQThcXHUwQUFBLVxcdTBBQjBcXHUwQUIyXFx1MEFCM1xcdTBBQjUtXFx1MEFCOVxcdTBBQkRcXHUwQUQwXFx1MEFFMFxcdTBBRTFcXHUwQUY5XFx1MEIwNS1cXHUwQjBDXFx1MEIwRlxcdTBCMTBcXHUwQjEzLVxcdTBCMjhcXHUwQjJBLVxcdTBCMzBcXHUwQjMyXFx1MEIzM1xcdTBCMzUtXFx1MEIzOVxcdTBCM0RcXHUwQjVDXFx1MEI1RFxcdTBCNUYtXFx1MEI2MVxcdTBCNzFcXHUwQjgzXFx1MEI4NS1cXHUwQjhBXFx1MEI4RS1cXHUwQjkwXFx1MEI5Mi1cXHUwQjk1XFx1MEI5OVxcdTBCOUFcXHUwQjlDXFx1MEI5RVxcdTBCOUZcXHUwQkEzXFx1MEJBNFxcdTBCQTgtXFx1MEJBQVxcdTBCQUUtXFx1MEJCOVxcdTBCRDBcXHUwQzA1LVxcdTBDMENcXHUwQzBFLVxcdTBDMTBcXHUwQzEyLVxcdTBDMjhcXHUwQzJBLVxcdTBDMzlcXHUwQzNEXFx1MEM1OC1cXHUwQzVBXFx1MEM1RFxcdTBDNjBcXHUwQzYxXFx1MEM4MFxcdTBDODUtXFx1MEM4Q1xcdTBDOEUtXFx1MEM5MFxcdTBDOTItXFx1MENBOFxcdTBDQUEtXFx1MENCM1xcdTBDQjUtXFx1MENCOVxcdTBDQkRcXHUwQ0REXFx1MENERVxcdTBDRTBcXHUwQ0UxXFx1MENGMVxcdTBDRjJcXHUwRDA0LVxcdTBEMENcXHUwRDBFLVxcdTBEMTBcXHUwRDEyLVxcdTBEM0FcXHUwRDNEXFx1MEQ0RVxcdTBENTQtXFx1MEQ1NlxcdTBENUYtXFx1MEQ2MVxcdTBEN0EtXFx1MEQ3RlxcdTBEODUtXFx1MEQ5NlxcdTBEOUEtXFx1MERCMVxcdTBEQjMtXFx1MERCQlxcdTBEQkRcXHUwREMwLVxcdTBEQzZcXHUwRTAxLVxcdTBFMzBcXHUwRTMyXFx1MEUzM1xcdTBFNDAtXFx1MEU0NlxcdTBFODFcXHUwRTgyXFx1MEU4NFxcdTBFODYtXFx1MEU4QVxcdTBFOEMtXFx1MEVBM1xcdTBFQTVcXHUwRUE3LVxcdTBFQjBcXHUwRUIyXFx1MEVCM1xcdTBFQkRcXHUwRUMwLVxcdTBFQzRcXHUwRUM2XFx1MEVEQy1cXHUwRURGXFx1MEYwMFxcdTBGNDAtXFx1MEY0N1xcdTBGNDktXFx1MEY2Q1xcdTBGODgtXFx1MEY4Q1xcdTEwMDAtXFx1MTAyQVxcdTEwM0ZcXHUxMDUwLVxcdTEwNTVcXHUxMDVBLVxcdTEwNURcXHUxMDYxXFx1MTA2NVxcdTEwNjZcXHUxMDZFLVxcdTEwNzBcXHUxMDc1LVxcdTEwODFcXHUxMDhFXFx1MTBBMC1cXHUxMEM1XFx1MTBDN1xcdTEwQ0RcXHUxMEQwLVxcdTEwRkFcXHUxMEZDLVxcdTEyNDhcXHUxMjRBLVxcdTEyNERcXHUxMjUwLVxcdTEyNTZcXHUxMjU4XFx1MTI1QS1cXHUxMjVEXFx1MTI2MC1cXHUxMjg4XFx1MTI4QS1cXHUxMjhEXFx1MTI5MC1cXHUxMkIwXFx1MTJCMi1cXHUxMkI1XFx1MTJCOC1cXHUxMkJFXFx1MTJDMFxcdTEyQzItXFx1MTJDNVxcdTEyQzgtXFx1MTJENlxcdTEyRDgtXFx1MTMxMFxcdTEzMTItXFx1MTMxNVxcdTEzMTgtXFx1MTM1QVxcdTEzODAtXFx1MTM4RlxcdTEzQTAtXFx1MTNGNVxcdTEzRjgtXFx1MTNGRFxcdTE0MDEtXFx1MTY2Q1xcdTE2NkYtXFx1MTY3RlxcdTE2ODEtXFx1MTY5QVxcdTE2QTAtXFx1MTZFQVxcdTE2RjEtXFx1MTZGOFxcdTE3MDAtXFx1MTcxMVxcdTE3MUYtXFx1MTczMVxcdTE3NDAtXFx1MTc1MVxcdTE3NjAtXFx1MTc2Q1xcdTE3NkUtXFx1MTc3MFxcdTE3ODAtXFx1MTdCM1xcdTE3RDdcXHUxN0RDXFx1MTgyMC1cXHUxODc4XFx1MTg4MC1cXHUxODg0XFx1MTg4Ny1cXHUxOEE4XFx1MThBQVxcdTE4QjAtXFx1MThGNVxcdTE5MDAtXFx1MTkxRVxcdTE5NTAtXFx1MTk2RFxcdTE5NzAtXFx1MTk3NFxcdTE5ODAtXFx1MTlBQlxcdTE5QjAtXFx1MTlDOVxcdTFBMDAtXFx1MUExNlxcdTFBMjAtXFx1MUE1NFxcdTFBQTdcXHUxQjA1LVxcdTFCMzNcXHUxQjQ1LVxcdTFCNENcXHUxQjgzLVxcdTFCQTBcXHUxQkFFXFx1MUJBRlxcdTFCQkEtXFx1MUJFNVxcdTFDMDAtXFx1MUMyM1xcdTFDNEQtXFx1MUM0RlxcdTFDNUEtXFx1MUM3RFxcdTFDODAtXFx1MUM4OFxcdTFDOTAtXFx1MUNCQVxcdTFDQkQtXFx1MUNCRlxcdTFDRTktXFx1MUNFQ1xcdTFDRUUtXFx1MUNGM1xcdTFDRjVcXHUxQ0Y2XFx1MUNGQVxcdTFEMDAtXFx1MURCRlxcdTFFMDAtXFx1MUYxNVxcdTFGMTgtXFx1MUYxRFxcdTFGMjAtXFx1MUY0NVxcdTFGNDgtXFx1MUY0RFxcdTFGNTAtXFx1MUY1N1xcdTFGNTlcXHUxRjVCXFx1MUY1RFxcdTFGNUYtXFx1MUY3RFxcdTFGODAtXFx1MUZCNFxcdTFGQjYtXFx1MUZCQ1xcdTFGQkVcXHUxRkMyLVxcdTFGQzRcXHUxRkM2LVxcdTFGQ0NcXHUxRkQwLVxcdTFGRDNcXHUxRkQ2LVxcdTFGREJcXHUxRkUwLVxcdTFGRUNcXHUxRkYyLVxcdTFGRjRcXHUxRkY2LVxcdTFGRkNcXHUyMDcxXFx1MjA3RlxcdTIwOTAtXFx1MjA5Q1xcdTIxMDJcXHUyMTA3XFx1MjEwQS1cXHUyMTEzXFx1MjExNVxcdTIxMTktXFx1MjExRFxcdTIxMjRcXHUyMTI2XFx1MjEyOFxcdTIxMkEtXFx1MjEyRFxcdTIxMkYtXFx1MjEzOVxcdTIxM0MtXFx1MjEzRlxcdTIxNDUtXFx1MjE0OVxcdTIxNEVcXHUyMTgzXFx1MjE4NFxcdTJDMDAtXFx1MkNFNFxcdTJDRUItXFx1MkNFRVxcdTJDRjJcXHUyQ0YzXFx1MkQwMC1cXHUyRDI1XFx1MkQyN1xcdTJEMkRcXHUyRDMwLVxcdTJENjdcXHUyRDZGXFx1MkQ4MC1cXHUyRDk2XFx1MkRBMC1cXHUyREE2XFx1MkRBOC1cXHUyREFFXFx1MkRCMC1cXHUyREI2XFx1MkRCOC1cXHUyREJFXFx1MkRDMC1cXHUyREM2XFx1MkRDOC1cXHUyRENFXFx1MkREMC1cXHUyREQ2XFx1MkREOC1cXHUyRERFXFx1MkUyRlxcdTMwMDVcXHUzMDA2XFx1MzAzMS1cXHUzMDM1XFx1MzAzQlxcdTMwM0NcXHUzMDQxLVxcdTMwOTZcXHUzMDlELVxcdTMwOUZcXHUzMEExLVxcdTMwRkFcXHUzMEZDLVxcdTMwRkZcXHUzMTA1LVxcdTMxMkZcXHUzMTMxLVxcdTMxOEVcXHUzMUEwLVxcdTMxQkZcXHUzMUYwLVxcdTMxRkZcXHUzNDAwLVxcdTREQkZcXHU0RTAwLVxcdUE0OENcXHVBNEQwLVxcdUE0RkRcXHVBNTAwLVxcdUE2MENcXHVBNjEwLVxcdUE2MUZcXHVBNjJBXFx1QTYyQlxcdUE2NDAtXFx1QTY2RVxcdUE2N0YtXFx1QTY5RFxcdUE2QTAtXFx1QTZFNVxcdUE3MTctXFx1QTcxRlxcdUE3MjItXFx1QTc4OFxcdUE3OEItXFx1QTdDQVxcdUE3RDBcXHVBN0QxXFx1QTdEM1xcdUE3RDUtXFx1QTdEOVxcdUE3RjItXFx1QTgwMVxcdUE4MDMtXFx1QTgwNVxcdUE4MDctXFx1QTgwQVxcdUE4MEMtXFx1QTgyMlxcdUE4NDAtXFx1QTg3M1xcdUE4ODItXFx1QThCM1xcdUE4RjItXFx1QThGN1xcdUE4RkJcXHVBOEZEXFx1QThGRVxcdUE5MEEtXFx1QTkyNVxcdUE5MzAtXFx1QTk0NlxcdUE5NjAtXFx1QTk3Q1xcdUE5ODQtXFx1QTlCMlxcdUE5Q0ZcXHVBOUUwLVxcdUE5RTRcXHVBOUU2LVxcdUE5RUZcXHVBOUZBLVxcdUE5RkVcXHVBQTAwLVxcdUFBMjhcXHVBQTQwLVxcdUFBNDJcXHVBQTQ0LVxcdUFBNEJcXHVBQTYwLVxcdUFBNzZcXHVBQTdBXFx1QUE3RS1cXHVBQUFGXFx1QUFCMVxcdUFBQjVcXHVBQUI2XFx1QUFCOS1cXHVBQUJEXFx1QUFDMFxcdUFBQzJcXHVBQURCLVxcdUFBRERcXHVBQUUwLVxcdUFBRUFcXHVBQUYyLVxcdUFBRjRcXHVBQjAxLVxcdUFCMDZcXHVBQjA5LVxcdUFCMEVcXHVBQjExLVxcdUFCMTZcXHVBQjIwLVxcdUFCMjZcXHVBQjI4LVxcdUFCMkVcXHVBQjMwLVxcdUFCNUFcXHVBQjVDLVxcdUFCNjlcXHVBQjcwLVxcdUFCRTJcXHVBQzAwLVxcdUQ3QTNcXHVEN0IwLVxcdUQ3QzZcXHVEN0NCLVxcdUQ3RkJcXHVGOTAwLVxcdUZBNkRcXHVGQTcwLVxcdUZBRDlcXHVGQjAwLVxcdUZCMDZcXHVGQjEzLVxcdUZCMTdcXHVGQjFEXFx1RkIxRi1cXHVGQjI4XFx1RkIyQS1cXHVGQjM2XFx1RkIzOC1cXHVGQjNDXFx1RkIzRVxcdUZCNDBcXHVGQjQxXFx1RkI0M1xcdUZCNDRcXHVGQjQ2LVxcdUZCQjFcXHVGQkQzLVxcdUZEM0RcXHVGRDUwLVxcdUZEOEZcXHVGRDkyLVxcdUZEQzdcXHVGREYwLVxcdUZERkJcXHVGRTcwLVxcdUZFNzRcXHVGRTc2LVxcdUZFRkNcXHVGRjIxLVxcdUZGM0FcXHVGRjQxLVxcdUZGNUFcXHVGRjY2LVxcdUZGQkVcXHVGRkMyLVxcdUZGQzdcXHVGRkNBLVxcdUZGQ0ZcXHVGRkQyLVxcdUZGRDdcXHVGRkRBLVxcdUZGREMnLFxuICB9LFxufVxuXG4vLyBzZXBhcmF0b3JzIG1hdGNoZXMgYWxsIGxhbmd1YWdlcyB3b3JkIHNlcGFyYXRvcnMgYW5kIHNwZWNpYWwgY2hhcmFjdGVyc1xuZXhwb3J0IGNvbnN0IHNlcGFyYXRvcnMgPSBuZXcgUmVnRXhwKCdbXicgKyB1bmljb2RlUmVnZXguTC5ibXAgKyAnXSsoPzwhW1xcXFx4MjdcXFxceDYwXFxcXHUyMDE5XSknLCAnZ3UnKVxuXG5leHBvcnQgY29uc3QgbWF0Y2hEb21haW5zID0gbmV3IFJlZ0V4cCgnKFtBLVphLXowLTktXSsuKStjb20oL1MqfFteJyArIHVuaWNvZGVSZWdleC5MLmJtcCArICddKScsICdnJykiLCIvKlxuQ29weXJpZ2h0IDIwMjMgTml0byBULk0uXG5MaWNlbnNlIGh0dHBzOi8vd3d3LmFwYWNoZS5vcmcvbGljZW5zZXMvTElDRU5TRS0yLjAgQXBhY2hlLTIuMFxuQXV0aG9yIE5pdG8gVC5NLiAoaHR0cHM6Ly9naXRodWIuY29tL25pdG90bSlcblBhY2thZ2UgbnBtanMuY29tL3BhY2thZ2UvZWxkXG4qL1xuXG4vLyBKUyBkb2VzIG5vdCBhbGxvdyByYXcgYnl0ZSBzdHJpbmdzLCBVaW50OEFycmF5XFxoZXggYWRkcyBjb21wbGV4aXR5IGFuZCBhIGhlYXZpZXIgZGF0YWJhc2UuXG4vLyBBIGRpY3Rpb25hcnkgZm9yIGludmFsaWQgVVRGLTggYnl0ZXMgc29sdmVzIGFsbCBwcm9ibGVtcy5cbmV4cG9ydCBjb25zdCBkaWN0aW9uYXJ5ID0gW1xuICAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJyxcbiAgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnXFwnJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJyxcbiAgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsXG4gICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLFxuICAnICcsICcgJywgJyAnLCAnICcsICdgJywgJ2EnLCAnYicsICdjJywgJ2QnLCAnZScsICdmJywgJ2cnLCAnaCcsICdpJywgJ2onLCAnaycsICdsJywgJ20nLCAnbicsICdvJywgJ3AnLCAncScsICdyJyxcbiAgJ3MnLCAndCcsICd1JywgJ3YnLCAndycsICd4JywgJ3knLCAneicsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnTScsICcyJywgJ1InLCAnSicsICdPJywgJ1AnLCAneycsICfDpCcsICc+JywgJ8OiJyxcbiAgJ8O8JywgJ8OpJywgJ18nLCAnUScsICfDoScsICfDtCcsICfDqycsICfDpScsICfDtScsICfDqCcsICfDrycsICdaJywgJ8O7JywgJ30nLCAnw6AnLCAnMycsICfDuScsICfDiScsICdZJywgJ8OuJywgJ8OtJywgJ10nLCAnfCcsXG4gICcpJywgJ8O/JywgJ34nLCAnMScsICdWJywgJ0QnLCAnVCcsICc0JywgJzgnLCAnRicsICdJJywgJ0snLCAnNycsICdXJywgJ1MnLCAnLycsICdFJywgJ0InLCAnNScsICc7JywgJ04nLCAnQycsICfDqicsXG4gICcqJywgJ1gnLCAnPScsICdeJywgJzonLCAnWycsICdIJywgJ8OyJywgJyAnLCAnICcsICfCoicsICchJywgJygnLCAnLCcsICfDnycsICcgJywgJ8O4JywgJ8OzJywgJyAnLCAnICcsICcgJywgJyAnLCAnVScsXG4gICfDticsICc2JywgJ0AnLCAnw4AnLCAnw4EnLCAnICcsICc8JywgJ8O9JywgJ0cnLCAnLScsICdBJywgJ8OxJywgJ8O6JywgJyAnLCAnICcsICcgJywgJyAnLCAnJCcsICdMJywgJ8OmJywgJz8nLCAnMCcsICdcIicsXG4gICcjJywgJyUnLCAnJicsICcrJywgJ8OsJywgJzknLCAnLicsICfDpycsICcgJywgJ8K1JywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLCAnICcsICcgJywgJyAnLFxuICAnICcsICcgJywgJyAnXVxuIiwiLypcbkNvcHlyaWdodCAyMDIzIE5pdG8gVC5NLlxuTGljZW5zZSBodHRwczovL3d3dy5hcGFjaGUub3JnL2xpY2Vuc2VzL0xJQ0VOU0UtMi4wIEFwYWNoZS0yLjBcbkF1dGhvciBOaXRvIFQuTS4gKGh0dHBzOi8vZ2l0aHViLmNvbS9uaXRvdG0pXG5QYWNrYWdlIG5wbWpzLmNvbS9wYWNrYWdlL2VsZFxuKi9cblxuLyoqXG4gKiBDb252ZXJ0cyBuZ3JhbSBkYXRhYmFzZSBsYW5ndWFnZSBpbmRleGVzIChpbnRlZ2VyKSB0byBJU08gNjM5LTEgY29kZVxuICpcbiAqIEBwYXJhbSB7QXJyYXl9IGxhbmd1YWdlU2V0XG4gKiBAcGFyYW0ge09iamVjdH0gZGVmYXVsdExhbmd1YWdlc1xuICogQHJldHVybnMge09iamVjdH1cbiAqL1xuZXhwb3J0IGZ1bmN0aW9uIGlzb0xhbmd1YWdlcyAobGFuZ3VhZ2VTZXQsIGRlZmF1bHRMYW5ndWFnZXMpIHtcbiAgbGV0IGxhbmd1YWdlQ29kZXMgPSB7fVxuICBmb3IgKGxldCBsYW5nSUQgb2YgbGFuZ3VhZ2VTZXQpIHtcbiAgICBsYW5ndWFnZUNvZGVzW2xhbmdJRF0gPSBkZWZhdWx0TGFuZ3VhZ2VzW2xhbmdJRF1cbiAgfVxuICByZXR1cm4gbGFuZ3VhZ2VDb2Rlc1xufVxuIiwiLypcbkNvcHlyaWdodCAyMDIzIE5pdG8gVC5NLlxuTGljZW5zZSBodHRwczovL3d3dy5hcGFjaGUub3JnL2xpY2Vuc2VzL0xJQ0VOU0UtMi4wIEFwYWNoZS0yLjBcbkF1dGhvciBOaXRvIFQuTS4gKGh0dHBzOi8vZ2l0aHViLmNvbS9uaXRvdG0pXG5QYWNrYWdlIG5wbWpzLmNvbS9wYWNrYWdlL2VsZFxuKi9cblxuaW1wb3J0IHsgYXZnU2NvcmUgfSBmcm9tICcuL2F2Z1Njb3JlLmpzJ1xuXG5leHBvcnQgY2xhc3MgTGFuZ3VhZ2VSZXN1bHQge1xuICAvKipcbiAgICogQ3JlYXRlcyBhbiBpbnN0YW5jZSBvZiBMYW5ndWFnZVJlc3VsdC5cbiAgICpcbiAgICogQHBhcmFtIHtzdHJpbmd9IGxhbmd1YWdlXG4gICAqIEBwYXJhbSB7T2JqZWN0fSByZXN1bHRzXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBudW1OZ3JhbXNcbiAgICogQHBhcmFtIHtPYmplY3R9IGxhbmdDb2Rlc1xuICAgKiBAbWVtYmVyb2YgTGFuZ3VhZ2VSZXN1bHRcbiAgICovXG4gIGNvbnN0cnVjdG9yIChsYW5ndWFnZSwgcmVzdWx0cywgbnVtTmdyYW1zLCBsYW5nQ29kZXMpIHtcbiAgICB0aGlzLmxhbmd1YWdlID0gbGFuZ3VhZ2VcbiAgICB0aGlzLmdldFNjb3JlcyA9ICgpID0+IGdldFNjb3JlcyhyZXN1bHRzLCBsYW5nQ29kZXMpIC8vIHJldHVybnMgb2JqZWN0XG4gICAgdGhpcy5pc1JlbGlhYmxlID0gKCkgPT4gaXNSZWxpYWJsZShyZXN1bHRzLCBudW1OZ3JhbXMsIGxhbmd1YWdlKSAvLyByZXR1cm5zIGJvb2xlYW5cbiAgfVxufVxuXG4vKipcbiAqIEBwYXJhbSB7b2JqZWN0fSByZXN1bHRzXG4gKiBAcGFyYW0ge251bWJlcn0gbnVtTmdyYW1zXG4gKiBAcGFyYW0ge3N0cmluZ30gbGFuZ3VhZ2VcbiAqIEByZXR1cm5zIHtib29sZWFufVxuICovXG5mdW5jdGlvbiBpc1JlbGlhYmxlIChyZXN1bHRzLCBudW1OZ3JhbXMsIGxhbmd1YWdlKSB7XG4gIGlmICghcmVzdWx0cy5sZW5ndGggfHwgbnVtTmdyYW1zIDwgMykge1xuICAgIHJldHVybiBmYWxzZVxuICB9XG4gIGNvbnN0IG5leHRTY29yZSA9IHJlc3VsdHMubGVuZ3RoID4gMSA/IHJlc3VsdHNbMV1bMF0gOiAwXG4gIC8vIEEgbWluaW11bSBvZiBhIDI0JSBwZXIgbmdyYW0gc2NvcmUgZnJvbSBhdmVyYWdlXG4gIHJldHVybiAhKGF2Z1Njb3JlW2xhbmd1YWdlXSAqIDAuMjQgPiByZXN1bHRzWzBdWzFdIC8gbnVtTmdyYW1zIHx8IDAuMDEgPlxuICAgIE1hdGguYWJzKHJlc3VsdHNbMF1bMV0gLSBuZXh0U2NvcmUpKVxuXG59XG5cbi8qKlxuICogQ29udmVydHMgaW50ZXJuYWwgbXVsdGktYXJyYXkgcmVzdWx0cywgd2l0aCBpbnRlZ2VyIGxhbmd1YWdlIGNvZGVzLCB0byBmaW5hbCBvYmplY3Qgd2l0aCBJU08gNjM5LTEgY29kZXNcbiAqIEBwYXJhbSB7T2JqZWN0fSByZXN1bHRzXG4gKiBAcGFyYW0ge09iamVjdH0gbGFuZ0NvZGVzXG4gKiBAcmV0dXJucyB7T2JqZWN0fVxuICovXG5mdW5jdGlvbiBnZXRTY29yZXMgKHJlc3VsdHMsIGxhbmdDb2Rlcykge1xuICBsZXQgc2NvcmVzID0ge31cbiAgbGV0IGtleVxuICBmb3IgKGtleSBpbiByZXN1bHRzKSB7XG4gICAgbGV0IHNjb3JlID0gcmVzdWx0c1trZXldWzFdXG4gICAgaWYgKHNjb3JlID09PSAwKSB7XG4gICAgICBicmVha1xuICAgIH1cbiAgICBzY29yZXNbbGFuZ0NvZGVzW3Jlc3VsdHNba2V5XVswXV1dID0gc2NvcmVcbiAgfVxuICByZXR1cm4gc2NvcmVzXG59XG5cbiIsIi8qXG5Db3B5cmlnaHQgMjAyMyBOaXRvIFQuTS5cbkxpY2Vuc2UgaHR0cHM6Ly93d3cuYXBhY2hlLm9yZy9saWNlbnNlcy9MSUNFTlNFLTIuMCBBcGFjaGUtMi4wXG5BdXRob3IgTml0byBULk0uIChodHRwczovL2dpdGh1Yi5jb20vbml0b3RtKVxuUGFja2FnZSBucG1qcy5jb20vcGFja2FnZS9lbGRcbiovXG5cbmltcG9ydCB7IGlzb0xhbmd1YWdlcyB9IGZyb20gJy4vaXNvTGFuZ3VhZ2VzLmpzJ1xuXG5leHBvcnQgY29uc3Qgc2F2ZUxhbmd1YWdlU3Vic2V0ID0gKGZ1bmN0aW9uICgpIHtcbiAgLyoqXG4gICAqIENyZWF0ZXMgYSBuZ3JhbXMgZGF0YWJhc2UgZmlsZSBkb3dubG9hZCwgb25seSB3aXRoIHRoZSBsYW5ndWFnZXMgaW5jbHVkZWQgaW4gdGhlIGxhbmdBcnJheSBzdWJzZXRcbiAgICpcbiAgICogQHBhcmFtIHtBcnJheX0gbGFuZ0FycmF5XG4gICAqIEBwYXJhbSB7T2JqZWN0fSBuZ3JhbXNcbiAgICogQHBhcmFtIHtPYmplY3R9IGRlZmF1bHRMYW5ndWFnZXNcbiAgICogQHBhcmFtIHtzdHJpbmd9IHR5cGVcbiAgICovXG4gIGZ1bmN0aW9uIHNhdmVTdWJzZXQgKGxhbmdBcnJheSwgbmdyYW1zLCBkZWZhdWx0TGFuZ3VhZ2VzLCB0eXBlKSB7XG4gICAgLy8gbGFuZ0FycmF5IGxhbmd1YWdlcyBhcmUgYWxyZWFkeSB2YWxpZGF0ZWQgYnkgZHluYW1pY0xhbmdTdWJzZXQoKVxuICAgIGlmICghbGFuZ0FycmF5Lmxlbmd0aCkge1xuICAgICAgcmV0dXJuICdObyBsYW5ndWFnZXMgZm91bmQnXG4gICAgfVxuICAgIGxldCBuZXdOZ3JhbXMgPSBKU09OLnBhcnNlKEpTT04uc3RyaW5naWZ5KG5ncmFtcykpIC8vIERlZXAgY29weSBvZiBvYmplY3RcbiAgICBjb25zdCBmaWxlID0gJ25ncmFtcycgKyB0eXBlICsgJy0nICsgbGFuZ0FycmF5Lmxlbmd0aCArICdfJyArIERhdGUubm93KCkgKyAnLmpzJ1xuXG4gICAgZm9yIChsZXQgbmdyYW0gaW4gbmV3TmdyYW1zKSB7XG4gICAgICBmb3IgKGxldCBpZCBpbiBuZXdOZ3JhbXNbbmdyYW1dKSB7XG4gICAgICAgIGlmIChsYW5nQXJyYXkuaW5kZXhPZihwYXJzZUludChpZCkpID09PSAtMSkge1xuICAgICAgICAgIGRlbGV0ZSBuZXdOZ3JhbXNbbmdyYW1dW2lkXVxuICAgICAgICB9XG4gICAgICB9XG5cbiAgICAgIGlmIChPYmplY3Qua2V5cyhuZXdOZ3JhbXNbbmdyYW1dKS5sZW5ndGggPT09IDApIHtcbiAgICAgICAgZGVsZXRlIG5ld05ncmFtc1tuZ3JhbV1cbiAgICAgIH1cbiAgICB9XG5cbiAgICBkb3dubG9hZCgnLy8gQ29weXJpZ2h0IDIwMjMgTml0byBULk0uIFsgQXBhY2hlIDIuMCBMaWNlbmNlIGh0dHBzOi8vd3d3LmFwYWNoZS5vcmcvbGljZW5zZXMvTElDRU5TRS0yLjAgXVxcbicgK1xuICAgICAgJ2V4cG9ydCBjb25zdCBuZ3JhbXNEYXRhID0ge1xcbicgK1xuICAgICAgJyAgIHR5cGU6IFwiJyArIHR5cGUgKyAnXCIsXFxuJyArXG4gICAgICAnICAgbGFuZ3VhZ2VzOiAnICsgSlNPTi5zdHJpbmdpZnkoaXNvTGFuZ3VhZ2VzKGxhbmdBcnJheSwgZGVmYXVsdExhbmd1YWdlcykpICsgJyxcXG4nICtcbiAgICAgICcgICBpc1N1YnNldDogdHJ1ZSxcXG4nICtcbiAgICAgICcgICBuZ3JhbXM6ICcgKyBuZ3JhbUV4cG9ydChuZXdOZ3JhbXMpICsgJ1xcbicgK1xuICAgICAgJ30nLCBmaWxlLCAnanMnKVxuICB9XG5cbiAgLyoqXG4gICAqIEBwYXJhbSB7T2JqZWN0fSBuZ3JhbXNcbiAgICogQHJldHVybnMge3N0cmluZ31cbiAgICovXG4gIGZ1bmN0aW9uIG5ncmFtRXhwb3J0IChuZ3JhbXMpIHtcbiAgICBpZiAodHlwZW9mIG5ncmFtcyA9PT0gJ29iamVjdCcgJiYgbmdyYW1zKSB7XG4gICAgICBsZXQgdG9JbXBsb2RlID0gW11cbiAgICAgIGZvciAoY29uc3QgcHJvcGVydHkgaW4gbmdyYW1zKSB7XG4gICAgICAgIHRvSW1wbG9kZS5wdXNoKCdcXCcnICsgcHJvcGVydHkucmVwbGFjZSgvJy9nLCAnXFxcXFxcJycpICsgJ1xcJzonICsgam9pbk51bWJlcnMobmdyYW1zW3Byb3BlcnR5XSkpXG4gICAgICB9XG4gICAgICByZXR1cm4gJ3snICsgdG9JbXBsb2RlLmpvaW4oJywnKSArICd9J1xuICAgIH1cbiAgfVxuXG4gIC8qKlxuICAgKiBAcGFyYW0ge09iamVjdH0gb2JqXG4gICAqIEByZXR1cm5zIHtzdHJpbmd9XG4gICAqL1xuICBmdW5jdGlvbiBqb2luTnVtYmVycyAob2JqKSB7XG4gICAgbGV0IHRvSW1wbG9kZSA9IFtdXG4gICAgZm9yIChjb25zdCBwcm9wZXJ0eSBpbiBvYmopIHtcbiAgICAgIHRvSW1wbG9kZS5wdXNoKHByb3BlcnR5ICsgJzonICsgb2JqW3Byb3BlcnR5XSlcbiAgICB9XG4gICAgcmV0dXJuICd7JyArIHRvSW1wbG9kZS5qb2luKCcsJykgKyAnfSdcbiAgfVxuXG4gIC8qKlxuICAgKiBUcmlnZ2VycyBmaWxlIGRvd25sb2FkIGF0IHRoZSB3ZWIgYnJvd3NlclxuICAgKlxuICAgKiBAcGFyYW0ge3N0cmluZ30gZGF0YVxuICAgKiBAcGFyYW0ge3N0cmluZ30gZmlsZW5hbWVcbiAgICogQHBhcmFtIHtzdHJpbmd9IHR5cGVcbiAgICovXG4gIGZ1bmN0aW9uIGRvd25sb2FkIChkYXRhLCBmaWxlbmFtZSwgdHlwZSkge1xuICAgIGNvbnN0IGZpbGUgPSBuZXcgQmxvYihbZGF0YV0sIHsgdHlwZTogdHlwZSB9KVxuICAgIGlmICh0eXBlb2Ygd2luZG93ID09PSAndW5kZWZpbmVkJykge1xuICAgICAgY29uc29sZS5sb2coJ3NhdmVTdWJzZXQoKSBpcyBvbmx5IGF2YWlsYWJsZSBhdCB0aGUgV2ViIEJyb3dzZXInKVxuICAgICAgcmV0dXJuXG4gICAgfVxuICAgIGlmICh3aW5kb3cubmF2aWdhdG9yLm1zU2F2ZU9yT3BlbkJsb2IpIHtcbiAgICAgIC8vIElFMTArXG4gICAgICB3aW5kb3cubmF2aWdhdG9yLm1zU2F2ZU9yT3BlbkJsb2IoZmlsZSwgZmlsZW5hbWUpXG4gICAgfSBlbHNlIHtcbiAgICAgIC8vIE90aGVyc1xuICAgICAgbGV0IGEgPSBkb2N1bWVudC5jcmVhdGVFbGVtZW50KCdhJylcbiAgICAgIGxldCB1cmwgPSBVUkwuY3JlYXRlT2JqZWN0VVJMKGZpbGUpXG4gICAgICBhLmhyZWYgPSB1cmxcbiAgICAgIGEuZG93bmxvYWQgPSBmaWxlbmFtZVxuICAgICAgZG9jdW1lbnQuYm9keS5hcHBlbmRDaGlsZChhKVxuICAgICAgYS5jbGljaygpXG4gICAgICBzZXRUaW1lb3V0KGZ1bmN0aW9uICgpIHtcbiAgICAgICAgZG9jdW1lbnQuYm9keS5yZW1vdmVDaGlsZChhKVxuICAgICAgICB3aW5kb3cuVVJMLnJldm9rZU9iamVjdFVSTCh1cmwpXG4gICAgICB9LCAwKVxuICAgIH1cbiAgfVxuXG4gIHJldHVybiB7XG4gICAgc2F2ZVN1YnNldDogc2F2ZVN1YnNldFxuICB9XG59KSgpXG4iLCIvKlxuQ29weXJpZ2h0IDIwMjMgTml0byBULk0uXG5MaWNlbnNlIGh0dHBzOi8vd3d3LmFwYWNoZS5vcmcvbGljZW5zZXMvTElDRU5TRS0yLjAgQXBhY2hlLTIuMFxuQXV0aG9yIE5pdG8gVC5NLiAoaHR0cHM6Ly9naXRodWIuY29tL25pdG90bSlcblBhY2thZ2UgbnBtanMuY29tL3BhY2thZ2UvZWxkXG4qL1xuXG5pbXBvcnQgeyBsYW5ndWFnZURhdGEsIGxvYWROZ3JhbXMgfSBmcm9tICcuL2xhbmd1YWdlRGF0YS5qcydcbmltcG9ydCB7IHNlcGFyYXRvcnMsIG1hdGNoRG9tYWlucyB9IGZyb20gJy4vcmVnZXhQYXR0ZXJucy5qcydcbmltcG9ydCB7IGRpY3Rpb25hcnkgfSBmcm9tICcuL2RpY3Rpb25hcnkuanMnXG5pbXBvcnQgeyBpc29MYW5ndWFnZXMgfSBmcm9tICcuL2lzb0xhbmd1YWdlcy5qcydcbmltcG9ydCB7IExhbmd1YWdlUmVzdWx0IH0gZnJvbSAnLi9MYW5ndWFnZVJlc3VsdC5qcydcbmltcG9ydCB7IHNhdmVMYW5ndWFnZVN1YnNldCB9IGZyb20gJy4vc2F2ZUxhbmd1YWdlU3Vic2V0LmRldi5qcydcblxuXG4vLyBQcm9qZWN0IGlzIEVTMjAxNVxuY29uc3QgZWxkID0gKGZ1bmN0aW9uICgpIHtcblx0Ly8gQWRkIGluaXRpYWxpemF0aW9uIGZ1bmN0aW9uXG5cdGFzeW5jIGZ1bmN0aW9uIGluaXQoc2l6ZSA9ICdNJykge1xuXHRcdFx0YXdhaXQgbG9hZE5ncmFtcyhzaXplKTtcblx0fVxuXG4gIHJldHVybiB7XG4gICAgaW5pdCxcbiAgICBkZXRlY3QsXG4gICAgY2xlYW5UZXh0LFxuICAgIGR5bmFtaWNMYW5nU3Vic2V0LFxuICAgIHNhdmVTdWJzZXQsXG4gICAgbG9hZE5ncmFtcyxcbiAgICBpbmZvLFxuICB9XG59KSgpXG5cbi8qKiBAdHlwZSB7Ym9vbGVhbnxBcnJheX0gKi9cbmxldCBzdWJzZXQgPSBmYWxzZVxuXG4vKiogQHR5cGUge2Jvb2xlYW59IFdoZW4gdHJ1ZSwgZGV0ZWN0KCkgY2xlYW5zIGlucHV0IHRleHQgd2l0aCBnZXRDbGVhblR4dCgpICovXG5sZXQgZG9DbGVhblRleHQgPSBmYWxzZVxuXG4vKipcbiAqIGRldGVjdCgpIGlkZW50aWZpZXMgdGhlIG5hdHVyYWwgbGFuZ3VhZ2Ugb2YgYSBVVEYtOCBzdHJpbmdcbiAqIFJldHVybnMgYW4gb2JqZWN0LCB3aXRoIGEgdmFyaWFibGUgbmFtZWQgJ2xhbmd1YWdlJywgd2l0aCBhbiBJU08gNjM5LTEgY29kZSBvciBlbXB0eSBzdHJpbmdcbiAqIHsgbGFuZ3VhZ2U6ICdlcycsIGdldFNjb3JlcygpOiB7J2VzJzogMC41LCAnZXQnOiAwLjJ9LCBpc1JlbGlhYmxlKCk6IHRydWUgfVxuICpcbiAqIEBwYXJhbSB7c3RyaW5nfSB0ZXh0IFVURi04XG4gKiBAcmV0dXJucyB7e2xhbmd1YWdlOiBzdHJpbmcsIGdldFNjb3JlcygpOiBPYmplY3QsIGlzUmVsaWFibGUoKTogYm9vbGVhbn19IGNsYXNzIExhbmd1YWdlUmVzdWx0XG4gKi9cbmZ1bmN0aW9uIGRldGVjdCAodGV4dCkge1xuICBpZiAodHlwZW9mIHRleHQgIT09ICdzdHJpbmcnKSByZXR1cm4gbmV3IExhbmd1YWdlUmVzdWx0KCcnLCAwLCAwLHt9KVxuXG4gIGlmIChkb0NsZWFuVGV4dCkge1xuICAgIC8vIFJlbW92ZXMgVXJscywgZW1haWxzLCBhbHBoYW51bWVyaWNhbCAmIG51bWJlcnNcbiAgICB0ZXh0ID0gZ2V0Q2xlYW5UeHQodGV4dClcbiAgfVxuXG4gIGNvbnN0IGJ5dGVXb3JkcyA9IHRleHRQcm9jZXNzb3IodGV4dClcbiAgY29uc3QgYnl0ZU5ncmFtcyA9IGdldEJ5dGVOZ3JhbXMoYnl0ZVdvcmRzKVxuICBjb25zdCBudW1OZ3JhbXMgPSBPYmplY3Qua2V5cyhieXRlTmdyYW1zKS5sZW5ndGhcbiAgbGV0IHJlc3VsdHMgPSBjYWxjdWxhdGVTY29yZXMoYnl0ZU5ncmFtcywgbnVtTmdyYW1zKVxuICBsZXQgbGFuZ3VhZ2UgPSAnJ1xuXG4gIGlmIChzdWJzZXQpIHtcbiAgICByZXN1bHRzID0gZmlsdGVyTGFuZ1N1YnNldChyZXN1bHRzKVxuICB9XG4gIGlmIChyZXN1bHRzLmxlbmd0aCA+IDApIHtcbiAgICByZXN1bHRzLnNvcnQoKGEsIGIpID0+IGJbMV0gLSBhWzFdKVxuICAgIGxhbmd1YWdlID0gbGFuZ3VhZ2VEYXRhLmxhbmdDb2Rlc1tyZXN1bHRzWzBdWzBdXVxuICB9XG4gIHJldHVybiBuZXcgTGFuZ3VhZ2VSZXN1bHQobGFuZ3VhZ2UsIHJlc3VsdHMsIG51bU5ncmFtcywgbGFuZ3VhZ2VEYXRhLmxhbmdDb2Rlcylcbn1cblxuLyoqXG4gKiBQdWJsaWMgZnVuY3Rpb24gdG8gY2hhbmdlIGRvQ2xlYW5UZXh0IHZhbHVlXG4gKlxuICogQHBhcmFtIHtib29sZWFufSBib29sXG4gKi9cbmZ1bmN0aW9uIGNsZWFuVGV4dCAoYm9vbCkge1xuICBkb0NsZWFuVGV4dCA9IEJvb2xlYW4oYm9vbClcbn1cblxuLyoqXG4gKiBSZW1vdmVzIHBhcnRzIG9mIGEgc3RyaW5nLCB0aGF0IG1heSBiZSBjb25zaWRlcmVkIGFzIFwibm9pc2VcIiBmb3IgbGFuZ3VhZ2UgZGV0ZWN0aW9uXG4gKlxuICogQHBhcmFtIHtzdHJpbmd9IHN0clxuICogQHJldHVybnMge3N0cmluZ31cbiAqL1xuZnVuY3Rpb24gZ2V0Q2xlYW5UeHQgKHN0cikge1xuICAvLyBSZW1vdmUgVVJMU1xuICBzdHIgPSBzdHIucmVwbGFjZSgvW2h3XSgodHRwcz86XFwvXFwvKHd3d1xcLik/KXx3d1xcLikoW15cXHMvPy4jLV0rXFwuPykrKFxcL1xcUyopPy9naSwgJyAnKVxuICAvLyBSZW1vdmUgZW1haWxzXG4gIHN0ciA9IHN0ci5yZXBsYWNlKC9bYS16QS1aMC05LiEkJSbigJkrX2AtXStAW0EtWmEtejAtOS4tXStcXC5bQS1aYS16MC05LV17Miw2NH0vZywgJyAnKVxuICAvLyBSZW1vdmUgLmNvbSBkb21haW5zXG4gIHN0ciA9IHN0ci5yZXBsYWNlKG1hdGNoRG9tYWlucywgJyAnKVxuICAvLyBSZW1vdmUgYWxwaGFudW1lcmljYWwvbnVtYmVyIGNvZGVzXG4gIHN0ciA9IHN0ci5yZXBsYWNlKC9bYS16QS1aXSpbMC05XStbYS16QS1aMC05XSovZywgJyAnKVxuICByZXR1cm4gc3RyXG59XG5cbi8qKlxuICogQHBhcmFtIHtzdHJpbmd9IHRleHRcbiAqIEByZXR1cm5zIHtBcnJheX1cbiAqL1xuZnVuY3Rpb24gdGV4dFByb2Nlc3NvciAodGV4dCkge1xuICB0ZXh0ID0gdGV4dC5zdWJzdHJpbmcoMCwgMTAwMClcbiAgLy8gTm9ybWFsaXplIHNwZWNpYWwgY2hhcmFjdGVycy93b3JkIHNlcGFyYXRvcnNcbiAgdGV4dCA9IHRleHQucmVwbGFjZShzZXBhcmF0b3JzLCAnICcpXG4gIHRleHQgPSB0ZXh0LnRyaW0oKS50b0xvd2VyQ2FzZSgpXG4gIHJldHVybiBzdHJUb1V0ZjhCeXRlcyh0ZXh0KSAvLyByZXR1cm5zIGFycmF5IG9mIHdvcmRzXG59XG5cbi8qKlxuICogR2V0cyBOZ3JhbXMgZnJvbSBhIGdpdmVuIGFycmF5IG9mIHdvcmRzXG4gKlxuICogQHBhcmFtIHtBcnJheX0gd29yZHNcbiAqIEByZXR1cm5zIHtPYmplY3R9XG4gKi9cbmZ1bmN0aW9uIGdldEJ5dGVOZ3JhbXMgKHdvcmRzKSB7XG4gIGxldCBieXRlTmdyYW1zID0ge31cbiAgbGV0IGNvdW50TmdyYW1zID0gMFxuICBsZXQgdGhpc0J5dGVzXG4gIGxldCBqXG5cbiAgZm9yIChsZXQga2V5IGluIHdvcmRzKSB7XG4gICAgbGV0IHdvcmQgPSB3b3Jkc1trZXldXG4gICAgbGV0IGxlbiA9IHdvcmQubGVuZ3RoXG4gICAgaWYgKGxlbiA+IDcwKSB7XG4gICAgICBsZW4gPSA3MFxuICAgIH1cblxuICAgIGZvciAoaiA9IDA7IGogKyA0IDwgbGVuOyBqICs9IDMsICsrY291bnROZ3JhbXMpIHtcbiAgICAgIHRoaXNCeXRlcyA9IChqID09PSAwID8gJyAnIDogJycpICsgd29yZC5zdWJzdHJpbmcoaiwgaiArIDQpXG4gICAgICBieXRlTmdyYW1zW3RoaXNCeXRlc10gPSB0eXBlb2YgYnl0ZU5ncmFtc1t0aGlzQnl0ZXNdICE9PSAndW5kZWZpbmVkJyA/IGJ5dGVOZ3JhbXNbdGhpc0J5dGVzXSArIDEgOiAxXG4gICAgfVxuICAgIHRoaXNCeXRlcyA9IChqID09PSAwID8gJyAnIDogJycpICsgd29yZC5zdWJzdHJpbmcobGVuICE9PSAzID8gbGVuIC0gNCA6IDApICsgJyAnXG4gICAgYnl0ZU5ncmFtc1t0aGlzQnl0ZXNdID0gdHlwZW9mIGJ5dGVOZ3JhbXNbdGhpc0J5dGVzXSAhPT0gJ3VuZGVmaW5lZCcgPyBieXRlTmdyYW1zW3RoaXNCeXRlc10gKyAxIDogMVxuICAgIGNvdW50TmdyYW1zKytcbiAgfVxuICAvLyBGcmVxdWVuY3kgaXMgbXVsdGlwbGllZCBieSAxNTAwMCBhdCB0aGUgbmdyYW1zIGRhdGFiYXNlLiBBIHJlZHVjZWQgbnVtYmVyICgxMzIwMCkgc2VlbXMgdG8gd29yayBiZXR0ZXIuXG4gIC8vIExpbmVhciBmb3JtdWxhcyB3ZXJlIHRyaWVkLCBkZWNyZWFzaW5nIHRoZSBtdWx0aXBsaWVyIGZvciBmZXdlciBuZ3JhbSBzdHJpbmdzLCBubyBtZWFuaW5nZnVsIGltcHJvdmVtZW50LlxuICBmb3IgKGxldCBieXRlcyBpbiBieXRlTmdyYW1zKSB7XG4gICAgYnl0ZU5ncmFtc1tieXRlc10gPSAoYnl0ZU5ncmFtc1tieXRlc10gLyBjb3VudE5ncmFtcykgKiAxMzIwMFxuICB9XG4gIHJldHVybiBieXRlTmdyYW1zXG59XG5cbi8qKlxuICogQ2FsY3VsYXRlIHNjb3JlcyBmb3IgZWFjaCBsYW5ndWFnZSBmcm9tIHRoZSBnaXZlbiBOZ3JhbXNcbiAqXG4gKiBAcGFyYW0ge09iamVjdH0gYnl0ZU5ncmFtc1xuICogQHBhcmFtIHtudW1iZXJ9IG51bU5ncmFtc1xuICogQHJldHVybnMge0FycmF5fVxuICovXG5mdW5jdGlvbiBjYWxjdWxhdGVTY29yZXMgKGJ5dGVOZ3JhbXMsIG51bU5ncmFtcykge1xuICBsZXQgYnl0ZXMsIGdsb2JhbEZyZXF1ZW5jeSwgcmVsZXZhbmN5LCBsYW5nQ291bnQsIGZyZXF1ZW5jeSwgbGFuZywgdGhpc0J5dGVcbiAgbGV0IGxhbmdTY29yZSA9IFsuLi5sYW5ndWFnZURhdGEubGFuZ1Njb3JlXVxuXG4gIGZvciAoYnl0ZXMgaW4gYnl0ZU5ncmFtcykge1xuICAgIGZyZXF1ZW5jeSA9IGJ5dGVOZ3JhbXNbYnl0ZXNdXG4gICAgdGhpc0J5dGUgPSBsYW5ndWFnZURhdGEubmdyYW1zW2J5dGVzXVxuXG4gICAgaWYgKHRoaXNCeXRlKSB7XG4gICAgICBsYW5nQ291bnQgPSBPYmplY3Qua2V5cyh0aGlzQnl0ZSkubGVuZ3RoXG4gICAgICAvLyBOZ3JhbSBzY29yZSBtdWx0aXBsaWVyLCB0aGUgZmV3ZXIgbGFuZ3VhZ2VzIGZvdW5kIHRoZSBtb3JlIHJlbGV2YW5jeS4gRm9ybXVsYSBjYW4gYmUgZmluZS10dW5lZC5cbiAgICAgIGlmIChsYW5nQ291bnQgPT09IDEpIHtcbiAgICAgICAgcmVsZXZhbmN5ID0gMjcgLy8gSGFuZHBpY2tlZCByZWxldmFuY2UgbXVsdGlwbGllciwgdHJpYWwtZXJyb3JcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIGlmIChsYW5nQ291bnQgPCAxNikge1xuICAgICAgICAgIHJlbGV2YW5jeSA9ICgxNiAtIGxhbmdDb3VudCkgLyAyICsgMVxuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIHJlbGV2YW5jeSA9IDFcbiAgICAgICAgfVxuICAgICAgfVxuICAgICAgLy8gTW9zdCB0aW1lLWNvbnN1bWluZyBsb29wLCBkbyBvbmx5IHRoZSBzdHJpY3RseSBuZWNlc3NhcnkgaW5zaWRlXG4gICAgICBmb3IgKGxhbmcgaW4gdGhpc0J5dGUpIHtcbiAgICAgICAgZ2xvYmFsRnJlcXVlbmN5ID0gdGhpc0J5dGVbbGFuZ11cbiAgICAgICAgbGFuZ1Njb3JlW2xhbmddICs9IChmcmVxdWVuY3kgPiBnbG9iYWxGcmVxdWVuY3kgPyBnbG9iYWxGcmVxdWVuY3kgLyBmcmVxdWVuY3kgOiBmcmVxdWVuY3kgLyBnbG9iYWxGcmVxdWVuY3kpICpcbiAgICAgICAgICByZWxldmFuY3kgKyAyXG4gICAgICB9XG4gICAgfVxuICB9XG5cbiAgLy8gVGhpcyBkaXZpc29yIHdpbGwgcHJvZHVjZSBhIGZpbmFsIHNjb3JlIGJldHdlZW4gMCAtIH4xLCBzY29yZSBjb3VsZCBiZSA+MS4gQ2FuIGJlIGltcHJvdmVkLlxuICBsZXQgcmVzdWx0RGl2aXNvciA9IG51bU5ncmFtcyAqIDMuMlxuICBsZXQgcmVzdWx0cyA9IFtdXG4gIGZvciAobGFuZyBpbiBsYW5nU2NvcmUpIHtcbiAgICBpZiAobGFuZ1Njb3JlW2xhbmddKSB7XG4gICAgICAvLyBKYXZhc2NyaXB0IGRvZXMgTm90IGd1YXJhbnRlZSBvYmplY3Qgb3JkZXIsIHNvIGEgbXVsdGktYXJyYXkgaXMgdXNlZFxuICAgICAgcmVzdWx0cy5wdXNoKFtwYXJzZUludChsYW5nKSwgbGFuZ1Njb3JlW2xhbmddIC8gcmVzdWx0RGl2aXNvcl0pIC8vICogbGFuZ3VhZ2VEYXRhLnNjb3JlTm9ybWFsaXplcltsYW5nXTtcbiAgICB9XG4gIH1cbiAgcmV0dXJuIHJlc3VsdHNcbn1cblxuLyoqXG4gKiBDb252ZXJ0cyBlYWNoIGJ5dGUgdG8gYSBzaW5nbGUgY2hhcmFjdGVyLCB1c2luZyBvdXIgb3duIGRpY3Rpb25hcnksIHNpbmNlIGphdmFzY3JpcHQgZG9lcyBub3QgYWxsb3cgcmF3IGJ5dGVcbiAqIHN0cmluZ3Mgb3IgaW52YWxpZCBVVEYtOCBjaGFyYWN0ZXJzLiBXZSBjb3VsZCB1c2UgVGV4dEVuY29kZXIoKSB0byBjcmVhdGUgYW4gVWludDhBcnJheSwgYW5kIHRoZW4gdHJhbnNsYXRlIHRvIG91clxuICogZGljdGlvbmFyeSwgYnV0IHRoaXMgZnVuY3Rpb24gaXMgb3ZlcmFsbCBmYXN0ZXIgYXMgaXQgZG9lcyBib3RoIGpvYnMgYXQgb25jZVxuICpcbiAqIEFsdGVybmF0aXZlcyBzdWNoIGFzIGp1c3QgdXNpbmcgVWludDhBcnJheS9oZXggZm9yIGRldGVjdGlvbiBhZGRzIGNvbXBsZXhpdHkgYW5kIG9yIGEgYmlnZ2VyIGRhdGFiYXNlXG4gKlxuICogQHBhcmFtIHtzdHJpbmd9IHN0clxuICogQHJldHVybnMge0FycmF5fVxuICovXG5mdW5jdGlvbiBzdHJUb1V0ZjhCeXRlcyAoc3RyKSB7XG4gIGxldCBlbmNvZGVkID0gJydcbiAgbGV0IHdvcmRzID0gW11cbiAgbGV0IGNvdW50Qnl0ZXMgPSAwXG4gIGNvbnN0IGN1dEFmdGVyID0gMzUwIC8vIEN1dCB0byBmaXJzdCB3aGl0ZXNwYWNlIGFmdGVyIDM1MCBieXRlIGxlbmd0aCBvZmZzZXRcbiAgY29uc3QgZW5mb3JjZUN1dEFmdGVyID0gMzgwIC8vIEN1dCBhZnRlciBhbnkgVVRGLTggY2hhcmFjdGVyIHdoZW4gc3VycGFzc2luZyAzODAgYnl0ZSBsZW5ndGhcblxuICBmb3IgKGxldCBpaSA9IDA7IGlpIDwgc3RyLmxlbmd0aDsgaWkrKykge1xuICAgIGxldCBjaGFyQ29kZSA9IHN0ci5jaGFyQ29kZUF0KGlpKVxuXG4gICAgaWYgKGNoYXJDb2RlIDwgMHg4MCkge1xuICAgICAgaWYgKGNoYXJDb2RlID09PSAzMikge1xuICAgICAgICBpZiAoZW5jb2RlZCAhPT0gJycpIHtcbiAgICAgICAgICB3b3Jkcy5wdXNoKGVuY29kZWQpXG4gICAgICAgICAgZW5jb2RlZCA9ICcnXG4gICAgICAgIH1cbiAgICAgICAgaWYgKGNvdW50Qnl0ZXMgPiBjdXRBZnRlcikge1xuICAgICAgICAgIGJyZWFrXG4gICAgICAgIH1cbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIGVuY29kZWQgKz0gc3RyW2lpXVxuICAgICAgfVxuICAgICAgY291bnRCeXRlcysrXG4gICAgfSBlbHNlIGlmIChjaGFyQ29kZSA8IDB4ODAwKSB7XG4gICAgICBlbmNvZGVkICs9IGRpY3Rpb25hcnlbMHhjMCB8IChjaGFyQ29kZSA+PiA2KV0gKyBkaWN0aW9uYXJ5WzB4ODAgfCAoY2hhckNvZGUgJiAweDNmKV1cbiAgICAgIGNvdW50Qnl0ZXMgKz0gMlxuICAgIH0gZWxzZSBpZiAoY2hhckNvZGUgPCAweGQ4MDAgfHwgY2hhckNvZGUgPj0gMHhlMDAwKSB7XG4gICAgICBlbmNvZGVkICs9IGRpY3Rpb25hcnlbMHhlMCB8IChjaGFyQ29kZSA+PiAxMildICsgZGljdGlvbmFyeVsweDgwIHwgKChjaGFyQ29kZSA+PiA2KSAmIDB4M2YpXSArXG4gICAgICAgIGRpY3Rpb25hcnlbMHg4MCB8IChjaGFyQ29kZSAmIDB4M2YpXVxuICAgICAgY291bnRCeXRlcyArPSAzXG4gICAgfSBlbHNlIHtcbiAgICAgIC8vIFVURi0xNlxuICAgICAgaWkrK1xuICAgICAgY2hhckNvZGUgPSAweDEwMDAwICsgKCgoY2hhckNvZGUgJiAweDNmZikgPDwgMTApIHwgKHN0ci5jaGFyQ29kZUF0KGlpKSAmIDB4M2ZmKSlcbiAgICAgIGVuY29kZWQgKz0gZGljdGlvbmFyeVsweGYwIHwgKGNoYXJDb2RlID4+IDE4KV0gKyBkaWN0aW9uYXJ5WzB4ODAgfCAoKGNoYXJDb2RlID4+IDEyKSAmIDB4M2YpXSArXG4gICAgICAgIGRpY3Rpb25hcnlbMHg4MCB8ICgoY2hhckNvZGUgPj4gNikgJiAweDNmKV0gKyBkaWN0aW9uYXJ5WzB4ODAgfCAoY2hhckNvZGUgJiAweDNmKV1cbiAgICAgIGNvdW50Qnl0ZXMgKz0gNFxuICAgIH1cbiAgICBpZiAoY291bnRCeXRlcyA+IGVuZm9yY2VDdXRBZnRlcikge1xuICAgICAgYnJlYWtcbiAgICB9XG4gIH1cbiAgaWYgKGVuY29kZWQgIT09ICcnKSB7XG4gICAgd29yZHMucHVzaChlbmNvZGVkKVxuICAgIC8vIEl0IGlzIGZhc3RlciB0byBidWlsZCB0aGUgYXJyYXkgdGhhbiB0byB3b3Jkcy5zcGxpdCgvICsvKS5maWx0ZXIoKHgpID0+IHggIT09ICcgJykgbGF0ZXJcbiAgfVxuICByZXR1cm4gd29yZHNcbn1cblxuLyoqXG4gKiBGaWx0ZXJzIGxhbmd1YWdlcyBub3QgaW5jbHVkZWQgaW4gdGhlIHN1YnNldCwgZnJvbSB0aGUgcmVzdWx0IHNjb3Jlc1xuICpcbiAqIEBwYXJhbSB7QXJyYXl9IHJlc3VsdHNcbiAqIEByZXR1cm5zIHtBcnJheX1cbiAqL1xuZnVuY3Rpb24gZmlsdGVyTGFuZ1N1YnNldCAocmVzdWx0cykge1xuICBsZXQgc3ViUmVzdWx0cyA9IFtdXG4gIGZvciAobGV0IGtleSBpbiByZXN1bHRzKSB7XG4gICAgaWYgKHN1YnNldC5pbmRleE9mKHJlc3VsdHNba2V5XVswXSkgPiAtMSkge1xuICAgICAgc3ViUmVzdWx0cy5wdXNoKHJlc3VsdHNba2V5XSlcbiAgICB9XG4gIH1cbiAgcmV0dXJuIHN1YlJlc3VsdHNcbn1cblxuLyoqXG4gKiBWYWxpZGF0ZXMgYW4gZXhwZWN0ZWQgYXJyYXkgb2YgSVNPIDYzOS0xIGxhbmd1YWdlIGNvZGUgc3RyaW5ncywgZ2l2ZW4gYnkgdGhlIHVzZXIsIGFuZCBjcmVhdGVzIGEgc3Vic2V0IG9mIHRoZSB2YWxpZFxuICogbGFuZ3VhZ2VzIGNvbXBhcmVkIGFnYWluc3QgdGhlIGN1cnJlbnQgZGF0YWJhc2UgYXZhaWxhYmxlIGxhbmd1YWdlc1xuICpcbiAqIEBwYXJhbSB7QXJyYXl8Ym9vbGVhbn0gbGFuZ3VhZ2VzXG4gKiBAcmV0dXJucyB7QXJyYXl8Ym9vbGVhbn1cbiAqL1xuZnVuY3Rpb24gbWFrZVN1YnNldCAobGFuZ3VhZ2VzKSB7XG4gIGlmIChsYW5ndWFnZXMpIHtcbiAgICBzdWJzZXQgPSBbXVxuICAgIGZvciAobGV0IGtleSBpbiBsYW5ndWFnZXMpIHtcbiAgICAgIC8vIFZhbGlkYXRlIGxhbmd1YWdlcywgYnkgY2hlY2tpbmcgaWYgdGhleSBhcmUgYXZhaWxhYmxlIGF0IGxhbmd1YWdlRGF0YVxuICAgICAgbGV0IGxhbmcgPSBPYmplY3Qua2V5cyhsYW5ndWFnZURhdGEubGFuZ0NvZGVzKS5maW5kKChsa2V5KSA9PiBsYW5ndWFnZURhdGEubGFuZ0NvZGVzW2xrZXldID09PSBsYW5ndWFnZXNba2V5XSlcbiAgICAgIGlmIChsYW5nKSB7XG4gICAgICAgIHN1YnNldC5wdXNoKHBhcnNlSW50KGxhbmcpKVxuICAgICAgfVxuICAgIH1cbiAgICBpZiAoc3Vic2V0Lmxlbmd0aCkge1xuICAgICAgc3Vic2V0LnNvcnQoKVxuICAgIH0gZWxzZSB7XG4gICAgICBzdWJzZXQgPSBmYWxzZVxuICAgIH1cbiAgfSBlbHNlIHtcbiAgICBzdWJzZXQgPSBmYWxzZVxuICB9XG4gIHJldHVybiBzdWJzZXRcbn1cblxuLyoqXG4gKiBDcmVhdGVzIGEgc3Vic2V0IG9mIGxhbmd1YWdlcywgZnJvbSB3aGljaCBkZXRlY3QoKSB3aWxsIGZpbHRlciBleGNsdWRlZCBsYW5ndWFnZXMgZnJvbSB0aGUgcmVzdWx0c1xuICogQ2FsbCBkeW5hbWljTGFuZ1N1YnNldChmYWxzZSkgdG8gZGVsZXRlIHRoZSBzdWJzZXRcbiAqXG4gKiBAcGFyYW0ge0FycmF5fGJvb2xlYW59IGxhbmd1YWdlc1xuICogQHJldHVybnMge09iamVjdH0gUmV0dXJucyBsaXN0IG9mIHRoZSB2YWxpZGF0ZWQgbGFuZ3VhZ2VzIGZvciB0aGUgbmV3IHN1YnNldFxuICovXG5mdW5jdGlvbiBkeW5hbWljTGFuZ1N1YnNldCAobGFuZ3VhZ2VzKSB7XG4gIGxldCByZXN1bHQgPSBtYWtlU3Vic2V0KGxhbmd1YWdlcylcbiAgaWYgKHJlc3VsdCkge1xuICAgICAgcmV0dXJuIGlzb0xhbmd1YWdlcyhyZXN1bHQsIGxhbmd1YWdlRGF0YS5sYW5nQ29kZXMpXG4gICAgfVxuICByZXR1cm4ge31cbn1cblxuLyoqXG4gKiBDcmVhdGVzIGEgZG93bmxvYWQsIG9ubHkgYXZhaWxhYmxlIGZvciB0aGUgd2ViIGJyb3dzZXIsIHdpdGggYSBmaWxlIGNvbnRhaW5pbmcgdGhlIG5ncmFtcyBkYXRhYmFzZSwgb2YgdGhlIHZhbGlkYXRlZFxuICogbGFuZ3VhZ2VzIGZyb20gdGhlIGFycmF5IGFyZ3VtZW50XG4gKlxuICogQHBhcmFtIHtBcnJheX0gbGFuZ3VhZ2VzXG4qL1xuZnVuY3Rpb24gc2F2ZVN1YnNldCAobGFuZ3VhZ2VzKSB7XG4gIGNvbnN0IGxhbmdBcnJheSA9IG1ha2VTdWJzZXQobGFuZ3VhZ2VzKVxuICBtYWtlU3Vic2V0KGZhbHNlKSAvLyByZW1vdmUgdGhlIGdsb2JhbCBzdWJzZXQsIHdlIG9ubHkgbmVlZCB0aGUgZmlsdGVyZWQgbGFuZ0FycmF5XG4gIHNhdmVMYW5ndWFnZVN1YnNldC5zYXZlU3Vic2V0KGxhbmdBcnJheSwgbGFuZ3VhZ2VEYXRhLm5ncmFtcywgbGFuZ3VhZ2VEYXRhLmxhbmdDb2RlcywgbGFuZ3VhZ2VEYXRhLnR5cGUpXG59XG5cbmZ1bmN0aW9uIGluZm8oKSB7XG4gIHJldHVybiB7XG4gICAgJ0RhdGEgdHlwZSc6IGxhbmd1YWdlRGF0YS50eXBlLFxuICAgICdMYW5ndWFnZXMnOiBsYW5ndWFnZURhdGEubGFuZ0NvZGVzLFxuICAgICdEeW5hbWljIHN1YnNldCc6IHN1YnNldCA/IGlzb0xhbmd1YWdlcyhzdWJzZXQsIGxhbmd1YWdlRGF0YS5sYW5nQ29kZXMpIDogZmFsc2VcbiAgfVxufVxuXG5leHBvcnQgeyBlbGQgfTsiLCJsZXQgZW5hYmxlZCA9IGZhbHNlXG5jaHJvbWUucnVudGltZS5vbk1lc3NhZ2UuYWRkTGlzdGVuZXIoKG1lc3NhZ2UsIF9zZW5kZXIsIHNlbmRSZXNwb25zZSkgPT4ge1xuICBpZiAobWVzc2FnZS50eXBlID09PSBcImVuYWJsZWRcIikge1xuICAgICAgZW5hYmxlZCA9IEJvb2xlYW4obWVzc2FnZS5wYXlsb2FkKVxuICAgICAgY29uc29sZS5sb2coZW5hYmxlZClcbiAgfSBcbn0pO1xuXG5pbXBvcnQgeyBlbGQgfSBmcm9tIFwiQHl1dGVuZ2ppbmcvZWxkXCI7XG5lbGQuaW5pdCgpLnRoZW4oKCkgPT4ge1xuICBjb25zb2xlLmxvZyhlbGQuZGV0ZWN0KFwiaGFsbG9cIikubGFuZ3VhZ2UpXG59KVxuXG5cbmV4cG9ydCBkZWZhdWx0IGRlZmluZUJhY2tncm91bmQoKCkgPT4ge1xuICBjb25zb2xlLmxvZygnSGVsbG8gYmFja2dyb3VuZCEnLCB7IGlkOiBicm93c2VyLnJ1bnRpbWUuaWQgfSk7XG59KTtcbiJdLCJuYW1lcyI6WyJzYXZlU3Vic2V0IiwicmVzdWx0Il0sIm1hcHBpbmdzIjoiOzs7QUFBTyxXQUFT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QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFDOUc7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFBQSxJQUFLO0FBQUEsSUFBSztBQUFBLElBQUs7QUFB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YUFBYSxTQUFTLEVBQUUsS0FBSyxDQUFDLFNBQVMsYUFBYSxVQUFVLElBQUksTUFBTSxVQUFVLEdBQUcsQ0FBQztBQUM3RyxZQUFJLE1BQU07QUFDUixpQkFBTyxLQUFLLFNBQVMsSUFBSSxDQUFDO0FBQUEsUUFDbEM7QUFBQSxNQUNBO0FBQ0ksVUFBSSxPQUFPLFFBQVE7QUFDakIsZUFBTyxLQUFJO0FBQUEsTUFDakIsT0FBVztBQUNMLGlCQUFTO0FBQUEsTUFDZjtBQUFBLElBQ0EsT0FBUztBQUNMLGVBQVM7QUFBQSxJQUNiO0FBQ0UsV0FBTztBQUFBLEVBQ1Q7QUFTQSxXQUFTLGtCQUFtQixXQUFXO0FBQ3JDLFFBQUlDLFVBQVMsV0FBVyxTQUFTO0FBQ2pDLFFBQUlBLFNBQVE7QUFDUixhQUFPLGFBQWFBLFNBQVEsYUFBYSxTQUFTO0FBQUEsSUFDeEQ7QUFDRSxXQUFPLENBQUE7QUFBQSxFQUNUO0FBUUEsV0FBUyxXQUFZLFdBQVc7QUFDOUIsVUFBTSxZQUFZLFdBQVcsU0FBUztBQUN0QyxlQUFXLEtBQUs7QUFDaEIsdUJBQW1CLFdBQVcsV0FBVyxhQUFhLFFBQVEsYUFBYSxXQUFXLGFBQWEsSUFBSTtBQUFBLEVBQ3pHO0FBRUEsV0FBUyxPQUFPO0FBQ2QsV0FBTztBQUFBLE1BQ0wsYUFBYSxhQUFhO0FBQUEsTUFDMUIsYUFBYSxhQUFhO0FBQUEsTUFDMUIsa0JBQWtCLFNBQVMsYUFBYSxRQUFRLGFBQWEsU0FBUyxJQUFJO0FBQUEsSUFDOUU7QUFBQSxFQUNBO0FDelVBLE1BQUksVUFBVTtBQUNkLFNBQU8sUUFBUSxVQUFVLFlBQVksQ0FBQyxTQUFTLFNBQVMsaUJBQWlCO0FBQ25FLFFBQUEsUUFBUSxTQUFTLFdBQVc7QUFDbEIsZ0JBQUEsUUFBUSxRQUFRLE9BQU87QUFDakMsY0FBUSxJQUFJLE9BQU87QUFBQSxJQUFBO0FBQUEsRUFFekIsQ0FBQztBQUdELE1BQUksS0FBQSxFQUFPLEtBQUssTUFBTTtBQUNwQixZQUFRLElBQUksSUFBSSxPQUFPLE9BQU8sRUFBRSxRQUFRO0FBQUEsRUFDMUMsQ0FBQztBQUdjLFFBQUEsYUFBQSxpQkFBaUIsTUFBTTtBQUNwQyxZQUFRLElBQUkscUJBQXFCLEVBQUUsSUFBSSxRQUFRLFFBQVEsSUFBSTtBQUFBLEVBQzdELENBQUM7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7OyIsInhfZ29vZ2xlX2lnbm9yZUxpc3QiOlswLDEsMiwzLDQsNSw2LDcsOCw5LDEwXX0=
