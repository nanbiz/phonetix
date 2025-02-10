let enabled = false
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.type === "enabled") {
      enabled = Boolean(message.payload)
      console.log(enabled)
  } 
});


export default defineBackground(() => {
  console.log('Hello background!', { id: browser.runtime.id });
});
