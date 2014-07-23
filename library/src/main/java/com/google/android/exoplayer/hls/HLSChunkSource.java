package com.google.android.exoplayer.hls;

import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.TrackInfo;
import com.google.android.exoplayer.chunk.Chunk;
import com.google.android.exoplayer.chunk.ChunkOperationHolder;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.chunk.MediaChunk;
import com.google.android.exoplayer.chunk.HLSMediaChunk;
import com.google.android.exoplayer.parser.ts.TSExtractor;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.Util;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HLSChunkSource implements ChunkSource {
    private static final String TAG = "HLSChunkSource";
    private final String baseUrl;
    private final DataSource dataSource;
    private final FormatEvaluator formatEvaluator;
    private final FormatEvaluator.Evaluation evaluation;
    private final Format[] formats;

    private MainPlaylist mainPlaylist;
    private VariantPlaylist currentVariantPlaylist;

    private final ArrayList<Integer> trackList;
    private final ArrayList<TrackInfo> trackInfoList;
    private final ArrayList<MediaFormat> videoMediaFormats;

    public HLSChunkSource(String baseUrl, MainPlaylist mainPlaylist, DataSource dataSource, FormatEvaluator formatEvaluator) {
        this.baseUrl = baseUrl;
        this.dataSource = dataSource;
        this.formatEvaluator = formatEvaluator;
        this.evaluation = new FormatEvaluator.Evaluation();
        this.mainPlaylist = mainPlaylist;

        int entryCount = mainPlaylist.entries.size();
        formats = new Format[entryCount];

        int hasVideo = 0;
        int hasAudio = 0;

        for (MainPlaylist.Entry entry : mainPlaylist.entries) {
            if (entry.codecs.contains("mp4a")) {
                hasAudio = 1;
            }
            if (entry.codecs.contains("avc1")) {
                hasVideo = 1;
            }
        }

        int i = 0;
        for (MainPlaylist.Entry entry : mainPlaylist.entries) {
            formats[i] = new Format(i, "video/mp2t", entry.width, entry.height, 2, 44100, entry.bps);
            i++;
        }

        trackList = new ArrayList<Integer>();
        trackInfoList = new ArrayList<TrackInfo>();
        long durationUs = (long)mainPlaylist.entries.get(0).variantPlaylist.duration * 1000000;
        if (hasAudio == 1) {
            trackList.add(TSExtractor.TYPE_AUDIO);
            trackInfoList.add(new TrackInfo(MimeTypes.AUDIO_AAC, durationUs));
        }
        if (hasVideo == 1) {
            trackList.add(TSExtractor.TYPE_VIDEO);
            trackInfoList.add(new TrackInfo(MimeTypes.VIDEO_H264, durationUs));
        }

        i = 0;
        videoMediaFormats = new ArrayList<MediaFormat>();

        for (MainPlaylist.Entry entry : mainPlaylist.entries) {
            if (hasVideo == 1) {
                videoMediaFormats.add(MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, MediaFormat.NO_VALUE,
                        entry.width, entry.height, null));
            }
            i++;
        }

        Arrays.sort(formats, new Format.DecreasingBandwidthComparator());
    }

    @Override
    public int getTrackCount() {

        return trackList.size();
    }

    @Override
    public TrackInfo getTrackInfo(int track) {

        return trackInfoList.get(track);
    }

    @Override
    public void getMaxVideoDimensions(MediaFormat out) {

        out.setMaxVideoDimensions(1920, 1080);
    }

    @Override
    public void enable(int track) {

    }

    @Override
    public void disable(int track, List<MediaChunk> queue) {

    }

    @Override
    public void continueBuffering(long playbackPositionUs) {

    }

    @Override
    public void getChunkOperation(List<? extends MediaChunk> queue, long seekPositionUs, long playbackPositionUs, ChunkOperationHolder out) {
        evaluation.queueSize = queue.size();
        formatEvaluator.evaluate(queue, playbackPositionUs, formats, evaluation);
        Format selectedFormat = evaluation.format;
        out.queueSize = evaluation.queueSize;

        if (selectedFormat == null) {
            out.chunk = null;
            return;
        } else if (out.queueSize == queue.size() && out.chunk != null
                && out.chunk.format.id == evaluation.format.id) {
            // We already have a chunk, and the evaluation hasn't changed either the format or the size
            // of the queue. Do nothing.
            return;
        }

        int nextChunkIndex;

        currentVariantPlaylist = mainPlaylist.entries.get(evaluation.format.id).variantPlaylist;

        if (queue.isEmpty()) {
            nextChunkIndex = currentVariantPlaylist.mediaSequence;
        } else {
            nextChunkIndex = queue.get(out.queueSize - 1).nextChunkIndex;
        }

        if (nextChunkIndex == -1) {
            out.chunk = null;
            return;
        }

        boolean isLastChunk = (nextChunkIndex == currentVariantPlaylist.mediaSequence + currentVariantPlaylist.entries.size() - 1);
        VariantPlaylist.Entry entry = currentVariantPlaylist.entries.get(nextChunkIndex - currentVariantPlaylist.mediaSequence);

        String chunkUrl = Util.makeAbsoluteUrl(currentVariantPlaylist.url, entry.url);
        Log.d(TAG, "opening " + chunkUrl);
        Uri uri = null;
        if (entry.keyEntry != null) {
            String dataUrl = null;
            String keyUrl = null;
            try {
                dataUrl = URLEncoder.encode(chunkUrl, "utf-8");
                keyUrl = URLEncoder.encode(entry.keyEntry.uri, "utf-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            String iv = entry.keyEntry.IV;
            if (iv == null) {
                // XXX: is this nextChunkIndex or nextChunkIndex + 1 ?
                iv = Integer.toHexString(nextChunkIndex);
            }
            uri = Uri.parse("aes://dummy?dataUrl=" + dataUrl + "&keyUrl=" + keyUrl + "&iv=" + iv);
        } else {
            uri = Uri.parse(chunkUrl);
        }

        DataSpec dataSpec = new DataSpec(uri, entry.offset, entry.length, null);
        MediaFormat videoMediaFormat;
        if (selectedFormat.id < videoMediaFormats.size()) {
            videoMediaFormat = videoMediaFormats.get(selectedFormat.id);
        } else {
            videoMediaFormat = null;
        }
        Chunk mediaChunk = new HLSMediaChunk(dataSource, trackList, videoMediaFormat, dataSpec, selectedFormat,
                                            (long)(entry.startTime * 1000000), (long)((entry.startTime + entry.extinf) * 1000000),
                                            isLastChunk ? -1 : nextChunkIndex + 1);
        out.chunk = mediaChunk;
    }

    @Override
    public IOException getError() {
        return null;
    }
}
