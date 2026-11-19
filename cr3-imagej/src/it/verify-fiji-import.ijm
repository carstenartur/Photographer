// Run with a directory holding sample.cr3 and its full-sensor reference.tiff.
dir = getArgument() + File.separator;
run("CR3 Sensor Data...", "path=[" + dir + "sample.cr3]");
sensor = getImageID();
w = getWidth();
h = getHeight();
bits = bitDepth();
open(dir + "reference.tiff");
reference = getImageID();
imageCalculator("Difference create 32-bit", sensor, reference);
getRawStatistics(n, mean, min, max);
sensorResult = w + "x" + h + " " + bits + " bits " + d2s(n,0) + " samples max=" + d2s(max,0);
close();
selectImage(reference);
close();
selectImage(sensor);
close();

run("CR3 Color Image...", "path=[" + dir + "sample.cr3]");
getDimensions(w, h, channels, slices, frames);
if (w != 5495 || h != 3667 || channels != 3 || slices != 1 || frames != 1 || bitDepth() != 16)
    exit("Unexpected developed RGB dimensions or bit depth");
for (channel = 1; channel <= 3; channel++) {
    Stack.setChannel(channel);
    fine = 0;
    for (y = 73; y < 89; y++)
        for (x = 107; x < 123; x++)
            if (getPixel(x, y) % 257 != 0)
                fine++;
    if (fine == 0)
        exit("No values between expanded 8-bit levels in channel " + channel);
}
close();
File.saveString(sensorResult + "\n", dir + "result.txt");
