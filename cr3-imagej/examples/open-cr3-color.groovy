#@ File(label="Canon CR3 file", style="file") cr3
#@ Boolean(label="Develop RAW to 16-bit RGB", value=false) developRaw
#@output ImagePlus image

import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReaderSpi
import com.photographer.focusstack.cr3.imagej.Cr3ColorImporter
import ij.ImagePlus
import ij.process.ColorProcessor
import javax.imageio.stream.FileImageInputStream

if (developRaw) {
    image = Cr3ColorImporter.open(cr3.toPath())
} else {
    def reader = new CanonCr3ImageReaderSpi().createReaderInstance()
    try {
        new FileImageInputStream(cr3).withCloseable { input ->
            reader.setInput(input)
            def param = reader.getDefaultReadParam()
            param.setPreviewOnly(true)
            image = new ImagePlus(cr3.name + " — camera JPEG", new ColorProcessor(reader.read(0, param)))
        }
    } finally {
        reader.dispose()
    }
}
