using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Speech.Recognition;
using System.Threading.Tasks;

namespace DashboardWindows {
    public sealed class Recorder : IDisposable {
        [StructLayout(LayoutKind.Sequential)] struct Format { public ushort tag,channels; public uint rate,bytes; public ushort align,bits,extra; }
        [StructLayout(LayoutKind.Sequential)] struct Header { public IntPtr data; public uint length,recorded; public IntPtr user; public uint flags,loops; public IntPtr next,reserved; }
        delegate void Callback(IntPtr wave,uint msg,IntPtr instance,IntPtr param,IntPtr other);
        [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr handle,uint id,ref Format format,Callback callback,IntPtr instance,uint flags);
        [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr handle,IntPtr header,uint size);
        [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr handle,IntPtr header,uint size);
        [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr handle,IntPtr header,uint size);
        [DllImport("winmm.dll")] static extern int waveInStart(IntPtr handle);
        [DllImport("winmm.dll")] static extern int waveInReset(IntPtr handle);
        [DllImport("winmm.dll")] static extern int waveInClose(IntPtr handle);
        readonly object gate=new object(); readonly List<IntPtr> headers=new List<IntPtr>(); readonly List<GCHandle> pins=new List<GCHandle>();
        Callback callback; IntPtr handle; FileStream file; volatile bool running;
        int level;
        public double Level { get { return System.Threading.Interlocked.CompareExchange(ref level,0,0)/1000.0; } }
        public static double PcmLevel(byte[] bytes) {
            int peak=0;
            for(int i=0;i+1<bytes.Length;i+=2) { int sample=(short)(bytes[i] | bytes[i+1]<<8); peak=Math.Max(peak,Math.Abs(sample)); }
            return peak==0?0:Math.Max(0,Math.Min(1,1+20*Math.Log10(peak/32768.0)/60));
        }
        static readonly uint size=(uint)Marshal.SizeOf(typeof(Header));
        public void Start(string path) {
            file=new FileStream(path,FileMode.CreateNew,FileAccess.ReadWrite,FileShare.Read); file.Write(new byte[44],0,44); file.Flush(true);
            callback=Data; var format=new Format { tag=1,channels=1,rate=16000,bytes=32000,align=2,bits=16 };
            if(waveInOpen(out handle,unchecked((uint)-1),ref format,callback,IntPtr.Zero,0x30000)!=0) { file.Dispose(); file=null; throw new IOException("Microphone unavailable. Check Windows microphone permissions and input device."); }
            try {
                for(int i=0;i<4;i++) {
                    var bytes=new byte[3200]; var pin=GCHandle.Alloc(bytes,GCHandleType.Pinned); pins.Add(pin);
                    var p=Marshal.AllocHGlobal((int)size); headers.Add(p);
                    Marshal.StructureToPtr(new Header { data=pin.AddrOfPinnedObject(),length=(uint)bytes.Length },p,false);
                    if(waveInPrepareHeader(handle,p,size)!=0 || waveInAddBuffer(handle,p,size)!=0) throw new IOException("Microphone buffer initialization failed.");
                }
                running=true; if(waveInStart(handle)!=0) throw new IOException("Microphone could not start.");
            } catch { Dispose(); throw; }
        }
        void Data(IntPtr wave,uint msg,IntPtr instance,IntPtr p,IntPtr other) {
            if(msg!=0x3C0) return;
            try {
                lock(gate) {
                    // A late driver callback must never dereference a header freed during Stop.
                    if(file==null || !headers.Contains(p)) return;
                    var h=(Header)Marshal.PtrToStructure(p,typeof(Header));
                    if(h.recorded>0 && file.Length+h.recorded <= 25*1024*1024) { var b=new byte[h.recorded]; Marshal.Copy(h.data,b,0,b.Length); System.Threading.Interlocked.Exchange(ref level,(int)(PcmLevel(b)*1000)); file.Write(b,0,b.Length); }
                    if(running) waveInAddBuffer(wave,p,size);
                }
            } catch { running=false; }
        }
        public void Dispose() {
            running=false;
            if(handle!=IntPtr.Zero) waveInReset(handle);
            lock(gate) {
                if(handle!=IntPtr.Zero) { foreach(var p in headers) { waveInUnprepareHeader(handle,p,size); Marshal.FreeHGlobal(p); } headers.Clear(); waveInClose(handle); handle=IntPtr.Zero; }
                foreach(var pin in pins) if(pin.IsAllocated) pin.Free(); pins.Clear();
                if(file!=null) { Finish(file); file.Dispose(); file=null; }
            }
            GC.KeepAlive(callback);
        }
        public static void Finish(Stream stream) {
            long length=stream.Length; if(length<44 || length>25*1024*1024) throw new InvalidDataException("Invalid recording length.");
            stream.Position=0; var w=new BinaryWriter(stream,System.Text.Encoding.ASCII,true);
            w.Write(System.Text.Encoding.ASCII.GetBytes("RIFF")); w.Write((int)length-8); w.Write(System.Text.Encoding.ASCII.GetBytes("WAVEfmt "));
            w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(16000); w.Write(32000); w.Write((short)2); w.Write((short)16);
            w.Write(System.Text.Encoding.ASCII.GetBytes("data")); w.Write((int)length-44); w.Flush(); var f=stream as FileStream; if(f!=null) f.Flush(true);
        }
        public static Task<string> Transcribe(string path) {
            return Task.Run(()=> {
                var engines=SpeechRecognitionEngine.InstalledRecognizers();
                if(engines.Count==0) throw new InvalidOperationException("No local Windows speech engine is installed. Audio remains saved on this PC.");
                using(var engine=new SpeechRecognitionEngine(engines[0])) {
                    engine.LoadGrammar(new DictationGrammar()); engine.SetInputToWaveFile(path); var text=new List<string>(); RecognitionResult result;
                    while((result=engine.Recognize())!=null) if(!String.IsNullOrWhiteSpace(result.Text)) text.Add(result.Text);
                    return String.Join(" ",text);
                }
            });
        }
    }
}
