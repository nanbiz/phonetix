import { defineExtensionMessaging } from '@webext-core/messaging';
import { Languages, Modes } from './types';

interface ProtocolMap {
  extensionToggled(isEnabled: boolean): void;
  languageChanged(language: keyof typeof Languages): void;
  modeChanged(mode: keyof typeof Modes): void;
  getIpaMap():  {[key:string] : string[]};
}


export const { sendMessage, onMessage } = defineExtensionMessaging<ProtocolMap>();

export async function getCurrentTabId(): Promise<number> {
  return new Promise((resolve, reject) => {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs.length > 0 && tabs[0].id) {
        resolve(tabs[0].id)
      } else {
        reject()
      }
    });
  })
}