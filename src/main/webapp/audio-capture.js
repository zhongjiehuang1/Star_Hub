/* AudioWorklet: mono PCM16, 24 kHz, ~100 ms chunks. Native context resampling
   is preferred; the accumulator also handles devices with another sample rate. */
class StarHubCapture extends AudioWorkletProcessor {
  constructor(){
    super();this.chunk=new Int16Array(2400);this.offset=0;this.phase=0;this.sum=0;this.count=0;
    this.port.onmessage=e=>{if(e.data==='flush'){this.flush();this.port.postMessage({flushed:true});}};
  }
  flush(){if(this.offset){const bytes=this.chunk.slice(0,this.offset).buffer;this.port.postMessage({audio:bytes},[bytes]);this.offset=0;}}
  process(inputs,outputs){
    for(const output of outputs)for(const channel of output)channel.fill(0);
    const samples=inputs[0]?.[0];if(!samples)return true;
    for(const sample of samples){
      this.sum+=sample;this.count++;this.phase+=24000;
      if(this.phase>=sampleRate){this.phase-=sampleRate;const value=Math.max(-1,Math.min(1,this.sum/this.count));this.sum=0;this.count=0;this.chunk[this.offset++]=value<0?value*32768:value*32767;if(this.offset===this.chunk.length)this.flush();}
    }
    return true;
  }
}
registerProcessor('starhub-capture',StarHubCapture);
