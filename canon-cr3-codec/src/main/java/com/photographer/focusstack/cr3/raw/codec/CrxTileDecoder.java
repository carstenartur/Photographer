package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.raw.Cr3Workers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schedules complete tile-plane readers so each task keeps its own entropy and row state.
 * When a consumer shares row accumulators across tiles, every tile of a given Bayer
 * position runs on the same worker in directory order.
 */
record CrxTileDecoder(CrxHeader header, int width, int height, int tileWidth, PlaneReader reader)
        implements CrxSensorDecoder {
    private static final int BAYER_PHASES = 4;

    @FunctionalInterface
    interface PlaneReader {
        void read(int tile, CrxHeader.Plane plane, int columns, int rows, RowConsumer consumer)
                throws IOException, Cr3Exception;
    }

    @Override
    public void decodeRows(RowConsumer consumer) throws IOException, Cr3Exception {
        decodePlanes(consumer, 0, 1, PlaneRowLimit.ALL_ROWS);
    }

    @Override
    public void decodeRows(RowConsumer consumer, int maxWorkers) throws IOException, Cr3Exception {
        decodeSelectedRows(consumer, maxWorkers, PlaneRowLimit.ALL_ROWS, false);
    }

    @Override
    public void decodeSelectedRows(RowConsumer consumer, int maxWorkers, PlaneRowLimit limit,
                                   boolean allowIndependentTiles) throws Cr3Exception, IOException {
        boolean partial = limit != PlaneRowLimit.ALL_ROWS;
        int workItems = allowIndependentTiles ? header.tiles().size() * BAYER_PHASES : BAYER_PHASES;
        int workers = Cr3Workers.count(maxWorkers, workItems);
        if (allowIndependentTiles && workers > 1 && (workers > BAYER_PHASES || partial)) {
            decodeIndependentTiles(consumer, limit, workers);
            return;
        }
        run(workers, worker -> decodePlanes(consumer, worker, workers, limit));
    }

    private void decodeIndependentTiles(RowConsumer consumer, PlaneRowLimit limit, int workers)
            throws IOException, Cr3Exception {
        var jobs = new ArrayList<PlaneJob>();
        for (int tile = 0; tile < header.tiles().size(); tile++) {
            int columns = columns(tile);
            for (var plane : header.tiles().get(tile).planes()) {
                int rows = selectedRows(limit, tile, plane, columns);
                if (rows > 0) {
                    jobs.add(new PlaneJob(tile, plane, columns, rows));
                }
            }
        }
        if (jobs.isEmpty()) {
            return;
        }
        // Start larger streams first. Workers take another plane as soon as they finish.
        jobs.sort(Comparator.comparingLong(PlaneJob::cost).reversed());
        var next = new AtomicInteger();
        run(Math.min(workers, jobs.size()), worker -> {
            int index;
            while ((index = next.getAndIncrement()) < jobs.size()) {
                var job = jobs.get(index);
                reader.read(job.tile, job.plane, job.columns, job.rows, consumer);
            }
        });
    }

    @FunctionalInterface
    private interface DecodeWork {
        void run(int worker) throws IOException, Cr3Exception;
    }

    private static void run(int workers, DecodeWork action) throws IOException, Cr3Exception {
        if (workers == 1) {
            action.run(0);
            return;
        }
        Exception[] failures = new Exception[workers];
        Cr3Workers.run(workers, worker -> {
            try {
                action.run(worker);
            } catch (IOException | Cr3Exception e) {
                failures[worker] = e;
            }
        });
        for (var failure : failures) {
            if (failure instanceof IOException io) throw io;
            if (failure instanceof Cr3Exception cr3) throw cr3;
        }
    }

    private void decodePlanes(RowConsumer consumer, int worker, int workers, PlaneRowLimit limit)
            throws IOException, Cr3Exception {
        for (int tile = 0; tile < header.tiles().size(); tile++) {
            int columns = columns(tile);
            for (var plane : header.tiles().get(tile).planes()) {
                if (plane.index() % workers == worker) {
                    int rows = selectedRows(limit, tile, plane, columns);
                    if (rows > 0) {
                        reader.read(tile, plane, columns, rows, consumer);
                    }
                }
            }
        }
    }

    private int columns(int tile) {
        return Math.min(tileWidth, width - tile * tileWidth) / 2;
    }

    private int selectedRows(PlaneRowLimit limit, int tile, CrxHeader.Plane plane, int columns) {
        int rows = limit.rowsToRead(tile * tileWidth + (plane.index() & 1), plane.index() / 2,
                columns, height / 2);
        if (rows < 0 || rows > height / 2) {
            throw new IllegalArgumentException("Invalid row limit for CRX tile plane");
        }
        return rows;
    }

    private record PlaneJob(int tile, CrxHeader.Plane plane, int columns, int rows) {
        long cost() {
            return plane.dataSize() * rows;
        }
    }
}
