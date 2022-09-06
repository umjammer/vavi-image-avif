// Copyright 2020 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.io.InputStream;

import vavi.awt.image.avif.avif.avifIO;
import vavi.awt.image.avif.avif.avifResult;


abstract class io {

    static class avifIOMemoryReader extends avifIO {

        avifIO io; // this must be the first member for easy casting to avifIO*
        byte[] /* avifROData */ rodata;

        @Override
        public avifResult read(int readFlags,
                               long offset,
                               int size,
                               final byte[] /* avifROData */ out) {
            // printf("avifIOMemoryReaderRead offset %" PRIu64 " size %zu\n", offset, size);

            if (readFlags != 0) {
                // Unsupported readFlags
                return avifResult.AVIF_RESULT_IO_ERROR;
            }

            avifIOMemoryReader reader = (avifIOMemoryReader) io;

            // Sanitize/clamp incoming request
            if (offset > reader.rodata.length) {
                // The offset is past the end of the buffer.
                return avifResult.AVIF_RESULT_IO_ERROR;
            }
            long availableSize = reader.rodata.length - offset;
            if (size > availableSize) {
                size = (int) availableSize;
            }

            out = reader.rodata + offset;
            out.size = size;
            return avifResult.AVIF_RESULT_OK;
        }

        avifIOMemoryReader(final byte[] data, int size) {
            this.io.sizeHint = size;
            this.io.persistent = true;
            this.rodata = data;
        }

        @Override
        public avifResult write(int a, int b, byte[] c, int d) {
            // TODO Auto-generated method stub
            return null;
        }
    }

    // avifIOFileReader
    static class avifIOFileReader extends avifIO {

        avifIO io; // this must be the first member for easy casting to avifIO*
        byte[] /* avifRWData */ buffer;
        InputStream f;

        @Override
        public avifResult read(int readFlags,
                               long offset,
                               int size,
                               final byte[] /* avifROData */ out) {
            // printf("avifIOFileReaderRead offset %" PRIu64 " size %zu\n", offset, size);

            if (readFlags != 0) {
                // Unsupported readFlags
                return avifResult.AVIF_RESULT_IO_ERROR;
            }

            avifIOFileReader reader = (avifIOFileReader) io;

            // Sanitize/clamp incoming request
            if (offset > reader.io.sizeHint) {
                // The offset is past the EOF.
                return avifResult.AVIF_RESULT_IO_ERROR;
            }
            long availableSize = reader.io.sizeHint - offset;
            if (size > availableSize) {
                size = (int) availableSize;
            }

            if (size > 0) {
                if (offset > Long.MAX_VALUE) {
                    return avifResult.AVIF_RESULT_IO_ERROR;
                }
                if (reader.buffer.length < size) {
                    rawdata.avifRWDataRealloc(reader.buffer, size);
                }
                if (fseek(reader.f, (long) offset, SEEK_SET) != 0) {
                    return avifResult.AVIF_RESULT_IO_ERROR;
                }
                int bytesRead = reader.f.read(reader.buffer, 0, size);
                if (bytesRead == -1) {
                    return avifResult.AVIF_RESULT_IO_ERROR;
                    size = bytesRead;
                }
            }

            out = reader.buffer;
            out.size = size;
            return avifResult.AVIF_RESULT_OK;
        }

        avifIOFileReader(final String filename) {
            this.f = fopen(filename, "rb");
            if (!f) {
                return;
            }

            fseek(f, 0, SEEK_END);
            long fileSize = ftell(f);
            if (fileSize < 0) {
                f.close();
                return;
            }
            fseek(f, 0, SEEK_SET);

            this.io.sizeHint = fileSize;
            this.io.persistent = false;
            rawdata.avifRWDataRealloc(this.buffer, 1024);
        }

        @Override
        public avifResult write(int a, int b, byte[] c, int d) {
            return null;
        }
    }
}
