# Chrome Web Store listing

Fields in the order the developer dashboard asks for them. The package is the release's
`phonetix-<version>-chrome.zip`.

## Store listing

- Name and summary come from the manifest.
- Category: Education
- Language: English
- Store icon: public/icon/128.png
- Screenshots (1280x800): store/screenshot-*.png
- Small promo tile (440x280): store/promo-small-440x280.png
- Homepage: https://github.com/nanbiz/phonetix
- Support: https://github.com/nanbiz/phonetix/issues

Description:

Read a language you are learning on any page, and know how every word in it is said.

Point at a word and a card opens above it: how the word is said, in IPA and out loud, what it means in your language, and which form of which word it is ("comió" is the preterite of "comer", he ate). The meaning is the one the sentence uses, not the first one in the dictionary. Phrases are read as one ("a través de"), and pronouns, articles and verbs keep their case, gender and tense in your language.

Learning a language as you read: Phonetix can replace some of the words of a page in your own language with the language you are learning, or write how they are said above them. You choose how often.

The translator (Alt+Shift+T) translates whatever you type or say, either way between your language and the one you are learning. Alt+Shift+D opens it listening.

Everything runs in your browser. Dictionaries built from Wiktionary in about 60 languages, the translation models Firefox uses, and a speech model are downloaded once and kept. What you type or say never leaves your device, and of what you read only the word you open a card for is looked up on Wiktionary. Accents can be chosen where a language has several, and the interface is in 29 languages.

Free and open source: https://github.com/nanbiz/phonetix

## Privacy practices

- Single purpose: Help the reader understand and pronounce the words of a foreign language on the pages they read.
- host_permissions `<all_urls>`: the card and the replaced words appear on whatever page the reader reads, so the content script runs on every site. It reads the page's text to tell its language and look its words up, on the device. The same permission lets the background download dictionaries and models and ask Wiktionary about a word the reader opened.
- storage: keeps the reader's settings (languages, the sites Phonetix is off on, appearance) in the browser.
- tabs: tells the settings view which site it was opened over, for its switch for that site.
- offscreen: Chrome's service worker has no document, and the speech synthesizer, the speech recognizer and the translator need one; they run in an offscreen document.
- declarativeNetRequest: one static rule sets a Firefox user agent on requests to Mozilla's server for translation models (firefox-settings-attachments.cdn.mozilla.net), which refuses Chrome's (406) and serves the same files to Firefox. No other request is touched.
- Remote code: No. Every script and WebAssembly module is in the package; downloads are data (dictionaries, model weights, JSON).
- Data usage: Website content, because the one word a reader opens a card for is looked up on Wiktionary. Nothing else is collected. Certify the three statements (not sold, not used for unrelated purposes, not used for creditworthiness).
- Privacy policy: https://github.com/nanbiz/phonetix/blob/main/PRIVACY.md
