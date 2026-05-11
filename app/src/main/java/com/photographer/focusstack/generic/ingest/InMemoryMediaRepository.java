package com.photographer.focusstack.generic.ingest;

import com.photographer.focusstack.common.media.MediaItem;
import com.photographer.focusstack.common.media.MediaRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trivial in-memory {@link MediaRepository} backed by a concurrent map.
 *
 * <p>Sufficient for unit tests, replay sessions and the initial wiring of the
 * generic ingest workflow. Production deployments should plug in a JPA-backed
 * implementation via the same interface.
 */
public class InMemoryMediaRepository implements MediaRepository {

    private final Map<String, MediaItem> store = new ConcurrentHashMap<>();

    @Override
    public Optional<MediaItem> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<MediaItem> findByCamera(String cameraId) {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem m : store.values()) {
            if (cameraId.equals(m.cameraId())) out.add(m);
        }
        return out;
    }

    @Override
    public MediaItem save(MediaItem item) {
        store.put(item.id(), item);
        return item;
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
