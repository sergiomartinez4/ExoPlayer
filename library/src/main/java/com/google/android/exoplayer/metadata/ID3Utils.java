package com.google.android.exoplayer.metadata;

import android.util.Log;

import com.google.android.exoplayer.hls.Packet;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by bhnath on 10/1/14.
 */
public class ID3Utils {
    public static final String TAG = ID3Utils.class.getSimpleName();

    public static class ID3Tag {
        public long timestamp;
        public List<ID3Data> data;

        public ID3Tag(long timestamp, List<ID3Data> data) {
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    public static class ID3Data {
        public String frameId;
        public String frameData;

        public ID3Data(String frameId, String frameData) {
            this.frameId = frameId;
            this.frameData = frameData;
        }
    }

    public ID3Utils() {}

    public static ID3Tag parseID3Tag(Packet.UnsignedByteArray packet) {
        int offset = 0;
        String id3Tag = new String(packet.array(), offset, 3);

        if (!id3Tag.equals("ID3")) {
            Log.e(TAG, "Error - not an ID3 tag. Header did not start with 'ID3'");
        }

        // skip the 2 version bytes for the ID3 tag
        int id3TagOffset = offset + 5;
        int id3Flags = packet.get(id3TagOffset);
        boolean id3ExtendedHeader = (id3Flags & 0x40) !=0;

        id3TagOffset++;

        int size = getSynchSafeInteger(packet, id3TagOffset);
        if (size == 0 || size > packet.length()) {
            Log.e(TAG, "Error - ID3 tag size is incorrect.");
        }

        id3TagOffset+=4;

        if (id3ExtendedHeader) {
            id3TagOffset += 10;
        }

        List<ID3Data> id3Data = new ArrayList<ID3Data>();

        int sizeOffset = id3TagOffset + size;
        while (id3TagOffset < sizeOffset) {
            String frameId = new String(packet.array(), id3TagOffset, 4);
            id3TagOffset += 4;
            int frameSize = getSynchSafeInteger(packet, id3TagOffset);
            id3TagOffset += 6; // 4 for the size, skipped the 2 for the flags
            String frameData = new String(packet.array(), id3TagOffset, frameSize);
            id3Data.add(new ID3Data(frameId, frameData));

            id3TagOffset += frameSize;
        }

        return new ID3Tag(0, id3Data);
    }

    private static int getSynchSafeInteger(Packet.UnsignedByteArray data, int offset) {
        return (data.get(offset) << 21) | (data.get(offset + 1) << 14) | (data.get(offset + 2) << 7) | (data.get(offset + 3));
    }
}
