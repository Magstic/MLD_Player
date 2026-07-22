package timeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import container.MldFile;
import container.MldParser;
import event.TrackDecodeResult;
import event.TrackDecoder;
import normalize.MdNormalizationResult;
import normalize.MdNormalizer;

public final class MldTimelineLoader {
    private MldTimelineLoader() {
    }

    public static PlaybackTimeline load(Path inputPath) throws IOException {
        MldFile file = new MldParser().parse(inputPath);
        TrackDecoder decoder = new TrackDecoder();
        List<TrackDecodeResult> decodedTracks = new ArrayList<TrackDecodeResult>();
        for (int i = 0; i < file.tracks.size(); i++) {
            decodedTracks.add(decoder.decode(file, file.tracks.get(i)));
        }
        MdNormalizationResult normalization = new MdNormalizer().normalize(decodedTracks);
        return new TimelineCompiler().compile(file, decodedTracks, normalization);
    }
}
