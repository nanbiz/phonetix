
export default defineContentScript({
  matches: ['<all_urls>'],
  runAt: "document_start",
  main() {
    // TODO: I will use lists of IPA per language and make it possible to add multiple languages, then put them all into a set and update that set on each settings change

    var enabled = true
    if (enabled) {

    let blockTags = new Set(["SCRIPT", "STYLE"])

    const ActualTextNodes: NodeFilter = (node: Node): number => {
      if (node.nodeType === Node.TEXT_NODE && node.parentElement && !blockTags.has(node.parentElement?.tagName)) {
        return NodeFilter.FILTER_ACCEPT;
      }
      return NodeFilter.FILTER_REJECT;
    };
    // TODO: Currently doesn't work great with shadow root nodes
    function transformAllTextNodes() {
      const walker = document.createTreeWalker(
        document.documentElement,
        NodeFilter.SHOW_TEXT,
        ActualTextNodes
      );
      let node;
      while ((node = walker.nextNode())) {
        if (node.nodeValue?.trim()) 
          node.nodeValue = transform(node.nodeValue || "");
      }
    }

    transformAllTextNodes();
    
    const config = { childList: true, subtree: true };
    const callback = (mutationsList: MutationRecord[]) => {
      mutationsList.forEach(mutation => 
        mutation.addedNodes.forEach(node => {
          if (ActualTextNodes(node) == NodeFilter.FILTER_ACCEPT) {
            if (node.nodeValue?.trim())
              node.nodeValue = transform(node.nodeValue || "")
          }
        }
        )
      );
    };

    const targetNode = document.documentElement
    const observer = new MutationObserver(callback);
    observer.observe(targetNode, config);
  }
  },
});

function transform(text:string) {
  console.log(text)
  return text.toUpperCase()
}