import { onMessage } from "@/lib/messaging";
import ipaMapJson from '@/public/languages_without_audio/de.json';

let ipaMap: {[key:string] : string[]} | null = null;

export default defineBackground(() => {
  console.log('Hello background!', { id: browser.runtime.id });
});

onMessage('getIpaMap', () => {
  return ipaMap ?? createIpaMap(ipaMapJson);;
});

interface IpaEntry {
  ipa: string;
  tags?: string[];
  note?: string;
}

interface Pos {
  ipas: IpaEntry[];
}

interface WordData {
  [pos: string]: Pos;
}

function createIpaMap(ipaMapJson: object):  {[key:string] : string[]} {
  if (!ipaMapJson || typeof ipaMapJson !== 'object') throw new Error("JSON of IPAs not found.");
  const jsonData = ipaMapJson as Record<string, WordData>;


  for (const word in jsonData) {
    Object.values(jsonData[word]).flatMap((pos) => pos.ipas).map((ipa) => ipa.ipa)
  }

  return Object.fromEntries(
    Object.entries(jsonData).map(([word, data]) => [
      word,
      Object.values(data).flatMap(pos => pos.ipas).map(ipa => ipa.ipa)
    ])
  );
}