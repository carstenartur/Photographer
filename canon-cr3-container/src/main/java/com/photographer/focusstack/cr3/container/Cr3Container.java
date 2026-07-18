package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.bmff.CcdtEntry;
import com.photographer.focusstack.cr3.bmff.CctpBox;
import com.photographer.focusstack.cr3.bmff.CncvBox;
import com.photographer.focusstack.cr3.bmff.CtboBox;
import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.PrvwBox;
import com.photographer.focusstack.cr3.bmff.ThmbBox;

import java.util.Collections;
import java.util.List;

/** Represents the parsed top-level structure of a Canon CR3 file. */
public final class Cr3Container {

    private final FtypBox ftypBox;
    private final IsoBox moovBox;
    private final IsoBox mdatBox;
    private final List<IsoBox> allBoxes;
    private final List<Cr3Track> tracks;
    private final CncvBox cncvBox;
    private final CctpBox cctpBox;
    private final CtboBox ctboBox;
    private final ThmbBox thmbBox;
    private final PrvwBox prvwBox;

    public Cr3Container(FtypBox ftypBox, IsoBox moovBox, IsoBox mdatBox,
                        List<IsoBox> allBoxes, List<Cr3Track> tracks,
                        CncvBox cncvBox, CctpBox cctpBox, CtboBox ctboBox,
                        ThmbBox thmbBox, PrvwBox prvwBox) {
        this.ftypBox = ftypBox;
        this.moovBox = moovBox;
        this.mdatBox = mdatBox;
        this.allBoxes = Collections.unmodifiableList(allBoxes);
        this.tracks = Collections.unmodifiableList(tracks);
        this.cncvBox = cncvBox;
        this.cctpBox = cctpBox;
        this.ctboBox = ctboBox;
        this.thmbBox = thmbBox;
        this.prvwBox = prvwBox;
    }

    public Cr3Container(FtypBox ftypBox, IsoBox moovBox, IsoBox mdatBox,
                        List<IsoBox> allBoxes, List<Cr3Track> tracks) {
        this(ftypBox, moovBox, mdatBox, allBoxes, tracks, null, null, null, null, null);
    }

    public FtypBox ftypBox() { return ftypBox; }
    public IsoBox moovBox() { return moovBox; }
    public IsoBox mdatBox() { return mdatBox; }
    public List<IsoBox> allBoxes() { return allBoxes; }
    public List<Cr3Track> tracks() { return tracks; }
    public CncvBox cncvBox() { return cncvBox; }
    public CctpBox cctpBox() { return cctpBox; }
    public CtboBox ctboBox() { return ctboBox; }
    public ThmbBox thmbBox() { return thmbBox; }
    public PrvwBox prvwBox() { return prvwBox; }
    public int trackCount() { return tracks.size(); }

    public CcdtEntry findCcdtForTrack(int trakIndex) {
        if (cctpBox == null) return null;
        for (CcdtEntry entry : cctpBox.entries()) {
            if (entry.trakIndex() == trakIndex) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Cr3Container[brand=%s, tracks=%d, boxes=%d]"
                .formatted(ftypBox.majorBrand(), tracks.size(), allBoxes.size());
    }
}
