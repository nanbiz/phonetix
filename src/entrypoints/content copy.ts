import { onMessage } from "@/lib/messaging";
import { Languages, Modes } from "@/lib/types";
import type { TransformConfig } from "@/lib/types";
import { z } from "zod";

export default defineContentScript({
  matches: ['<all_urls>'],
  runAt: "document_start",
  allFrames: true,
  main() {
    const config = initializeConfig();
    processDomTree(document.documentElement, config);
    observeDomChanges(config);
    listenForMessages(config);
    return cleanupContentScript;
  },
});

const LanguageSchema = z.enum(Object.keys(Languages) as [keyof typeof Languages, ...Array<keyof typeof Languages>]);
const ModeSchema = z.enum(Object.keys(Modes) as [keyof typeof Modes, ...Array<keyof typeof Modes>]);

const DEFAULT_CONFIG: TransformConfig = {
  mode: "wholePage",
  language: "en",
  isEnabled: true
};

const BLOCKED_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT']);

let currentConfig: TransformConfig = { ...DEFAULT_CONFIG };
let mutationObservers: Map<Node, MutationObserver> = new Map();

function initializeConfig(): TransformConfig {
  const config = { ...DEFAULT_CONFIG };
  currentConfig = validateConfig(config);
  return currentConfig;
}

function processDomTree(root: Node, config: TransformConfig): void {
  const walker = createTextWalker(root);
  let node: Node | null = walker.nextNode();
  while (node) {
    transformNodeText(node as Text, config);
    if (node.parentElement && node.parentElement.shadowRoot) {
      processDomTree(node.parentElement.shadowRoot, config);
      observeRoot(node.parentElement.shadowRoot, config);
    }
    node = walker.nextNode();
  }
  if (root instanceof Element && root.shadowRoot) {
    processDomTree(root.shadowRoot, config);
    observeRoot(root.shadowRoot, config);
  }
}

function transformNodeText(node: Text, config: TransformConfig): void {
  if (!config.isEnabled || !node.nodeValue?.trim()) {
    return;
  }
  const originalValue = node.nodeValue;
  const transformedValue = config.mode === "wholePage"
    ? originalValue.toUpperCase()
    : originalValue;
  node.nodeValue = transformedValue;
}

function observeDomChanges(config: TransformConfig): void {
  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      // Handle added nodes
      if (mutation.addedNodes.length > 0) {
        const affectedNodes = Array.from(mutation.addedNodes);
        affectedNodes.forEach((node) => {
          processDomTree(node, config);
          if (node instanceof Element && node.shadowRoot) {
            processDomTree(node.shadowRoot, config);
            observeRoot(node.shadowRoot, config);
          }
        });
      }
      // Handle text content changes
      if (mutation.type === "characterData" && mutation.target.nodeType === Node.TEXT_NODE) {
        transformNodeText(mutation.target as Text, config);
      }
    });
  });
  observer.observe(document.documentElement, {
    childList: true,
    characterData: true,
    subtree: true
  });
  mutationObservers.set(document.documentElement, observer);
}

function observeRoot(root: ShadowRoot, config: TransformConfig): void {
  if (mutationObservers.has(root)) {
    return;
  }
  const observer = new MutationObserver((mutations) => {
    mutations.forEach((mutation) => {
      // Handle added nodes
      if (mutation.addedNodes.length > 0) {
        const affectedNodes = Array.from(mutation.addedNodes);
        affectedNodes.forEach((node) => {
          processDomTree(node, config);
          if (node instanceof Element && node.shadowRoot) {
            processDomTree(node.shadowRoot, config);
            observeRoot(node.shadowRoot, config);
          }
        });
      }
      // Handle text content changes
      if (mutation.type === "characterData" && mutation.target.nodeType === Node.TEXT_NODE) {
        transformNodeText(mutation.target as Text, config);
      }
    });
  });
  observer.observe(root, {
    childList: true,
    characterData: true,
    subtree: true
  });
  mutationObservers.set(root, observer);
}

function listenForMessages(config: TransformConfig): void {
  onMessage("modeChanged", (m) => {
    config.mode = validateMode(m.data);
    processDomTree(document.documentElement, config);
  });
  onMessage("languageChanged", (m) => {
    config.language = validateLanguage(m.data);
    processDomTree(document.documentElement, config);
  });
  onMessage("extensionToggled", (m) => {
    config.isEnabled = m.data;
    validateConfig(config);
    processDomTree(document.documentElement, config);
  });
}

function createTextWalker(root: Node): TreeWalker {
  const filterFunction = (node: Node): number => {
    const parent = node.parentElement;
    const isValid = parent && !BLOCKED_TAGS.has(parent.tagName);
    return isValid ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
  };
  return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, { acceptNode: filterFunction });
}

function validateConfig(config: TransformConfig): TransformConfig {
  const validatedMode = ModeSchema.safeParse(config.mode);
  const validatedLanguage = LanguageSchema.safeParse(config.language);
  config.mode = validatedMode.success ? validatedMode.data : DEFAULT_CONFIG.mode;
  config.language = validatedLanguage.success ? validatedLanguage.data : DEFAULT_CONFIG.language;
  return config;
}

function validateMode(mode: keyof typeof Modes): keyof typeof Modes {
  const result = ModeSchema.safeParse(mode);
  return result.success ? result.data : DEFAULT_CONFIG.mode;
}

function validateLanguage(language: keyof typeof Languages): keyof typeof Languages {
  const result = LanguageSchema.safeParse(language);
  return result.success ? result.data : DEFAULT_CONFIG.language;
}

function cleanupContentScript(): void {
  mutationObservers.forEach((observer) => {
    observer.disconnect();
  });
  mutationObservers.clear();
  currentConfig.isEnabled = false;
}