// The microphone's samples, handed to the page that records them as they arrive.
//
// An audio worklet, because it runs beside the audio rather than on the page's own thread: a
// page busy writing down what was said would otherwise drop what is being said now.
class Capture extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) this.port.postMessage(channel.slice(0));
    return true;
  }
}

registerProcessor('phonetix-capture', Capture);
