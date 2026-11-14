// Run with a directory holding sample.cr3 and its full-sensor reference.tiff.
dir = getArgument() + File.separator;
run("CR3 Sensor Data...", "path=[" + dir + "sample.cr3]");
sensor = getImageID();
w = getWidth();
h = getHeight();
bits = bitDepth();
open(dir + "reference.tiff");
imageCalculator("Difference create 32-bit", sensor, getImageID());
getRawStatistics(n, mean, min, max);
File.saveString(w + "x" + h + " " + bits + " bits " + d2s(n,0) + " samples max=" + d2s(max,0) + "\n", dir + "result.txt");
close("*");
run("Quit");
