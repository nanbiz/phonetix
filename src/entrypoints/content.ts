export default defineContentScript({
  matches: ['<all_urls>'],
  runAt: "document_start",
  main() {
    let blockTags = new Set(["SCRIPT", "STYLE"])

    const ActualTextNodes: NodeFilter = (node: Node): number => {
      if (node.nodeType === Node.TEXT_NODE && node.parentElement && !blockTags.has(node.parentElement?.tagName)) {
        return NodeFilter.FILTER_ACCEPT;
      }
      return NodeFilter.FILTER_REJECT;
    };

    function transformAllTextNodes() {
      const walker = document.createTreeWalker(
        document.documentElement,
        NodeFilter.SHOW_TEXT,
        ActualTextNodes
      );
      let node;
      while ((node = walker.nextNode())) {
        node.nodeValue = transform(node.nodeValue || "");
      }
    }

    transformAllTextNodes();
    
    const config = { childList: true, subtree: true };
    const callback = (mutationsList: MutationRecord[]) => {
      mutationsList.forEach(mutation => 
        mutation.addedNodes.forEach(node => {
          if (ActualTextNodes(node) == NodeFilter.FILTER_ACCEPT) {
            node.nodeValue = transform(node.nodeValue || "")
          }}

        )
      );
    };

    function transform(text:string) {
      return text.toUpperCase()
    }

    const targetNode = document.documentElement
    const observer = new MutationObserver(callback);
    observer.observe(targetNode, config);
  },
});

