// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;


class rawdata {

    static void avifRWDataRealloc(byte[] /* avifRWData */ raw, int newSize) {
        if (raw.length != newSize) {
            byte[] old = raw;
            int oldSize = raw.length;
            raw = new byte[newSize];
            if (oldSize != 0) {
                int bytesToCopy = (oldSize < raw.length) ? oldSize : raw.length;
                System.arraycopy(old, 0, raw, 0, bytesToCopy);
            }
        }
    }

    static void avifRWDataSet(byte[] /* avifRWData */ raw, final byte[] data, int len) {
        if (len != 0) {
            avifRWDataRealloc(raw, len);
            System.arraycopy(data, 0, raw, 0, len);
        }
    }
}
