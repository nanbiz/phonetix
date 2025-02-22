export const Languages = {
  detect: "Detect Language",
  en: "English",
  de: "German",
  es: "Spanish",
};

export const Modes = {
  wholePage: "Translate Whole Page",
  onHover: "Translate on Hover",
  showOriginalOnHover: "Show Original on Hover",
};

export type TransformConfig = {
  mode: keyof typeof Modes;
  language: keyof typeof Languages;
  isEnabled: boolean;
};