// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import vavi.awt.image.avif.read.avifBoxHeader;
import vavi.awt.image.avif.read.avifROStream;


class stream {
// ---------------------------------------------------------------------------
// avifROStream

    boolean avifROStreamReadString(DataInputStream stream, String output, int outputSize) {
        // Check for the presence of a null terminator in the stream.
        int remainingBytes = avifROStreamRemainingBytes(stream);
        final byte[] p = avifROStreamCurrent(stream);
        boolean foundNullTerminator = false;
        for (int i = 0; i < remainingBytes; ++i) {
            if (p[i] == 0) {
                foundNullTerminator = true;
                break;
            }
        }
        if (!foundNullTerminator) {
            System.err.printf("%s: Failed to find a null terminator when reading a string", null);
            return false;
        }

        final String streamString = new String(p);
        int stringLen = streamString.length();
        stream.offset += stringLen + 1; // update the stream to have read the
                                        // "whole string" in

        if (output != null && outputSize != 0) {
            // clamp to our output buffer
            if (stringLen >= outputSize) {
                stringLen = outputSize - 1;
            }
            memcpy(output, streamString, stringLen);
            output[stringLen] = 0;
        }
        return true;
    }

    static avifBoxHeader avifROStreamReadBoxHeaderPartial(DataInputStream stream) {
        avifBoxHeader header = new avifBoxHeader();
        int startOffset = stream.offset;

        int smallSize = stream.readInt();
        stream.readFully(header.type, 0, 4);

        long size = smallSize;
        if (size == 1) {
            size = stream.readLong();
        }

        if (header.type.equals("uuid")) {
            stream.skipBytes(16);
        }

        int bytesRead = stream.offset - startOffset;
        if ((size < bytesRead) || ((size - bytesRead) > Integer.MAX_VALUE)) {
            System.err.printf("%s: Header size overflow check failure", null);
            return null;
        }
        header.size = (int) (size - bytesRead);
        return header;
    }

    static avifBoxHeader avifROStreamReadBoxHeader(DataInputStream stream) throws IOException {
        avifBoxHeader header = avifROStreamReadBoxHeaderPartial(stream);
        if (header == null)
            return null;
        if (header.size > stream.available()) {
            System.err.printf("%s: Child box too large, possibly truncated data", null);
            return null;
        }
        return header;
    }

    static int[] avifROStreamReadVersionAndFlags(DataInputStream stream, int flags) throws IOException {
        byte[] versionAndFlags = new byte[4];
        stream.readFully(versionAndFlags);
        if (flags != 0) {
            flags = (versionAndFlags[1] << 16) + (versionAndFlags[2] << 8) + (versionAndFlags[3] << 0);
        }
        return new int[] { versionAndFlags[0], flags };
    }

    static boolean avifROStreamReadAndEnforceVersion(DataInputStream stream, int enforcedVersion) throws IOException {
        int[] versionAndFlags = avifROStreamReadVersionAndFlags(stream, 0);
        if (versionAndFlags[0] != enforcedVersion) {
            System.err.printf("%s: Expecting box version %u, got version %u", null, enforcedVersion, versionAndFlags[0]);
            return false;
        }
        return true;
    }

    static int avifRWStreamWriteFullBox(DataOutputStream stream, final String type, int contentSize, int version, int flags) {
        int marker = stream.offset;
        int headerSize = Integer.BYTES + 4 /* size of type */;
        if (version != -1) {
            headerSize += 4;
        }

        makeRoom(stream, headerSize);
        memset(stream.raw, stream.offset, 0, headerSize);
        int noSize = avifHTONL((int) (headerSize + contentSize));
        memcpy(stream.raw + stream.offset, noSize, Integer.BYTES);
        memcpy(stream.raw + stream.offset + 4, type, 4);
        if (version != -1) {
            stream.raw[stream.offset + 8] = (byte[]) version;
            stream.raw[stream.offset + 9] = (byte[]) ((flags >> 16) & 0xff);
            stream.raw[stream.offset + 10] = (byte[]) ((flags >> 8) & 0xff);
            stream.raw[stream.offset + 11] = (byte[]) ((flags >> 0) & 0xff);
        }
        stream.offset += headerSize;

        return marker;
    }

    static int avifRWStreamWriteBox(DataOutputStream stream, final String type, int contentSize) {
        return avifRWStreamWriteFullBox(stream, type, contentSize, -1, 0);
    }

    static void avifRWStreamFinishBox(DataOutputStream stream, int marker) {
        int noSize = avifHTONL((int) (stream.offset - marker));
        memcpy(stream.raw.data + marker, noSize, Integer.BYTES);
    }

    static void avifRWStreamWriteZeros(DataOutputStream stream, int byteCount) {
        makeRoom(stream, byteCount);
        byte[] p = stream.raw.data + stream.offset;
        byte[] end = p + byteCount;
        while (p != end) {
            p = 0;
            ++p;
        }
        stream.offset += byteCount;
    }
}
