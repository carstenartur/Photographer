package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.raw.Cr3Workers;
import java.io.IOException;

/** A Bayer position belongs to one worker across all tiles, including its optical-black rows. */
record CrxTileDecoder(CrxHeader header, PlaneReader reader) implements CrxSensorDecoder {
    @FunctionalInterface
    interface PlaneReader {
        void read(int tileIndex, CrxHeader.Plane plane, RowConsumer consumer) throws IOException, Cr3Exception;
    }

    @Override
    public void decodeRows(RowConsumer consumer) throws IOException, Cr3Exception {
        decodePlanes(consumer, 0, 1);
    }

    @Override
    public void decodeRows(RowConsumer consumer, int maxWorkers) throws IOException, Cr3Exception {
        int workers = Cr3Workers.count(maxWorkers, 4);
        if (workers == 1) {
            decodeRows(consumer);
            return;
        }
        Exception[] failures = new Exception[workers];
        Cr3Workers.run(workers, worker -> {
            try {
                decodePlanes(consumer, worker, workers);
            } catch (IOException | Cr3Exception e) {
                failures[worker] = e;
            }
        });
        for (var failure : failures) {
            if (failure instanceof IOException io) throw io;
            if (failure instanceof Cr3Exception cr3) throw cr3;
        }
    }

    private void decodePlanes(RowConsumer consumer, int worker, int workers) throws IOException, Cr3Exception {
        for (int tile = 0; tile < header.tiles().size(); tile++) {
            for (var plane : header.tiles().get(tile).planes()) {
                if (plane.index() % workers == worker) {
                    reader.read(tile, plane, consumer);
                }
            }
        }
    }
}
