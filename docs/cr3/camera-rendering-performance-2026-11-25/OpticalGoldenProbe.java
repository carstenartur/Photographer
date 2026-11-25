import com.photographer.focusstack.cr3.processing.OpticalCorrection;
import javax.imageio.ImageTypeSpecifier;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;

public class OpticalGoldenProbe {
    public static void main(String[] args) throws Exception {
        for (int fixture = 0; fixture < 10; fixture++) {
            boolean srgb = (fixture & 1) == 0;
            int w = fixture < 6 ? 79 : 1024, h = fixture < 6 ? 61 : 773;
            int bits = fixture < 4 || fixture > 5 ? 16 : 8;
            var image = make(w, h, bits, srgb);
            var correction = fixture < 6
                    ? new OpticalCorrection(.03, -.12, .02, 1.001, .999, -.6, .05, -.01)
                    : fixture < 8 ? new OpticalCorrection(-.04, .12, -.03, .997, 1.003, -.4, .04, .01)
                    : new OpticalCorrection(.99, 0, 0, 1.005, .995, -.2, .02, 0);
            double strength = fixture < 2 || fixture > 5 ? 1 : fixture < 4 ? .35 : 0;
            correction.applyToOwned(image, srgb, 4, strength);
            System.out.println(fixture + " " + OpticalStagesProbe.hash(image));
        }
    }

    static BufferedImage make(int w, int h, int bits, boolean srgb) {
        var cs = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        var image = bits == 8 ? new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                : ImageTypeSpecifier.createBanded(cs, new int[]{0, 1, 2}, new int[3],
                        DataBuffer.TYPE_USHORT, false, false).createBufferedImage(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int band = 0; band < 3; band++) {
            int value = (x * 1747 + y * 17231 + band * 19913 + x * y * 31) & 65535;
            if ((x + 3 * y + 5 * band) % 31 == 0) value = 0;
            if ((x + 7 * y + 2 * band) % 37 == 0) value = 65535;
            image.getRaster().setSample(x, y, band, bits == 16 ? value : value >>> 8);
        }
        return image;
    }
}
