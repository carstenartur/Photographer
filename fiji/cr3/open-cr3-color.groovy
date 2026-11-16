#@ File(label="Canon CR3 file", style="file") cr3
#@ Boolean(label="Develop RAW to RGB", value=false) developRaw
#@output ImagePlus image

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReaderSpi
import ij.ImagePlus
import ij.process.ColorProcessor

import javax.imageio.ImageIO

def reader = new CanonCr3ImageReaderSpi().createReaderInstance()
try {
    def stream = ImageIO.createImageInputStream(cr3)
    if (stream == null) {
        throw new IOException("Could not open " + cr3)
    }
    stream.withCloseable { input ->
        reader.setInput(input)
        def param = reader.getDefaultReadParam()
        param.setDecodeOptions(developRaw ? Cr3DecodeOptions.rawProcessed() : Cr3DecodeOptions.defaults())
        def pixels = reader.read(0, param)
        def label = developRaw ? "developed RGB" : "camera JPEG"
        image = new ImagePlus(cr3.name + " — " + label, new ColorProcessor(pixels))
    }
} finally {
    reader.dispose()
}
