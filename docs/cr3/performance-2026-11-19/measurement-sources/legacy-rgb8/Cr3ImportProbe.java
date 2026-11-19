import com.photographer.focusstack.cr3.decode.*;
import com.photographer.focusstack.cr3.imageio.*;
import java.awt.image.*;
import java.io.*;
import java.lang.management.*;
import java.lang.ref.Reference;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.*;

/** Public-API full-file probe. Hashing, diagnostics and optional output writing are outside import timing. */
public final class Cr3ImportProbe {
    static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static Map<String,String> args = new HashMap<>();
    public static void main(String[] argv) throws Exception {
        for (int i=0; i<argv.length; i++) {
            if (argv[i].equals("--checksum") || argv[i].equals("--gc-between")) args.put(argv[i], "true");
            else args.put(argv[i], argv[++i]);
        }
        Path file = Path.of(args.get("--input"));
        String mode = args.getOrDefault("--mode", "default");
        int warmup = Integer.parseInt(args.getOrDefault("--warmup", "0"));
        int iterations = Integer.parseInt(args.getOrDefault("--iterations", "1"));
        int workers = Integer.parseInt(args.getOrDefault("--workers", "0"));
        boolean checksum = args.containsKey("--checksum");
        ImageIO.scanForPlugins();
        THREADS.setThreadAllocatedMemoryEnabled(true);
        for (int iteration=-warmup; iteration<iterations; iteration++) {
            if (args.containsKey("--gc-between")) System.gc();
            Map<Long,Long> before = allocated();
            long cpu0 = OS.getProcessCpuTime(), t0 = System.nanoTime();
            BufferedImage image = read(file,mode,workers);
            long elapsed = System.nanoTime()-t0, cpu = OS.getProcessCpuTime()-cpu0;
            long allocated = allocated().entrySet().stream()
                    .mapToLong(e -> Math.max(0, e.getValue()-before.getOrDefault(e.getKey(),0L))).sum();
            long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long maxRss = statusKb("VmHWM");
            long hash = hash(image, checksum);
            if (iteration >= 0) {
                System.out.printf(Locale.ROOT,
                  "{\"engine\":\"Photographer\",\"input\":\"%s\",\"mode\":\"%s\",\"iteration\":%d,\"warmup\":%d,\"workers\":%d,\"available_processors\":%d,\"width\":%d,\"height\":%d,\"channels\":%d,\"bits\":%d,\"total_ms\":%.6f,\"cpu_ms\":%.6f,\"allocated_bytes\":%d,\"heap_used_bytes\":%d,\"maxrss_kb\":%d,\"checksum_kind\":\"%s\",\"fnv1a64\":\"%016x\"}%n",
                  escape(file.toString()),mode,iteration,warmup,workers,Runtime.getRuntime().availableProcessors(),
                  image.getWidth(),image.getHeight(),image.getRaster().getNumBands(),image.getSampleModel().getSampleSize(0),
                  elapsed/1e6,cpu/1e6,allocated,heapUsed,maxRss,checksum?"full-u16le-or-u8":"sampled-u16le-or-u8",hash);
            }
            if (iteration == iterations-1 && args.containsKey("--dump")) dump(image, Path.of(args.get("--dump")));
            Reference.reachabilityFence(image);
        }
    }
    static BufferedImage read(Path file, String mode, int workers) throws Exception {
        if (mode.equals("default")) {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) throw new IOException("No reader");
            return image;
        }
        Cr3DecodeOptions options = mode.startsWith("sensor") ? Cr3DecodeOptions.rawSensor() : Cr3DecodeOptions.rawProcessed();
        options.maxWorkers(workers);
        if (mode.startsWith("rgb16")) options.bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN);
        if (mode.endsWith("linear")) options.applySrgbConversion(false);
        if (mode.endsWith("-path")) {
            try (Cr3Decoder decoder = new Cr3Decoder()) { decoder.setInput(file); return decoder.decode(options); }
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) throw new IOException("No stream");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("No reader");
            ImageReader reader = readers.next();
            try {
                stream.seek(0); reader.setInput(stream,false,false);
                var param = new CanonCr3ImageReadParam(); param.setDecodeOptions(options);
                return reader.read(0,param);
            } finally { reader.dispose(); }
        }
    }
    static Map<Long,Long> allocated() {
        long[] ids=THREADS.getAllThreadIds(); long[] sizes=THREADS.getThreadAllocatedBytes(ids);
        Map<Long,Long> values=new HashMap<>();
        for(int i=0;i<ids.length;i++) if(sizes[i]>=0) values.put(ids[i],sizes[i]);
        return values;
    }
    static long statusKb(String key) throws IOException {
        for(String line:Files.readAllLines(Path.of("/proc/self/status")))
            if(line.startsWith(key+":")) return Long.parseLong(line.split("\\s+")[1]);
        return -1;
    }
    static long hash(BufferedImage image, boolean full) {
        Raster raster=image.getRaster(); int width=image.getWidth(), height=image.getHeight();
        int step=full?1:Math.max(1,height/64), bits=image.getSampleModel().getSampleSize(0);
        int[] row=new int[width*raster.getNumBands()]; long h=0xcbf29ce484222325L;
        for(int y=0;y<height;y+=step) {
            raster.getPixels(0,y,width,1,row);
            for(int v:row) { h=(h^(v&255))*0x100000001b3L; if(bits>8) h=(h^(v>>>8))*0x100000001b3L; }
        }
        return h;
    }
    static void dump(BufferedImage image,Path path) throws IOException {
        Raster raster=image.getRaster();int width=image.getWidth(),height=image.getHeight(),bands=raster.getNumBands();
        int bits=image.getSampleModel().getSampleSize(0);
        try(var out=new BufferedOutputStream(Files.newOutputStream(path))) {
            out.write(("P"+(bands==1?5:6)+"\n"+width+" "+height+"\n"+(bits>8?65535:255)+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            int[] row=new int[width*bands];
            byte[] bytes=new byte[row.length*(bits>8?2:1)];
            for(int y=0;y<height;y++) { raster.getPixels(0,y,width,1,row);int offset=0;for(int v:row) {if(bits>8)bytes[offset++]=(byte)(v>>>8);bytes[offset++]=(byte)v;} out.write(bytes); }
        }
    }
    static String escape(String value) { return value.replace("\\","\\\\").replace("\"","\\\""); }
}
