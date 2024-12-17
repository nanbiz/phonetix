let enabled = $state(false)
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.type === "enabled") {
      enabled = Boolean(message.payload)
      //sendResponse({ reply: "Hello from background" });
  } 
});


export default defineBackground(() => {
  console.log('Hello background!', { id: browser.runtime.id });
});
