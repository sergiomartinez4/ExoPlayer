package com.google.android.exoplayer.chunk;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.ParserException;
import com.google.android.exoplayer.SampleHolder;

public abstract class HLSExtractor {
    /**
     * An attempt to read from the input stream returned 0 bytes of data.
     */
    public static final int RESULT_NEED_MORE_DATA = 1;
    /**
     * The end of the input stream was reached.
     */
    public static final int RESULT_END_OF_STREAM = 2;
    /**
     * A media sample was read.
     */
    public static final int RESULT_READ_SAMPLE_FULL = 3;

    public static final int TYPE_VIDEO = 0;
    public static final int TYPE_AUDIO = 1;

    static public class UnsignedByteArray {
        byte[] array;
        public UnsignedByteArray(int length) {
            array = new byte[length];
        }

        public void resize(int newLength) {
            byte [] newArray = new byte[newLength];
            System.arraycopy(array, 0, newArray, 0, array.length);
            array = newArray;
        }

        public int length() {
            return array.length;
        }

        public int get(int index) {
            return (int)array[index] & 0xff;
        }
        public int getShort(int index) {
            return get(index) << 8 | get(index + 1);
        }
        public byte[] array() {
            return array;
        }

        public long getLong(int offset) {
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result <<= 8;
                result |= get(offset + i);
            }
            return result;
        }
    }

    abstract public int read(int type, SampleHolder out)
            throws ParserException;

    abstract public MediaFormat getAudioMediaFormat();

    abstract public boolean isReadFinished();
}