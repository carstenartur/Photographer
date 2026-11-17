import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;

public class SensorValues {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SensorValues <photo.cr3>");
        }
        var reader = ImageIO.getImageReadersByFormatName("CR3").next();
        try (var input = new FileImageInputStream(new File(args[0]))) {
            reader.setInput(input);
            var param = new CanonCr3ImageReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            var bayer = reader.readRaster(0, param);

            var values = new BitSet();
            for (int y = 0; y < bayer.getHeight(); y++) {
                for (int x = 0; x < bayer.getWidth(); x++) {
                    values.set(bayer.getSample(x, y, 0));
                }
            }
            System.out.printf("%d x %d Bayer mosaic, %d distinct sensor values, maximum %d%n",
                    bayer.getWidth(), bayer.getHeight(), values.cardinality(), values.length() - 1);
        } finally {
            reader.dispose();
        }
    }
}
