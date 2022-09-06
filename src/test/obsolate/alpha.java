// Copyright 2020 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import vavi.awt.image.avif.avif.avifRGBFormat;
import vavi.awt.image.avif.avif.avifRGBImage;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;


class alpha {

    static class avifAlphaParams {
        int width;
        int height;
        int srcDepth;
        avifRange srcRange;
        byte[] srcPlane;
        int srcRowBytes;
        int srcOffsetBytes;
        int srcPixelBytes;
        int dstDepth;
        avifRange dstRange;
        byte[] dstPlane;
        int dstRowBytes;
        int dstOffsetBytes;
        int dstPixelBytes;

    private int calcMaxChannel(int depth, avifRange range) {
        int maxChannel = (1 << depth) - 1;
        if (range == avifRange.AVIF_RANGE_LIMITED) {
            maxChannel = reformat.avifFullToLimitedY(depth, maxChannel);
        }
        return maxChannel;
    }

    boolean avifFillAlpha() {
        if (this.dstDepth > 8) {
            final short maxChannel = (short) calcMaxChannel(this.dstDepth, this.dstRange);
            for (int j = 0; j < this.height; ++j) {
                int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                ShortBuffer sb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P).asShortBuffer();
                int sb_P = 0;
                for (int i = 0; i < this.width; ++i) {
                    sb.put(sb_P, maxChannel);
                    sb_P += this.dstPixelBytes / Short.BYTES;
                }
            }
        } else {
            final byte maxChannel = (byte) calcMaxChannel(this.dstDepth, this.dstRange);
            for (int j = 0; j < this.height; ++j) {
                int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                for (int i = 0; i < this.width; ++i) {
                    this.dstPlane[dstRow_P] = maxChannel;
                    dstRow_P += this.dstPixelBytes;
                }
            }
        }
        return true;
    }

// Note: The [limited . limited] paths are here for completeness, but in practice those
//       paths will never be used, as avifRGBImage is always full range.
    boolean avifReformatAlpha() {
        final int srcMaxChannel = (1 << this.srcDepth) - 1;
        final int dstMaxChannel = (1 << this.dstDepth) - 1;
        final float srcMaxChannelF = srcMaxChannel;
        final float dstMaxChannelF = dstMaxChannel;

        if (this.srcDepth == this.dstDepth) {
            // no depth rescale

            if ((this.srcRange == avifRange.AVIF_RANGE_FULL) && (this.dstRange == avifRange.AVIF_RANGE_FULL)) {
                // no depth rescale, no range conversion

                if (this.srcDepth > 8) {
                    // no depth rescale, no range conversion, short . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                .asShortBuffer();
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, ssb.get(i * this.srcPixelBytes / Short.BYTES));
                        }
                    }
                } else {
                    // no depth rescale, no range conversion, byte[] . byte[]

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        for (int i = 0; i < this.width; ++i) {
                            this.dstPlane[dstRow_P + i * this.dstPixelBytes] = this.srcPlane[srcRow_P +
                                                                                                   i * this.srcPixelBytes];
                        }
                    }
                }
            } else if ((this.srcRange == avifRange.AVIF_RANGE_LIMITED) && (this.dstRange == avifRange.AVIF_RANGE_FULL)) {
                // limited . full

                if (this.srcDepth > 8) {
                    // no depth rescale, limited . full, short . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                .asShortBuffer();
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                            int dstAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                        }
                    }
                } else {
                    // no depth rescale, limited . full, byte[] . byte[]

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = this.srcPlane[srcRow_P + i * this.srcPixelBytes];
                            int dstAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                            this.dstPlane[dstRow_P + i * this.dstPixelBytes] = (byte) dstAlpha;
                        }
                    }
                }
            } else if ((this.srcRange == avifRange.AVIF_RANGE_FULL) && (this.dstRange == avifRange.AVIF_RANGE_LIMITED)) {
                // full . limited

                if (this.srcDepth > 8) {
                    // no depth rescale, full . limited, short . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                .asShortBuffer();
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                            int dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, srcAlpha);
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                        }
                    }
                } else {
                    // no depth rescale, full . limited, byte[] . byte[]

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = this.srcPlane[srcRow_P + i * this.srcPixelBytes];
                            int dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, srcAlpha);
                            this.dstPlane[dstRow_P + i * this.dstPixelBytes] = (byte) dstAlpha;
                        }
                    }
                }
            } else if ((this.srcRange == avifRange.AVIF_RANGE_LIMITED) && (this.dstRange == avifRange.AVIF_RANGE_LIMITED)) {
                // limited . limited

                if (this.srcDepth > 8) {
                    // no depth rescale, limited . limited, short . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                .asShortBuffer();
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, ssb.get(i * this.srcPixelBytes / Short.BYTES));
                        }
                    }
                } else {
                    // no depth rescale, limited . limited, byte[] . byte[]

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        for (int i = 0; i < this.width; ++i) {
                            this.srcPlane[dstRow_P + i * this.dstPixelBytes] = this.dstPlane[srcRow_P +
                                                                                                   i * this.srcPixelBytes];
                        }
                    }
                }
            }

        } else {
            // depth rescale

            if ((this.srcRange == avifRange.AVIF_RANGE_FULL) && (this.dstRange == avifRange.AVIF_RANGE_FULL)) {
                // depth rescale, no range conversion

                if (this.srcDepth > 8) {
                    if (this.dstDepth > 8) {
                        // depth rescale, no range conversion, short . short

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                            }
                        }
                    } else {
                        // depth rescale, no range conversion, short . byte[]

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                this.dstPlane[dstRow_P + i * this.dstPixelBytes] = (byte) dstAlpha;
                            }
                        }
                    }
                } else {
                    // depth rescale, no range conversion, byte[] . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = this.srcPlane[srcRow_P + i * this.srcPixelBytes];
                            float alphaF = srcAlpha / srcMaxChannelF;
                            int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                            dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                        }
                    }

                    // If (srcDepth == 8), dstDepth must be >8 otherwise we'd be
                    // in the (this.srcDepth == this.dstDepth) block above.
                    // assert(this.dstDepth > 8);
                }
            } else if ((this.srcRange == avifRange.AVIF_RANGE_LIMITED) && (this.dstRange == avifRange.AVIF_RANGE_FULL)) {
                // limited . full

                if (this.srcDepth > 8) {
                    if (this.dstDepth > 8) {
                        // depth rescale, limited . full, short . short

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                srcAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                            }
                        }
                    } else {
                        // depth rescale, limited . full, short . byte[]

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                srcAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                this.dstPlane[dstRow_P + i * this.dstPixelBytes] = (byte) dstAlpha;
                            }
                        }
                    }
                } else {
                    // depth rescale, limited . full, byte[] . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = this.srcPlane[srcRow_P + i * this.srcPixelBytes];
                            srcAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                            float alphaF = srcAlpha / srcMaxChannelF;
                            int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                            dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                        }
                    }

                    // If (srcDepth == 8), dstDepth must be >8 otherwise we'd be
                    // in the (this.srcDepth == this.dstDepth) block above.
                    // assert(this.dstDepth > 8);
                }
            } else if ((this.srcRange == avifRange.AVIF_RANGE_FULL) && (this.dstRange == avifRange.AVIF_RANGE_LIMITED)) {
                // full . limited

                if (this.srcDepth > 8) {
                    if (this.dstDepth > 8) {
                        // depth rescale, full . limited, short . short

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, dstAlpha);
                                dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                            }
                        }
                    } else {
                        // depth rescale, full . limited, short . byte[]

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, dstAlpha);
                                this.dstPlane[dstRow_P + i * this.dstPixelBytes] = (byte) dstAlpha;
                            }
                        }
                    }
                } else {
                    // depth rescale, full . limited, byte[] . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = this.srcPlane[srcRow_P + i * this.srcPixelBytes];
                            float alphaF = srcAlpha / srcMaxChannelF;
                            int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                            dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                            dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, dstAlpha);
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                        }
                    }

                    // If (srcDepth == 8), dstDepth must be >8 otherwise we'd be
                    // in the (this.srcDepth == this.dstDepth) block above.
                    // assert(this.dstDepth > 8);
                }
            } else if ((this.srcRange == avifRange.AVIF_RANGE_LIMITED) && (this.dstRange == avifRange.AVIF_RANGE_LIMITED)) {
                // limited . limited

                if (this.srcDepth > 8) {
                    if (this.dstDepth > 8) {
                        // depth rescale, limited . limited, short . short

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                srcAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, dstAlpha);
                                dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                            }
                        }
                    } else {
                        // depth rescale, limited . limited, short . byte[]

                        for (int j = 0; j < this.height; ++j) {
                            int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                            int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                            ShortBuffer ssb = ByteBuffer.wrap(this.srcPlane, srcRow_P, this.srcPlane.length - srcRow_P)
                                    .asShortBuffer();
                            for (int i = 0; i < this.width; ++i) {
                                int srcAlpha = ssb.get(i * this.srcPixelBytes / Short.BYTES);
                                srcAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                                float alphaF = srcAlpha / srcMaxChannelF;
                                int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                                dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                                dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, dstAlpha);
                                this.dstPlane[dstRow_P + i * this.dstPixelBytes] = (byte) dstAlpha;
                            }
                        }
                    }
                } else {
                    // depth rescale, limited . limited, byte[] . short

                    for (int j = 0; j < this.height; ++j) {
                        int srcRow_P = this.srcOffsetBytes + (j * this.srcRowBytes);
                        int dstRow_P = this.dstOffsetBytes + (j * this.dstRowBytes);
                        ShortBuffer dsb = ByteBuffer.wrap(this.dstPlane, dstRow_P, this.dstPlane.length - dstRow_P)
                                .asShortBuffer();
                        for (int i = 0; i < this.width; ++i) {
                            int srcAlpha = this.srcPlane[srcRow_P + i * this.srcPixelBytes];
                            srcAlpha = reformat.avifLimitedToFullY(this.srcDepth, srcAlpha);
                            float alphaF = srcAlpha / srcMaxChannelF;
                            int dstAlpha = (int) (0.5f + (alphaF * dstMaxChannelF));
                            dstAlpha = avif.AVIF_CLAMP(dstAlpha, 0, dstMaxChannel);
                            dstAlpha = reformat.avifFullToLimitedY(this.dstDepth, dstAlpha);
                            dsb.put(i * this.dstPixelBytes / Short.BYTES, (short) dstAlpha);
                        }
                    }

                    // If (srcDepth == 8), dstDepth must be >8 otherwise we'd be
                    // in the (this.srcDepth == this.dstDepth) block above.
                    // assert(this.dstDepth > 8);
                }
            }
        }

        return true;
    }
    }

    static avifResult avifRGBImagePremultiplyAlpha(avifRGBImage rgb) {
        // no data
        if (rgb.pixels != null || rgb.rowBytes != 0) {
            return avifResult.AVIF_RESULT_REFORMAT_FAILED;
        }

        // no alpha.
        if (!rgb.format.avifRGBFormatHasAlpha()) {
            return avifResult.AVIF_RESULT_INVALID_ARGUMENT;
        }

        avifResult libyuvResult = reformat_libyuv.avifRGBImagePremultiplyAlphaLibYUV(rgb);
        if (libyuvResult != avifResult.AVIF_RESULT_NOT_IMPLEMENTED) {
            return libyuvResult;
        }

        assert (rgb.depth >= 8 && rgb.depth <= 16);

        int max = (1 << rgb.depth) - 1;
        float maxF = max;

        if (rgb.depth > 8) {
            if (rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_RGBA || rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_BGRA) {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes; // rgb.pixels
                    for (int i = 0; i < rgb.width; ++i) {
                        ShortBuffer pixel = ByteBuffer.wrap(rgb.pixels, row_P + i * 8, i * 8 / Short.BYTES).asShortBuffer();
                        short a = pixel.get(3);
                        if (a >= max) {
                            // opaque is no-op
                            continue;
                        } else if (a == 0) {
                            // result must be zero
                            pixel.put(0, (short) 0);
                            pixel.put(1, (short) 0);
                            pixel.put(2, (short) 0);
                        } else {
                            // a < maxF is always true now, so we don't need
                            // clamp here
                            pixel.put(0, (short) Math.round(pixel.get(0) * (float) a / maxF));
                            pixel.put(1, (short) Math.round(pixel.get(1) * (float) a / maxF));
                            pixel.put(2, (short) Math.round(pixel.get(2) * (float) a / maxF));
                        }
                    }
                }
            } else {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes; // rgb.pixels
                    for (int i = 0; i < rgb.width; ++i) {
                        ShortBuffer pixel = ByteBuffer.wrap(rgb.pixels, row_P + i * 8, i * 8 / Short.BYTES).asShortBuffer();
                        short a = pixel.get(0);
                        if (a >= max) {
                            continue;
                        } else if (a == 0) {
                            pixel.put(1, (short) 0);
                            pixel.put(2, (short) 0);
                            pixel.put(3, (short) 0);
                        } else {
                            pixel.put(1, (short) Math.round(pixel.get(1) * (float) a / maxF));
                            pixel.put(2, (short) Math.round(pixel.get(2) * (float) a / maxF));
                            pixel.put(3, (short) Math.round(pixel.get(3) * (float) a / maxF));
                        }
                    }
                }
            }
        } else {
            if (rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_RGBA || rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_BGRA) {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes;
                    for (int i = 0; i < rgb.width; ++i) {
                        int pixel_P = row_P + i * 4;
                        byte a = rgb.pixels[pixel_P + 3];
                        // byte[] can't exceed 255
                        if (a == max) {
                            continue;
                        } else if (a == 0) {
                            rgb.pixels[pixel_P + 0] = 0;
                            rgb.pixels[pixel_P + 1] = 0;
                            rgb.pixels[pixel_P + 2] = 0;
                        } else {
                            rgb.pixels[pixel_P + 0] = (byte) Math.round(rgb.pixels[pixel_P + 0] * (float) a / maxF);
                            rgb.pixels[pixel_P + 1] = (byte) Math.round(rgb.pixels[pixel_P + 1] * (float) a / maxF);
                            rgb.pixels[pixel_P + 2] = (byte) Math.round(rgb.pixels[pixel_P + 2] * (float) a / maxF);
                        }
                    }
                }
            } else {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes;
                    for (int i = 0; i < rgb.width; ++i) {
                        int pixel_P = row_P + i * 4;
                        byte a = rgb.pixels[pixel_P + 0];
                        if (a == max) {
                            continue;
                        } else if (a == 0) {
                            rgb.pixels[pixel_P + 1] = 0;
                            rgb.pixels[pixel_P + 2] = 0;
                            rgb.pixels[pixel_P + 3] = 0;
                        } else {
                            rgb.pixels[pixel_P + 1] = (byte) Math.round(rgb.pixels[pixel_P + 1] * (float) a / maxF);
                            rgb.pixels[pixel_P + 2] = (byte) Math.round(rgb.pixels[pixel_P + 2] * (float) a / maxF);
                            rgb.pixels[pixel_P + 3] = (byte) Math.round(rgb.pixels[pixel_P + 3] * (float) a / maxF);
                        }
                    }
                }
            }
        }

        return avifResult.AVIF_RESULT_OK;
    }

    static avifResult avifRGBImageUnpremultiplyAlpha(avifRGBImage rgb) {
        // no data
        if (rgb.pixels == null || rgb.rowBytes == 0) {
            return avifResult.AVIF_RESULT_REFORMAT_FAILED;
        }

        // no alpha.
        if (!rgb.format.avifRGBFormatHasAlpha()) {
            return avifResult.AVIF_RESULT_REFORMAT_FAILED;
        }

        avifResult libyuvResult = reformat_libyuv.avifRGBImageUnpremultiplyAlphaLibYUV(rgb);
        if (libyuvResult != avifResult.AVIF_RESULT_NOT_IMPLEMENTED) {
            return libyuvResult;
        }

        assert (rgb.depth >= 8 && rgb.depth <= 16);

        int max = (1 << rgb.depth) - 1;
        float maxF = max;

        if (rgb.depth > 8) {
            if (rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_RGBA || rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_BGRA) {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes;
                    for (int i = 0; i < rgb.width; ++i) {
                        ShortBuffer pixel = ByteBuffer.wrap(rgb.pixels, row_P + i * 8, i * 8 / Short.BYTES).asShortBuffer();
                        short a = pixel.get(3);
                        if (a >= max) {
                            // opaque is no-op
                            continue;
                        } else if (a == 0) {
                            // prevent division by zero
                            pixel.put(0, (short) 0);
                            pixel.put(1, (short) 0);
                            pixel.put(2, (short) 0);
                        } else {
                            float c1 = Math.round(pixel.get(0) * maxF / a);
                            float c2 = Math.round(pixel.get(1) * maxF / a);
                            float c3 = Math.round(pixel.get(2) * maxF / a);
                            pixel.put(0, (short) Math.min(c1, maxF));
                            pixel.put(1, (short) Math.min(c2, maxF));
                            pixel.put(2, (short) Math.min(c3, maxF));
                        }
                    }
                }
            } else {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes;
                    for (int i = 0; i < rgb.width; ++i) {
                        ShortBuffer pixel = ByteBuffer.wrap(rgb.pixels, row_P + i * 8, i * 8 / Short.BYTES).asShortBuffer();
                        short a = pixel.get(0);
                        if (a >= max) {
                            continue;
                        } else if (a == 0) {
                            pixel.put(1, (short) 0);
                            pixel.put(2, (short) 0);
                            pixel.put(3, (short) 0);
                        } else {
                            float c1 = Math.round(pixel.get(1) * maxF / a);
                            float c2 = Math.round(pixel.get(2) * maxF / a);
                            float c3 = Math.round(pixel.get(3) * maxF / a);
                            pixel.put(1, (short) Math.min(c1, maxF));
                            pixel.put(2, (short) Math.min(c2, maxF));
                            pixel.put(3, (short) Math.min(c3, maxF));
                        }
                    }
                }
            }
        } else {
            if (rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_RGBA || rgb.format == avifRGBFormat.AVIF_RGB_FORMAT_BGRA) {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes;
                    for (int i = 0; i < rgb.width; ++i) {
                        int pixel_P = row_P + i * 4;
                        byte a = rgb.pixels[pixel_P + 3];
                        if (a == max) {
                            continue;
                        } else if (a == 0) {
                            rgb.pixels[pixel_P + 0] = 0;
                            rgb.pixels[pixel_P + 1] = 0;
                            rgb.pixels[pixel_P + 2] = 0;
                        } else {
                            float c1 = Math.round(rgb.pixels[pixel_P + 0] * maxF / a);
                            float c2 = Math.round(rgb.pixels[pixel_P + 1] * maxF / a);
                            float c3 = Math.round(rgb.pixels[pixel_P + 2] * maxF / a);
                            rgb.pixels[pixel_P + 0] = (byte) Math.min(c1, maxF);
                            rgb.pixels[pixel_P + 1] = (byte) Math.min(c2, maxF);
                            rgb.pixels[pixel_P + 2] = (byte) Math.min(c3, maxF);
                        }
                    }
                }
            } else {
                for (int j = 0; j < rgb.height; ++j) {
                    int row_P = j * rgb.rowBytes;
                    for (int i = 0; i < rgb.width; ++i) {
                        int pixel_P = row_P + i * 4;
                        byte a = rgb.pixels[pixel_P + 0];
                        if (a == max) {
                            continue;
                        } else if (a == 0) {
                            rgb.pixels[pixel_P + 1] = 0;
                            rgb.pixels[pixel_P + 2] = 0;
                            rgb.pixels[pixel_P + 3] = 0;
                        } else {
                            float c1 = Math.round(rgb.pixels[pixel_P + 1] * maxF / a);
                            float c2 = Math.round(rgb.pixels[pixel_P + 2] * maxF / a);
                            float c3 = Math.round(rgb.pixels[pixel_P + 3] * maxF / a);
                            rgb.pixels[pixel_P + 1] = (byte) Math.min(c1, maxF);
                            rgb.pixels[pixel_P + 2] = (byte) Math.min(c2, maxF);
                            rgb.pixels[pixel_P + 3] = (byte) Math.min(c3, maxF);
                        }
                    }
                }
            }
        }

        return avifResult.AVIF_RESULT_OK;
    }
}
