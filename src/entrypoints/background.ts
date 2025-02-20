import { onMessage } from "@/lib/messaging";

let enabled = false


export default defineBackground(() => {
  console.log('Hello background!', { id: browser.runtime.id });
});
