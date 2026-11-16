# Install the Canon CR3 sensor importer in Fiji

Use Fiji with Java 21 or newer. Add the hosted update site to receive the importer and subsequent updates. For offline installation, obtain the two ZIPs from the project's GitHub release assets or the **cr3-fiji-distribution** Actions artifact. Access follows the repository's sharing settings. The workflow can also be run manually.

## Install from the hosted Fiji update site

1. Open **Help › Update... › Manage update sites › Add Unlisted Site**.
2. Enter **Canon CR3 Sensor Importer** and the URL `https://carstenartur.github.io/Photographer/fiji/cr3/`.
3. Enable the site, close the site manager, apply the changes and restart Fiji.
4. Open **File › Import › CR3 Sensor Data...**. For later versions, run **Help › Update...** again.

The [published version](https://carstenartur.github.io/Photographer/fiji/cr3/version.txt) identifies the installed channel's current build. A version ending in `-SNAPSHOT` is a preview. Stable releases use the same URL and retain the timestamped updater history.

## Open a sensor image

Choose **File › Import › CR3 Sensor Data...** and select a CR3. To reproduce the example, open the repository fixture [sample-tele.cr3](../../canon-cr3-imageio/src/test/resources/samples/sample-tele.cr3). The importer opens the original Bayer mosaic in sensor coordinates and Fiji displays that plane in grayscale. In this PowerShot G5 X Mark II capture, the RGGB mosaic contains 14-bit sensor codes. ImageJ stores them in an unsigned 16-bit image measuring 5,632 × 3,710 pixels.

![ImageJ displaying the imported sample-tele.cr3 sensor plane at 16.7% zoom. The image window reports 5632 × 3710 pixels and 16-bit depth.](../screenshots/cr3-sensor-imagej.webp)

The screenshot uses **Image › Adjust › Brightness/Contrast... › Set** with a display minimum of **2048** and maximum of **6473**. Use **Reset** to restore the display range. Adjusting these limits preserves the pixel values for measurement. Leave **Apply** unused to preserve those values. Other exposures can need different display limits.

## Open a color image

Choose the output that fits your work.

| Output | Source and display |
| --- | --- |
| **CR3 Sensor Data...** | One value per Bayer site, displayed in grayscale. Use this view to measure the original sensor codes. |
| Embedded color JPEG | The camera's rendered preview, when present. It opens without decoding the RAW sensor stream. |
| RGB developed from RAW | The Java reader demosaics a [supported RAW or C-RAW layout](decoder.md) into sRGB. Its default rendering uses 8 bits per channel, the active sensor area and the recorded orientation. Color reproduction and camera support remain experimental. |

After installing the update site, download the Groovy example and open it in Fiji's Script Editor, or paste the code below with **Groovy** selected. Run it and choose a CR3 file. With **Develop RAW to RGB** cleared, Fiji opens the camera JPEG. Select it to render from the RAW data using the installed decoder. [Download the example](https://carstenartur.github.io/Photographer/fiji/cr3/open-cr3-color.groovy) · [Fiji scripting guide](https://imagej.net/scripting/)

```groovy
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
```

For byte-preserving JPEG extraction in Java, use the [embedded-preview extractor](import.md#copy-the-cameras-embedded-jpeg-unchanged). The [ImageIO guide](imageio.md#read-parameters) describes the development controls.

## Install from the ZIP

Close Fiji. Extract `cr3-fiji-install.zip` into the Fiji application directory that contains `jars/` and `plugins/`. On macOS, open **Show Package Contents** to locate those folders. Merge the two directories with their existing counterparts. Restart Fiji and choose **File › Import › CR3 Sensor Data...**.

The archive installs one menu plugin and six CR3 libraries. Fiji supplies ImageJ and SLF4J. To uninstall a manual installation, remove the seven files listed in the ZIP and restart Fiji.

## Serve a local copy of the update site

Extract `cr3-fiji-update-site.zip` into a directory outside the Fiji installation. Its root contains `db.xml.gz`, `jars/` and `plugins/`. To try it on your own computer, start a local server in that directory:

```sh
python3 -m http.server 8765 --bind 127.0.0.1
```

Open **Help › Update... › Manage update sites › Add Unlisted Site**. Enter **Canon CR3 Sensor Importer** and `http://127.0.0.1:8765/`, enable the site, close the site manager, apply changes and restart Fiji. The URL must point to the directory containing the database. For an existing hosted copy, use its HTTPS base URL. A GitHub release ZIP download is an archive, so extract and serve its contents before adding the site.

To receive later updates, keep the same base URL and run **Help › Update...** again after a new version is staged. For local testing, keep the server running whenever Fiji checks the site, or disable the site after installation.

## Build both archives

Use Maven 3.9.x, JDK 25, Python 3 and a Fiji installation with the ImageJ 2.x updater. Run from the repository root, setting the path to the Fiji directory that contains `db.xml.gz`:

```sh
mvn -pl cr3-imagej -am verify -Pfiji-update-site -Dfiji.directory=/path/to/Fiji
```

The profile obtains the release-version CR3 libraries through Maven. Fiji's updater stages the timestamped files, discovers their dependencies and builds the database. A second local installation downloads all seven files, compares their bytes with the site and checks their updater status. Find the site tree and both ZIPs in `target/cr3-fiji-site/`.

For the next release, reuse that output directory to extend its history. From a clean checkout, set `FIJI_PREVIOUS_SITE` to the previous release's **cr3-fiji-update-site.zip** before running Maven. Use `-Dfiji.site.output=/new/path` to choose a different output. For static hosting, copy the entire resulting `site/` tree, including old timestamped payloads, and replace `db.xml.gz` last. Contributors can also share the ZIP privately and use the local-server instructions above.

## Release publication

The regular release workflow builds and tests the seven Fiji JARs on the verified stable tag. The distribution job attaches the two ZIPs to the GitHub release and publishes that release's update-site archive under `fiji/cr3/` on the existing `gh-pages` branch. It checks the Maven coordinates inside the current JARs, keeps timestamped payloads from previous releases and compares the anonymous downloads of the version file, update database and all seven current JARs against the local release bytes after requesting a Pages build. The site uses the existing public Pages source at the root of `gh-pages`.

If an attachment step stops after uploading one ZIP, rerun the failed jobs in the original Actions run while its verified artifact is retained. The attach job downloads that pair, checks every already uploaded ZIP byte-for-byte and fills only missing assets. Rebuilding can change Fiji timestamps and JAR bytes; a mismatching upload is reported before any missing file is added.

To recover a failed Pages publication, run **CR3 Fiji distribution** on **main** with `publish_existing_tag=vX.Y.Z` for the published stable GitHub release. This reuses its existing archive and accepts a byte-identical replay. Leaving the input empty runs the ordinary verification build.
