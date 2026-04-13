package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.bmff.IsoBox;

import java.util.Collections;
import java.util.List;

/**
 * Represents the parsed top-level structure of a Canon CR3 file.
 *
 * <p>A CR3 file is based on ISO BMFF (ISO 14496-12) and contains:</p>
 * <ul>
 *   <li>{@code ftyp} — file type identification (major brand "crx ")</li>
 *   <li>{@code moov} — movie box with metadata tracks</li>
 *   <li>{@code mdat} — media data containing JPEG previews and RAW sensor data</li>
 * </ul>
 *
 * <p>Track layout for Canon PowerShot G5 X Mark II:</p>
 * <ul>
 *   <li>Track 1: Small JPEG thumbnail (~160×120)</li>
 *   <li>Track 2: Main JPEG preview (~1620×1080)</li>
 *   <li>Track 3: Lossless CRX RAW data (full sensor resolution)</li>
 *   <li>Track 4: Lossless CRX HDR data (may not always be present)</li>
 * </ul>
 */
public final class Cr3Container {

    private final FtypBox ftypBox;
    private final IsoBox moovBox;
    private final IsoBox mdatBox;
    private final List<IsoBox> allBoxes;
    private final List<Cr3Track> tracks;

    /**
     * Creates a new CR3 container.
     *
     * @param ftypBox  the parsed ftyp box
     * @param moovBox  the moov box
     * @param mdatBox  the mdat box
     * @param allBoxes all top-level boxes
     * @param tracks   parsed track information
     */
    public Cr3Container(FtypBox ftypBox, IsoBox moovBox, IsoBox mdatBox,
                        List<IsoBox> allBoxes, List<Cr3Track> tracks) {
        this.ftypBox = ftypBox;
        this.moovBox = moovBox;
        this.mdatBox = mdatBox;
        this.allBoxes = Collections.unmodifiableList(allBoxes);
        this.tracks = Collections.unmodifiableList(tracks);
    }

    /** Returns the file type box. */
    public FtypBox ftypBox() { return ftypBox; }

    /** Returns the movie box containing metadata and track information. */
    public IsoBox moovBox() { return moovBox; }

    /** Returns the media data box containing actual image data. */
    public IsoBox mdatBox() { return mdatBox; }

    /** Returns all top-level boxes in file order. */
    public List<IsoBox> allBoxes() { return allBoxes; }

    /** Returns the parsed tracks. */
    public List<Cr3Track> tracks() { return tracks; }

    /**
     * Returns the number of tracks in this CR3 file.
     *
     * @return track count
     */
    public int trackCount() {
        return tracks.size();
    }

    @Override
    public String toString() {
        return "Cr3Container[brand=%s, tracks=%d, boxes=%d]"
                .formatted(ftypBox.majorBrand(), tracks.size(), allBoxes.size());
    }
}
