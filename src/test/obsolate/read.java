// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import vavi.awt.image.avif.avif.avifChannelIndex;
import vavi.awt.image.avif.avif.avifChromaSamplePosition;
import vavi.awt.image.avif.avif.avifCleanApertureBox;
import vavi.awt.image.avif.avif.avifCodec;
import vavi.awt.image.avif.avif.avifCodecChoice;
import vavi.awt.image.avif.avif.avifCodecConfigurationBox;
import vavi.awt.image.avif.avif.avifCodecFlag;
import vavi.awt.image.avif.avif.avifColorPrimaries;
import vavi.awt.image.avif.avif.avifCropRect;
import vavi.awt.image.avif.avif.avifDecoder;
import vavi.awt.image.avif.avif.avifDecoderSource;
import vavi.awt.image.avif.avif.avifExtent;
import vavi.awt.image.avif.avif.avifIO;
import vavi.awt.image.avif.avif.avifIOStats;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifImageMirror;
import vavi.awt.image.avif.avif.avifImageRotation;
import vavi.awt.image.avif.avif.avifImageTiming;
import vavi.awt.image.avif.avif.avifMatrixCoefficients;
import vavi.awt.image.avif.avif.avifPixelAspectRatioBox;
import vavi.awt.image.avif.avif.avifPixelFormat;
import vavi.awt.image.avif.avif.avifPixelFormat.avifPixelFormatInfo;
import vavi.awt.image.avif.avif.avifPlanesFlag;
import vavi.awt.image.avif.avif.avifProgressiveState;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;
import vavi.awt.image.avif.avif.avifStrictFlag;
import vavi.awt.image.avif.avif.avifTransferCharacteristics;
import vavi.awt.image.avif.avif.avifTransformFlag;
import vavi.awt.image.avif.obu.avifSequenceHeader;

class read {

    public static final int AUXTYPE_SIZE = 64;
public static final int CONTENTTYPE_SIZE = 64;

// class VisualSampleEntry(codingname) extends SampleEntry(codingname) {
//     int(16) pre_defined = 0;
//     final int(16) reserved = 0;
//     int(32)[3] pre_defined = 0;
//     int(16) width;
//     int(16) height;
//     template int(32) horizresolution = 0x00480000; // 72 dpi
//     template int(32) vertresolution = 0x00480000;  // 72 dpi
//     final int(32) reserved = 0;
//     template int(16) frame_count = 1;
//     string[32] compressorname;
//     template int(16) depth = 0x0018;
//     int(16) pre_defined = -1;
//     // other boxes from derived specifications
//     CleanApertureBox clap;    // optional
//     PixelAspectRatioBox pasp; // optional
// }
private final int VISUALSAMPLEENTRY_SIZE = 78;

public static final String URN_ALPHA0 = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha";
public static final String URN_ALPHA1 = "urn:mpeg:hevc:2015:auxid:1";

public static final String CONTENT_TYPE_XMP = "application/rdf+xml";

private final String xmpContentType = CONTENT_TYPE_XMP;

// The only supported ipma box values for both version and flags are [0,1], so there technically
// can't be more than 4 unique tuples right now.
public static final int MAX_IPMA_VERSION_AND_FLAGS_SEEN = 4;

public static final int MAX_AV1_LAYER_COUNT = 4;

// ---------------------------------------------------------------------------
// Box data structures

// ftyp

class avifFileType
{
    byte[] majorBrand = new byte[4];
    int minorVersion;
    // If not null, points to a memory block of 4 * compatibleBrandsCount bytes.
    byte[] compatibleBrands;
    int compatibleBrandsCount;
}

// ispe
class avifImageSpatialExtents
{
    int width;
    int height;
}

// auxC
class avifAuxiliaryType
{
    byte[] auxType = new byte[AUXTYPE_SIZE];
}

// infe mime content_type
class avifContentType
{
    byte[] contentType = new byte[CONTENTTYPE_SIZE];
}

// colr
class avifColourInformationBox
{
    boolean hasICC;
    byte[] icc;
    int iccSize;

    boolean hasNCLX;
    avifColorPrimaries colorPrimaries;
    avifTransferCharacteristics transferCharacteristics;
    avifMatrixCoefficients matrixCoefficients;
    avifRange range;
}

public static final int MAX_PIXI_PLANE_DEPTHS = 4;
class avifPixelInformationProperty
{
    byte[] planeDepths = new byte[MAX_PIXI_PLANE_DEPTHS];
    byte planeCount;
}

class avifOperatingPointSelectorProperty
{
    byte[] opIndex;
}

class avifLayerSelectorProperty
{
    short layerID;
}

class avifAV1LayeredImageIndexingProperty
{
    int[] layerSize = new int[3];
}

// Temporary storage for ipco/stsd contents until they can be associated and memcpy'd to an avifDecoderItem
class avifProperty
{
    String type;
    class union
    {
        avifImageSpatialExtents ispe;
        avifAuxiliaryType auxC;
        avifColourInformationBox colr;
        avifCodecConfigurationBox av1C;
        avifPixelAspectRatioBox pasp;
        avifCleanApertureBox clap;
        avifImageRotation irot;
        avifImageMirror imir;
        avifPixelInformationProperty pixi;
        avifOperatingPointSelectorProperty a1op;
        avifLayerSelectorProperty lsel;
        avifAV1LayeredImageIndexingProperty a1lx;
    }
    union u;
}
//AVIF_ARRAY_DECLARE(List<avifProperty>, avifProperty, prop);

private final avifProperty avifPropertyArrayFind(final List<avifProperty> properties, final String type)
{
    for (int propertyIndex = 0; propertyIndex < properties.size(); ++propertyIndex) {
        avifProperty prop = properties.get(propertyIndex);
        if (!prop.type.equals(type)) {
            return prop;
        }
    }
    return null;
}

//AVIF_ARRAY_DECLARE(avifExtentArray, avifExtent, extent);

// one "item" worth for decoding (all iref, iloc, iprp, etc refer to one of these)
class avifDecoderItem
{
    int id;
    avifMeta meta; // Unowned; A back-pointer for convenience
    byte[] type = new byte[4];
    int size;
    boolean idatStored; // If true, offset is relative to the associated meta box's idat box (iloc construction_method==1)
    int width;      // Set from this item's ispe property, if present
    int height;     // Set from this item's ispe property, if present
    avifContentType contentType;
    List<avifProperty> properties;
    List<avifExtent> extents;       // All extent offsets/sizes
    byte[] /* avifRWData */ mergedExtents;      // if set, is a single contiguous block of this item's extents (unused when extents.size() == 1)
    boolean ownsMergedExtents;    // if true, mergedExtents must be freed when this item is destroyed
    boolean partialMergedExtents; // If true, mergedExtents doesn't have all of the item data yet
    int thumbnailForID;       // if non-zero, this item is a thumbnail for Item #{thumbnailForID}
    int auxForID;             // if non-zero, this item is an auxC plane for Item #{auxForID}
    int descForID;            // if non-zero, this item is a content description for Item #{descForID}
    int dimgForID;            // if non-zero, this item is a derived image for Item #{dimgForID}
    int premByID;             // if non-zero, this item is premultiplied by Item #{premByID}
    boolean hasUnsupportedEssentialProperty; // If true, this item cites a property flagged as 'essential' that libavif doesn't support (yet). Ignore the item, if so.
    boolean ipmaSeen;    // if true, this item already received a property association
    boolean progressive; // if true, this item has progressive layers (a1lx), but does not select a specific layer (lsel)
}
//AVIF_ARRAY_DECLARE(avifDecoderItemArray, avifDecoderItem, item);

// grid storage
class avifImageGrid
{
    int rows;    // Legal range: [1-256]
    int columns; // Legal range: [1-256]
    int outputWidth;
    int outputHeight;
}

// ---------------------------------------------------------------------------
// avifTrack

class avifSampleTableChunk
{
    long offset;
}
//AVIF_ARRAY_DECLARE(avifSampleTableChunkArray, avifSampleTableChunk, chunk);

class avifSampleTableSampleToChunk
{
    int firstChunk;
    int samplesPerChunk;
    int sampleDescriptionIndex;
}
//AVIF_ARRAY_DECLARE(avifSampleTableSampleToChunkArray, avifSampleTableSampleToChunk, sampleToChunk);

class avifSampleTableSampleSize
{
    int size;
}
//AVIF_ARRAY_DECLARE(avifSampleTableSampleSizeArray, avifSampleTableSampleSize, sampleSize);

class avifSampleTableTimeToSample
{
    int sampleCount;
    int sampleDelta;
}
//AVIF_ARRAY_DECLARE(avifSampleTableTimeToSampleArray, avifSampleTableTimeToSample, timeToSample);

class avifSyncSample
{
    int sampleNumber;
}
//AVIF_ARRAY_DECLARE(avifSyncSampleArray, avifSyncSample, syncSample);

class avifSampleDescription
{
    String format;
    List<avifProperty> properties;
}
//AVIF_ARRAY_DECLARE(avifSampleDescriptionArray, avifSampleDescription, description);

class avifSampleTable
{
    List<avifSampleTableChunk> chunks;
    List<avifSampleDescription> sampleDescriptions;
    List<avifSampleTableSampleToChunk> sampleToChunks;
    List<avifSampleTableSampleSize> sampleSizes;
    List<avifSampleTableTimeToSample> timeToSamples;
    List<avifSyncSample> syncSamples;
    int allSamplesSize; // If this is non-zero, sampleSizes will be empty and all samples will be this size
}

//private void avifSampleTableDestroy(avifSampleTable  sampleTable);

private avifSampleTable avifSampleTableCreate()
{
    avifSampleTable sampleTable = new avifSampleTable();
    sampleTable.chunks = new ArrayList<>(16);
    sampleTable.sampleDescriptions = new ArrayList<>(2);
    sampleTable.sampleToChunks = new ArrayList<>(16);
    sampleTable.sampleSizes = new ArrayList<>(16);
    sampleTable.timeToSamples = new ArrayList<>(16);
    sampleTable.syncSamples = new ArrayList<>(16);
    return sampleTable;
}

private int avifSampleTableGetImageDelta(final avifSampleTable sampleTable, int imageIndex)
{
    int maxSampleIndex = 0;
    for (int i = 0; i < sampleTable.timeToSamples.size(); ++i) {
        final avifSampleTableTimeToSample timeToSample = sampleTable.timeToSamples.get(i);
        maxSampleIndex += timeToSample.sampleCount;
        if ((imageIndex < maxSampleIndex) || (i == (sampleTable.timeToSamples.size() - 1))) {
            return timeToSample.sampleDelta;
        }
    }

    // TODO: fail here?
    return 1;
}

private boolean avifSampleTableHasFormat(final avifSampleTable sampleTable, final String format)
{
    for (int i = 0; i < sampleTable.sampleDescriptions.size(); ++i) {
        if (sampleTable.sampleDescriptions.get(i).format.equals(format)) {
            return true;
        }
    }
    return false;
}

private int avifCodecConfigurationBoxGetDepth(final avifCodecConfigurationBox av1C)
{
    if (av1C.twelveBit != 0) {
        return 12;
    } else if (av1C.highBitdepth != 0) {
        return 10;
    }
    return 8;
}

// This is used as a hint to validating the clap box in avifDecoderItemValidateAV1.
private avifPixelFormat avifCodecConfigurationBoxGetFormat(final avifCodecConfigurationBox av1C)
{
    if (av1C.monochrome != 0) {
        return avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400;
    } else if (av1C.chromaSubsamplingY == 1) {
        return avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420;
    } else if (av1C.chromaSubsamplingX == 1) {
        return avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422;
    }
    return avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
}

private final List<avifProperty> avifSampleTableGetProperties(final avifSampleTable sampleTable)
{
    for (int i = 0; i < sampleTable.sampleDescriptions.size(); ++i) {
        final avifSampleDescription  description = sampleTable.sampleDescriptions.get(i);
        if (description.format.equals("av01")) {
            return description.properties;
        }
    }
    return null;
}

// one video track ("trak" contents)
class avifTrack
{
    int id;
    int auxForID; // if non-zero, this track is an auxC plane for Track #{auxForID}
    int premByID; // if non-zero, this track is premultiplied by Track #{premByID}
    int mediaTimescale;
    long mediaDuration;
    int width;
    int height;
    avifSampleTable  sampleTable;
    avifMeta meta;
}

static final int AVIF_SPATIAL_ID_UNSET = 0xff;

class avifDecodeSample
{
    byte[] data;
    boolean ownsData;
    boolean partialData; // if true, data exists but doesn't have all of the sample in it

    int itemID;   // if non-zero, data comes from a mergedExtents buffer in an avifDecoderItem, not a file offset
    long offset;   // additional offset into data. Can be used to offset into an itemID's payload as well.
    int size;       //
    byte spatialID; // If set to a value other than AVIF_SPATIAL_ID_UNSET, output frames from this sample should be
                       // skipped until the output frame's spatial_id matches this ID.
    boolean sync;     // is sync sample (keyframe)
}

class avifCodecDecodeInput
{
    List<avifDecodeSample> samples;
    boolean allLayers; // if true, the underlying codec must decode all layers, not just the best layer
    boolean alpha;     // if true, this is decoding an alpha plane
}

avifCodecDecodeInput avifCodecDecodeInputCreate()
{
    avifCodecDecodeInput decodeInput = new avifCodecDecodeInput();
    decodeInput.samples = new ArrayList<>(1);
    return decodeInput;
}

class avifBoxHeader
{
    int size;
    byte[] type;
} 

class avifROStream
{
    byte[]  raw;
    int offset;
} 

class avifRWStream
{
    byte[] raw;
    int offset;
}

// Returns how many samples are in the chunk.
private int avifGetSampleCountOfChunk(final List<avifSampleTableSampleToChunk> sampleToChunks, int chunkIndex)
{
    int sampleCount = 0;
    for (int sampleToChunkIndex = sampleToChunks.size() - 1; sampleToChunkIndex >= 0; --sampleToChunkIndex) {
        final avifSampleTableSampleToChunk  sampleToChunk = sampleToChunks.get(sampleToChunkIndex);
        if (sampleToChunk.firstChunk <= (chunkIndex + 1)) {
            sampleCount = sampleToChunk.samplesPerChunk;
            break;
        }
    }
    return sampleCount;
}

private boolean avifCodecDecodeInputFillFromSampleTable(avifCodecDecodeInput decodeInput,
                                                        avifSampleTable sampleTable,
                                                        final int imageCountLimit,
                                                        final long sizeHint)
{
    if (imageCountLimit != 0) {
        // Verify that the we're not about to exceed the frame count limit.

        int imageCountLeft = imageCountLimit;
        for (int chunkIndex = 0; chunkIndex < sampleTable.chunks.size(); ++chunkIndex) {
            // First, figure out how many samples are in this chunk
            int sampleCount = avifGetSampleCountOfChunk(sampleTable.sampleToChunks, chunkIndex);
            if (sampleCount == 0) {
                // chunks with 0 samples are invalid
                System.err.printf("Sample table contains a chunk with 0 samples");
                return false;
            }

            if (sampleCount > imageCountLeft) {
                // This file exceeds the imageCountLimit, bail out
                System.err.printf("Exceeded avifDecoder's imageCountLimit");
                return false;
            }
            imageCountLeft -= sampleCount;
        }
    }

    int sampleSizeIndex = 0;
    for (int chunkIndex = 0; chunkIndex < sampleTable.chunks.size(); ++chunkIndex) {
        avifSampleTableChunk  chunk = sampleTable.chunks.get(chunkIndex);

        // First, figure out how many samples are in this chunk
        int sampleCount = avifGetSampleCountOfChunk(sampleTable.sampleToChunks, chunkIndex);
        if (sampleCount == 0) {
            // chunks with 0 samples are invalid
            System.err.printf("Sample table contains a chunk with 0 samples");
            return false;
        }

        long sampleOffset = chunk.offset;
        for (int sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex) {
            int sampleSize = sampleTable.allSamplesSize;
            if (sampleSize == 0) {
                if (sampleSizeIndex >= sampleTable.sampleSizes.size()) {
                    // We've run out of samples to sum
                    System.err.printf("Truncated sample table");
                    return false;
                }
                avifSampleTableSampleSize  sampleSizePtr = sampleTable.sampleSizes.get(sampleSizeIndex);
                sampleSize = sampleSizePtr.size;
            }

            avifDecodeSample  sample = new avifDecodeSample();
            sample.offset = sampleOffset;
            sample.size = sampleSize;
            sample.spatialID = (byte) AVIF_SPATIAL_ID_UNSET; // Not filtering by spatial_id
            sample.sync = false;                 // to potentially be set to true following the outer loop
            decodeInput.samples.add(sample);

            if (sampleSize > Long.MAX_VALUE - sampleOffset) {
                System.err.printf(
                                      "Sample table contains an offset/size pair which overflows: [%d / %u]",
                                      sampleOffset,
                                      sampleSize);
                return false;
            }
            if (sizeHint != 0 && ((sampleOffset + sampleSize) > sizeHint)) {
                System.err.printf("Exceeded avifIO's sizeHint, possibly truncated data");
                return false;
            }

            sampleOffset += sampleSize;
            ++sampleSizeIndex;
        }
    }

    // Mark appropriate samples as sync
    for (int syncSampleIndex = 0; syncSampleIndex < sampleTable.syncSamples.size(); ++syncSampleIndex) {
        int frameIndex = sampleTable.syncSamples.get(syncSampleIndex).sampleNumber - 1; // sampleNumber is 1-based
        if (frameIndex < decodeInput.samples.size()) {
            decodeInput.samples.get(frameIndex).sync = true;
        }
    }

    // Assume frame 0 is sync, just in case the stss box is absent in the BMFF. (Unnecessary?)
    if (decodeInput.samples.size() > 0) {
        decodeInput.samples.get(0).sync = true;
    }
    return true;
}

private boolean avifCodecDecodeInputFillFromDecoderItem(avifCodecDecodeInput decodeInput,
                                                        avifDecoderItem  item,
                                                        boolean allowProgressive,
                                                        final int imageCountLimit,
                                                        final long sizeHint)
{
    if (sizeHint != 0 && (item.size > sizeHint)) {
        System.err.printf("Exceeded avifIO's sizeHint, possibly truncated data");
        return false;
    }

    byte layerCount = 0;
    int[] layerSizes = new int[] { 0, 0, 0, 0 };
    final avifProperty  a1lxProp = avifPropertyArrayFind(item.properties, "a1lx");
    if (a1lxProp != null) {
        // Calculate layer count and all layer sizes from the a1lx box, and then validate

        int remainingSize = item.size;
        for (int i = 0; i < 3; ++i) {
            ++layerCount;

            final int layerSize = a1lxProp.u.a1lx.layerSize[i];
            if (layerSize != 0) {
                if (layerSize >= remainingSize) { // >= instead of > because there must be room for the last layer
                    System.err.printf("a1lx layer index [%d] does not fit in item size", i);
                    return false;
                }
                layerSizes[i] = layerSize;
                remainingSize -= layerSize;
            } else {
                layerSizes[i] = remainingSize;
                remainingSize = 0;
                break;
            }
        }
        if (remainingSize > 0) {
            assert layerCount == 3;
            ++layerCount;
            layerSizes[3] = remainingSize;
        }
    }

    final avifProperty  lselProp = avifPropertyArrayFind(item.properties, "lsel");
    item.progressive = (a1lxProp != null && lselProp == null); // Progressive images offer layers via the a1lxProp, but don't specify a layer selection with lsel.
    if (lselProp != null) {
        // Layer selection. This requires that the underlying AV1 codec decodes all layers,
        // and then only returns the requested layer as a single frame. To the user of libavif,
        // this appears to be a single frame.

        decodeInput.allLayers = true;

        int sampleSize = 0;
        if (layerCount > 0) {
            // Optimization: If we're selecting a layer that doesn't require the entire image's payload (hinted via the a1lx box)

            if (lselProp.u.lsel.layerID >= layerCount) {
                System.err.printf(
                                      "lsel property requests layer index [%u] which isn't present in a1lx property ([%u] layers)",
                                      lselProp.u.lsel.layerID,
                                      layerCount);
                return false;
            }

            for (byte i = 0; i <= lselProp.u.lsel.layerID; ++i) {
                sampleSize += layerSizes[i];
            }
        } else {
            // This layer's payload subsection is unknown, just use the whole payload
            sampleSize = item.size;
        }

        avifDecodeSample  sample = new avifDecodeSample ();
        sample.itemID = item.id;
        sample.offset = 0;
        sample.size = sampleSize;
        assert(lselProp.u.lsel.layerID < MAX_AV1_LAYER_COUNT);
        sample.spatialID = (byte)lselProp.u.lsel.layerID;
        sample.sync = true;
        decodeInput.samples.add(sample);
    } else if (allowProgressive && item.progressive) {
        // Progressive image. Decode all layers and expose them all to the user.

        if (imageCountLimit != 0 && (layerCount > imageCountLimit)) {
            System.err.printf("Exceeded avifDecoder's imageCountLimit (progressive)");
            return false;
        }

        decodeInput.allLayers = true;

        int offset = 0;
        for (int i = 0; i < layerCount; ++i) {
            avifDecodeSample  sample = new avifDecodeSample ();
            sample.itemID = item.id;
            sample.offset = offset;
            sample.size = layerSizes[i];
            sample.spatialID = (byte) AVIF_SPATIAL_ID_UNSET;
            sample.sync = (i == 0); // Assume all layers depend on the first layer
            decodeInput.samples.add(sample);

            offset += layerSizes[i];
        }
    } else {
        // Typical case: Use the entire item's payload for a single frame output

        avifDecodeSample  sample = new avifDecodeSample ();
        sample.itemID = item.id;
        sample.offset = 0;
        sample.size = item.size;
        sample.spatialID = (byte) AVIF_SPATIAL_ID_UNSET;
        sample.sync = true;
        decodeInput.samples.add(sample);
    }
    return true;
}

// Use this to keep track of whether or not a child box that must be unique (0 or 1 present) has
// been seen yet, when parsing a parent box. If the "seen" bit is already set for a given box when
// it is encountered during parse, an error is thrown. Which bit corresponds to which box is
// dictated entirely by the calling function.
private boolean uniqueBoxSeen(int[] uniqueBoxFlags, int whichFlag, final String parentBoxType, final String boxType)
{
    final int flag = 1 << whichFlag;
    if ((uniqueBoxFlags[0] & flag) != 0) {
        // This box has already been seen. Error!
        System.err.printf("Box[%s] contains a duplicate unique box of type '%s'", parentBoxType, boxType);
        return false;
    }

    // Mark this box as seen.
    uniqueBoxFlags[0] |= flag;
    return true;
}

// ---------------------------------------------------------------------------
// avifDecoderData

class avifTile
{
    avifCodecDecodeInput input;
    avifCodec codec;
    avifImage  image;
    int width;  // Either avifTrack.width or avifDecoderItem.width
    int height; // Either avifTrack.height or avifDecoderItem.height
    byte[] operatingPoint;
}
//AVIF_ARRAY_DECLARE(List<avifTile>, avifTile, tile);

// This holds one "meta" box (from the BMFF and HEIF standards) worth of relevant-to-AVIF information.
// * If a meta box is parsed from the root level of the BMFF, it can contain the information about
//   "items" which might be color planes, alpha planes, or EXIF or XMP metadata.
// * If a meta box is parsed from inside of a track ("trak") box, any metadata (EXIF/XMP) items inside
//   of that box are implicitly associated with that track.
class avifMeta
{
    // Items (from HEIF) are the generic storage for any data that does not require timed processing
    // (single image color planes, alpha planes, EXIF, XMP, etc). Each item has a unique integer ID >1,
    // and is defined by a series of child boxes in a meta box:
    //  * iloc - location:     byte offset to item data, item size in bytes
    //  * iinf - information:  type of item (color planes, alpha plane, EXIF, XMP)
    //  * ipco - properties:   dimensions, aspect ratio, image transformations, references to other items
    //  * ipma - associations: Attaches an item in the properties list to a given item
    //
    // Items are lazily created in this array when any of the above boxes refer to one by a new (unseen) ID,
    // and are then further modified/updated as new information for an item's ID is parsed.
    List<avifDecoderItem> items;

    // Any ipco boxes explained above are populated into this array as a staging area, which are
    // then duplicated into the appropriate items upon encountering an item property association
    // (ipma) box.
    List<avifProperty> properties;

    // Filled with the contents of this meta box's "idat" box, which is raw data that an item can
    // directly refer to in its item location box (iloc) instead of just giving an offset into the
    // overall file. If all items' iloc boxes simply point at an offset/length in the file itself,
    // this buffer will likely be empty.
    byte[] /* avifRWData */ idat;

    // Ever-incrementing ID for uniquely identifying which 'meta' box contains an idat (when
    // multiple meta boxes exist as BMFF siblings). Each time avifParseMetaBox() is called on an
    // avifMeta struct, this value is incremented. Any time an additional meta box is detected at
    // the same "level" (root level, trak level, etc), this ID helps distinguish which meta box's
    // "idat" is which, as items implicitly reference idat boxes that exist in the same meta
    // box.
    int idatID;

    // Contents of a pitm box, which signal which of the items in this file is the main image. For
    // AVIF, this should point at an av01 type item containing color planes, and all other items
    // are ignored unless they refer to this item in some way (alpha plane, EXIF/XMP metadata).
    int primaryItemID;
}

//private void avifMetaDestroy(avifMeta  meta);

private avifMeta  avifMetaCreate()
{
    avifMeta  meta = new avifMeta();
    meta.items = new ArrayList<>(8);
    meta.properties = new ArrayList<>(16);
    return meta;
}

private avifDecoderItem  avifMetaFindItem(avifMeta  meta, int itemID)
{
    if (itemID == 0) {
        return null;
    }

    for (int i = 0; i < meta.items.size(); ++i) {
        if (meta.items.get(i).id == itemID) {
            return meta.items.get(i);
        }
    }

    avifDecoderItem item = new avifDecoderItem();
    item.properties = new ArrayList<>(16);
    item.extents = new ArrayList<>(1);
    item.id = itemID;
    item.meta = meta;
    meta.items.add(item);
    return item;
}

class avifDecoderData
{
    avifMeta meta; // The root-level meta box
    List<avifTrack> tracks;
    List<avifTile> tiles;
    int colorTileCount;
    int alphaTileCount;
    int decodedColorTileCount;
    int decodedAlphaTileCount;
    avifImageGrid colorGrid;
    avifImageGrid alphaGrid;
    avifDecoderSource source;
    byte[] majorBrand = new byte[4];                     // From the file's ftyp, used by AVIF_DECODER_SOURCE_AUTO
    avifSampleTable  sourceSampleTable; // null unless (source == AVIF_DECODER_SOURCE_TRACKS), owned by an avifTrack
    boolean cicpSet;                          // True if avifDecoder's image has had its CICP set correctly yet.
                                               // This allows nclx colr boxes to override AV1 CICP, as specified in the MIAF
                                               // standard (ISO/IEC 23000-22:2019), section 7.3.6.4:
                                               //
    // "The colour information property takes precedence over any colour information in the image
    // bitstream, i.e. if the property is present, colour information in the bitstream shall be ignored."
}

private avifDecoderData avifDecoderDataCreate()
{
    avifDecoderData data = new avifDecoderData();
    data.meta = avifMetaCreate();
    data.tracks = new ArrayList<>(2);
    data.tiles = new ArrayList<>(8);
    return data;
}

private void avifDecoderDataResetCodec(avifDecoderData data)
{
    for (int i = 0; i < data.tiles.size(); ++i) {
        avifTile  tile = data.tiles.get(i);
        if (tile.codec != null) {
            tile.codec = null;
        }
    }
    data.decodedColorTileCount = 0;
    data.decodedAlphaTileCount = 0;
}

private avifTile  avifDecoderDataCreateTile(avifDecoderData data, int width, int height, byte[] operatingPoint)
{
    avifTile  tile = new avifTile();
    tile.image = new avifImage();
    if (tile.image == null) {
        return null;
    }
    tile.input = avifCodecDecodeInputCreate();
    if (tile.input == null) {
        return null;
    }
    tile.width = width;
    tile.height = height;
    tile.operatingPoint = operatingPoint;
    data.tiles.add(tile);
    return tile;
}

private avifTrack  avifDecoderDataCreateTrack(avifDecoderData data)
{
    avifTrack  track = new avifTrack();
    track.meta = avifMetaCreate();
    data.tracks.add(track);
    return track;
}

private void avifDecoderDataClearTiles(avifDecoderData data)
{
    for (int i = 0; i < data.tiles.size(); ++i) {
        avifTile  tile = data.tiles.get(i);
        if (tile.input != null) {
            tile.input = null;
        }
        if (tile.codec != null) {
            tile.codec = null;
        }
        if (tile.image != null) {
            tile.image = null;
        }
    }
    data.tiles.clear();
    data.colorTileCount = 0;
    data.alphaTileCount = 0;
    data.decodedColorTileCount = 0;
    data.decodedAlphaTileCount = 0;
}

// This returns the max extent that has to be read in order to decode this item. If
// the item is stored in an idat, the data has already been read during Parse() and
// this function will return avifResult.AVIF_RESULT_OK with a 0-byte extent.
private avifResult avifDecoderItemMaxExtent(final avifDecoderItem  item, final avifDecodeSample  sample, avifExtent  outExtent)
{
    if (item.extents.size() == 0) {
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }

    if (item.idatStored) {
        // construction_method: idat(1)

        if (item.meta.idat.length > 0) {
            // Already read from a meta box during Parse()
            outExtent = new avifExtent(); // TODO
            return avifResult.AVIF_RESULT_OK;
        }

        // no associated idat box was found in the meta box, bail out
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    // construction_method: file(0)

    if (sample.size == 0) {
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }
    long remainingOffset = sample.offset;
    int remainingBytes = sample.size; // This may be smaller than item.size if the item is progressive

    // Assert that the for loop below will execute at least one iteration.
    assert(item.extents.size() != 0);
    long minOffset = Long.MAX_VALUE;
    long maxOffset = 0;
    for (int extentIter = 0; extentIter < item.extents.size(); ++extentIter) {
        avifExtent  extent = item.extents.get(extentIter);

        // Make local copies of extent.offset and extent.size as they might need to be adjusted
        // due to the sample's offset.
        long startOffset = extent.offset;
        int extentSize = extent.size;
        if (remainingOffset != 0) {
            if (remainingOffset >= extentSize) {
                remainingOffset -= extentSize;
                continue;
            } else {
                if (remainingOffset > Long.MAX_VALUE - startOffset) {
                    return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
                }
                startOffset += remainingOffset;
                extentSize -= remainingOffset;
                remainingOffset = 0;
            }
        }

        final int usedExtentSize = (extentSize < remainingBytes) ? extentSize : remainingBytes;

        if (usedExtentSize > Long.MAX_VALUE - startOffset) {
            return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
        }
        final long endOffset = startOffset + usedExtentSize;

        if (minOffset > startOffset) {
            minOffset = startOffset;
        }
        if (maxOffset < endOffset) {
            maxOffset = endOffset;
        }

        remainingBytes -= usedExtentSize;
        if (remainingBytes == 0) {
            // We've got enough bytes for this sample.
            break;
        }
    }

    if (remainingBytes != 0) {
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }

    outExtent.offset = minOffset;
    final long extentLength = maxOffset - minOffset;
    if (extentLength > Integer.MAX_VALUE) {
        return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
    }
    outExtent.size = (int)extentLength;
    return avifResult.AVIF_RESULT_OK;
}

private byte[] avifDecoderItemOperatingPoint(final avifDecoderItem  item)
{
    final avifProperty  a1opProp = avifPropertyArrayFind(item.properties, "a1op");
    if (a1opProp != null) {
        return a1opProp.u.a1op.opIndex;
    }
    return null; // default
}

private avifResult avifDecoderItemValidateAV1(final avifDecoderItem  item, final EnumSet<avifStrictFlag> strictFlags)
{
    final avifProperty  av1CProp = avifPropertyArrayFind(item.properties, "av1C");
    if (av1CProp == null) {
        // An av1C box is mandatory in all valid AVIF configurations. Bail out.
        System.err.printf("Item ID %u of type '%.4s' is missing mandatory av1C property", item.id, item.type);
        return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
    }

    final avifProperty  pixiProp = avifPropertyArrayFind(item.properties, "pixi");
    if (pixiProp == null && strictFlags.contains(avifStrictFlag.AVIF_STRICT_PIXI_REQUIRED)) {
        // A pixi box is mandatory in all valid AVIF configurations. Bail out.
        System.err.printf(
                              "[Strict] Item ID %u of type '%.4s' is missing mandatory pixi property",
                              item.id,
                              new String(item.type));
        return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
    }

    if (pixiProp != null) {
        final int av1CDepth = avifCodecConfigurationBoxGetDepth(av1CProp.u.av1C);
        for (byte i = 0; i < pixiProp.u.pixi.planeCount; ++i) {
            if (pixiProp.u.pixi.planeDepths[i] != av1CDepth) {
                // pixi depth must match av1C depth
                System.err.printf("Item ID %u depth specified by pixi property [%u] does not match av1C property depth [%u]",
                                      item.id,
                                      pixiProp.u.pixi.planeDepths[i],
                                      av1CDepth);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
        }
    }

    if (strictFlags.contains(avifStrictFlag.AVIF_STRICT_CLAP_VALID)) {
        final avifProperty  clapProp = avifPropertyArrayFind(item.properties, "clap");
        if (clapProp != null) {
            final avifProperty  ispeProp = avifPropertyArrayFind(item.properties, "ispe");
            if (ispeProp == null) {
                System.err.printf(
                                      "[Strict] Item ID %u is missing an ispe property, so its clap property cannot be validated",
                                      item.id);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }

            avifCropRect cropRect;
            final int imageW = ispeProp.u.ispe.width;
            final int imageH = ispeProp.u.ispe.height;
            final avifPixelFormat av1CFormat = avifCodecConfigurationBoxGetFormat(av1CProp.u.av1C);
            boolean validClap = avif.avifCropRectConvertCleanApertureBox(cropRect, clapProp.u.clap, imageW, imageH, av1CFormat);
            if (!validClap) {
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifDecoderItemRead(avifDecoderItem  item,
                                      avifIO  io,
                                      final byte[] /* avifROData */  outData,
                                      int offset,
                                      int partialByteCount)
{
    if (item.mergedExtents != null && !item.partialMergedExtents) {
        // Multiple extents have already been concatenated for this item, just return it
        if (offset >= item.mergedExtents.length) {
            System.err.printf("Item ID %u read has overflowing offset", item.id);
            return avifResult.AVIF_RESULT_TRUNCATED_DATA;
        }
        outData = item.mergedExtents + offset;
        outData.length = item.mergedExtents.length - offset;
        return avifResult.AVIF_RESULT_OK;
    }

    if (item.extents.size() == 0) {
        System.err.printf("Item ID %u has zero extents", item.id);
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }

    // Find this item's source of all extents' data, based on the construction method
    final byte[] /* avifRWData */  idatBuffer = null;
    if (item.idatStored) {
        // construction_method: idat(1)

        if (item.meta.idat.length > 0) {
            idatBuffer = item.meta.idat;
        } else {
            // no associated idat box was found in the meta box, bail out
            System.err.printf("Item ID %u is stored in an idat, but no associated idat box was found", item.id);
            return avifResult.AVIF_RESULT_NO_CONTENT;
        }
    }

    // Merge extents into a single contiguous buffer
    if ((io.sizeHint > 0) && (item.size > io.sizeHint)) {
        // Sanity check: somehow the sum of extents exceeds the entire file or idat size!
        System.err.printf("Item ID %u reported size failed size hint sanity check. Truncated data?", item.id);
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }

    if (offset >= item.size) {
        System.err.printf("Item ID %u read has overflowing offset", item.id);
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }
    final int maxOutputSize = item.size - offset;
    final int readOutputSize = (partialByteCount != 0 && (partialByteCount < maxOutputSize)) ? partialByteCount : maxOutputSize;
    final int totalBytesToRead = offset + readOutputSize;

    // If there is a single extent for this item and the source of the read buffer is going to be
    // persistent for the lifetime of the avifDecoder (whether it comes from its own internal
    // idatBuffer or from a known-persistent IO), we can avoid buffer duplication and just use the
    // preexisting buffer.
    boolean singlePersistentBuffer = ((item.extents.size() == 1) && (idatBuffer != null || io.persistent));
    if (!singlePersistentBuffer) {
        // Always allocate the item's full size here, as progressive image decodes will do partial
        // reads into this buffer and begin feeding the buffer to the underlying AV1 decoder, but
        // will then write more into this buffer without flushing the AV1 decoder (which is still
        // holding the address of the previous allocation of this buffer). This strategy avoids
        // use-after-free issues in the AV1 decoder and unnecessary reallocs as a typical
        // progressive decode use case will eventually decode the final layer anyway.
        item.mergedExtents = new byte[item.size];
        item.ownsMergedExtents = true;
    }

    // Set this until we manage to fill the entire mergedExtents buffer
    item.partialMergedExtents = true;

    byte[] front = item.mergedExtents;
    int remainingBytes = totalBytesToRead;
    for (int extentIter = 0; extentIter < item.extents.size(); ++extentIter) {
        avifExtent  extent = item.extents.get(extentIter);

        int bytesToRead = extent.size;
        if (bytesToRead > remainingBytes) {
            bytesToRead = remainingBytes;
        }

        final byte[] /* avifROData */ offsetBuffer;
        if (idatBuffer != null) {
            if (extent.offset > idatBuffer.length) {
                System.err.printf("Item ID %u has impossible extent offset in idat buffer", item.id);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            // Since extent.offset (a long) is not bigger than idatBuffer.size (a int),
            // it is safe to cast extent.offset to int.
            final int extentOffset = (int)extent.offset;
            if (extent.size > idatBuffer.length - extentOffset) {
                System.err.printf("Item ID %u has impossible extent size in idat buffer", item.id);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            offsetBuffer = idatBuffer + extentOffset;
            offsetBuffer.length = idatBuffer.length - extentOffset;
        } else {
            // construction_method: file(0)

            if ((io.sizeHint > 0) && (extent.offset > io.sizeHint)) {
                System.err.printf("Item ID %u extent offset failed size hint sanity check. Truncated data?", item.id);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            avifResult readResult = io.read(0, extent.offset, bytesToRead, offsetBuffer);
            if (readResult != avifResult.AVIF_RESULT_OK) {
                return readResult;
            }
            if (bytesToRead != offsetBuffer.length) {
                System.err.printf(
                                      "Item ID %u tried to read %zu bytes, but only received %zu bytes",
                                      item.id,
                                      bytesToRead,
                                      offsetBuffer.length);
                return avifResult.AVIF_RESULT_TRUNCATED_DATA;
            }
        }

        if (singlePersistentBuffer) {
            System.arraycopy(offsetBuffer, 0, item.mergedExtents, 0, offsetBuffer.length);
            item.mergedExtents.length = bytesToRead;
        } else {
            assert(item.ownsMergedExtents);
            assert front != null;
            System.arraycopy(offsetBuffer, 0, front, 0, bytesToRead);
            front += bytesToRead;
        }

        remainingBytes -= bytesToRead;
        if (remainingBytes == 0) {
            // This happens when partialByteCount is set
            break;
        }
    }
    if (remainingBytes != 0) {
        // This should be impossible?
        System.err.printf("Item ID %u has %zu unexpected trailing bytes", item.id, remainingBytes);
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }

    outData = item.mergedExtents + offset;
    outData.length = readOutputSize;
    item.partialMergedExtents = (item.size != totalBytesToRead);
    return avifResult.AVIF_RESULT_OK;
}

private boolean avifDecoderGenerateImageGridTiles(avifDecoder  decoder, avifImageGrid  grid, avifDecoderItem  gridItem, boolean alpha)
{
    int tilesRequested = grid.rows * grid.columns;

    // Count number of dimg for this item, bail out if it doesn't match perfectly
    int tilesAvailable = 0;
    for (int i = 0; i < gridItem.meta.items.size(); ++i) {
        avifDecoderItem  item = gridItem.meta.items.get(i);
        if (item.dimgForID == gridItem.id) {
            if (!new String(item.type).equals("av01")) {
                continue;
            }
            if (item.hasUnsupportedEssentialProperty) {
                // An essential property isn't supported by libavif; can't
                // decode a grid image if any tile in the grid isn't supported.
                System.err.printf("Grid image contains tile with an unsupported property marked as essential");
                return false;
            }

            ++tilesAvailable;
        }
    }

    if (tilesRequested != tilesAvailable) {
        System.err.printf("Grid image of dimensions %ux%u requires %u tiles, and only %u were found",
                              grid.columns,
                              grid.rows,
                              tilesRequested,
                              tilesAvailable);
        return false;
    }

    boolean firstTile = true;
    for (int i = 0; i < gridItem.meta.items.size(); ++i) {
        avifDecoderItem  item = gridItem.meta.items.get(i);
        if (item.dimgForID == gridItem.id) {
            if (!new String(item.type).equals("av01")) {
                continue;
            }

            avifTile  tile = avifDecoderDataCreateTile(decoder.data, item.width, item.height, avifDecoderItemOperatingPoint(item));
            if (tile == null) {
                return false;
            }
            if (!avifCodecDecodeInputFillFromDecoderItem(tile.input,
                                                         item,
                                                         decoder.allowProgressive,
                                                         decoder.imageCountLimit,
                                                         decoder.io.sizeHint)) {
                return false;
            }
            tile.input.alpha = alpha;

            if (firstTile) {
                firstTile = false;

                // Adopt the av1C property of the first av01 tile, so that it can be queried from
                // the top-level color/alpha item during avifDecoderReset().
                final avifProperty  srcProp = avifPropertyArrayFind(item.properties, "av1C");
                if (srcProp == null) {
                    System.err.printf("Grid image's first tile is missing an av1C property");
                    return false;
                }
                avifProperty  dstProp = srcProp;
                gridItem.properties.add(dstProp);

                if (!alpha && item.progressive) {
                    decoder.progressiveState = avifProgressiveState.AVIF_PROGRESSIVE_STATE_AVAILABLE;
                    if (tile.input.samples.size() > 1) {
                        decoder.progressiveState = avifProgressiveState.AVIF_PROGRESSIVE_STATE_ACTIVE;
                        decoder.imageCount = tile.input.samples.size();
                    }
                }
            }
        }
    }
    return true;
}

// Checks the grid consistency and copies the pixels from the tiles to the
// dstImage. Only the freshly decoded tiles are considered, skipping the already
// copied or not-yet-decoded tiles.
private boolean avifDecoderDataFillImageGrid(avifDecoderData data,
                                             avifImageGrid  grid,
                                             avifImage  dstImage,
                                             int firstTileIndex,
                                             int oldDecodedTileCount,
                                             int decodedTileCount,
                                             boolean alpha)
{
    assert(decodedTileCount > oldDecodedTileCount);

    avifTile  firstTile = data.tiles.get(firstTileIndex);
    boolean firstTileUVPresent = (firstTile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v] != null && firstTile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v] != null);

    // Check for tile consistency: All tiles in a grid image should match in the properties checked below.
    for (int i = Math.max(1, oldDecodedTileCount); i < decodedTileCount; ++i) {
        avifTile  tile = data.tiles.get(firstTileIndex + i);
        boolean uvPresent = (tile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v] != null && tile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v] != null);
        if ((tile.image.width != firstTile.image.width) || (tile.image.height != firstTile.image.height) ||
            (tile.image.depth != firstTile.image.depth) || (tile.image.yuvFormat != firstTile.image.yuvFormat) ||
            (tile.image.yuvRange != firstTile.image.yuvRange) || (uvPresent != firstTileUVPresent) ||
            (tile.image.colorPrimaries != firstTile.image.colorPrimaries) ||
            (tile.image.transferCharacteristics != firstTile.image.transferCharacteristics) ||
            (tile.image.matrixCoefficients != firstTile.image.matrixCoefficients) ||
            (tile.image.alphaRange != firstTile.image.alphaRange)) {
            System.err.printf("Grid image contains mismatched tiles");
            return false;
        }
    }

    // Validate grid image size and tile size.
    //
    // HEIF (ISO/IEC 23008-12:2017), Section 6.6.2.3.1:
    //   The tiled input images shall completely "cover" the reconstructed image grid canvas, ...
    if (((firstTile.image.width * grid.columns) < grid.outputWidth) ||
        ((firstTile.image.height * grid.rows) < grid.outputHeight)) {
        System.err.printf(
                              "Grid image tiles do not completely cover the image (HEIF (ISO/IEC 23008-12:2017), Section 6.6.2.3.1)");
        return false;
    }
    // Tiles in the rightmost column and bottommost row must overlap the reconstructed image grid canvas. See MIAF (ISO/IEC 23000-22:2019), Section 7.3.11.4.2, Figure 2.
    if (((firstTile.image.width * (grid.columns - 1)) >= grid.outputWidth) ||
        ((firstTile.image.height * (grid.rows - 1)) >= grid.outputHeight)) {
        System.err.printf(
                              "Grid image tiles in the rightmost column and bottommost row do not overlap the reconstructed image grid canvas. See MIAF (ISO/IEC 23000-22:2019), Section 7.3.11.4.2, Figure 2");
        return false;
    }

    if (alpha) {
        // An alpha tile does not contain any YUV pixels.
        assert(firstTile.image.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_NONE);
    }
    if (!avif.avifAreGridDimensionsValid(firstTile.image.yuvFormat,
                                    grid.outputWidth,
                                    grid.outputHeight,
                                    firstTile.image.width,
                                    firstTile.image.height)) {
        return false;
    }

    // Lazily populate dstImage with the new frame's properties. If we're decoding alpha,
    // these values must already match.
    if ((dstImage.width != grid.outputWidth) || (dstImage.height != grid.outputHeight) ||
        (dstImage.depth != firstTile.image.depth) || (!alpha && (dstImage.yuvFormat != firstTile.image.yuvFormat))) {
        if (alpha) {
            // Alpha doesn't match size, just bail out
            System.err.printf("Alpha plane dimensions do not match color plane dimensions");
            return false;
        }

        dstImage.width = grid.outputWidth;
        dstImage.height = grid.outputHeight;
        dstImage.depth = firstTile.image.depth;
        dstImage.yuvFormat = firstTile.image.yuvFormat;
        dstImage.yuvRange = firstTile.image.yuvRange;
        if (!data.cicpSet) {
            data.cicpSet = true;
            dstImage.colorPrimaries = firstTile.image.colorPrimaries;
            dstImage.transferCharacteristics = firstTile.image.transferCharacteristics;
            dstImage.matrixCoefficients = firstTile.image.matrixCoefficients;
        }
    }
    if (alpha) {
        dstImage.alphaRange = firstTile.image.alphaRange;
    }

    dstImage.avifImageAllocatePlanes(EnumSet.of(alpha ? avifPlanesFlag.AVIF_PLANES_A : avifPlanesFlag.AVIF_PLANES_YUV));

    avifPixelFormatInfo formatInfo = firstTile.image.yuvFormat.avifGetPixelFormatInfo();

    int tileIndex = oldDecodedTileCount;
    int pixelBytes = dstImage.avifImageUsesU16() ? 2 : 1;
    int rowIndex = oldDecodedTileCount / grid.columns;
    int colIndex = oldDecodedTileCount % grid.columns;
    // Only the first iteration of the outer for loop uses this initial value of colIndex.
    // Subsequent iterations of the outer for loop initializes colIndex to 0.
    for (; rowIndex < grid.rows; ++rowIndex, colIndex = 0) {
        for (; colIndex < grid.columns; ++colIndex, ++tileIndex) {
            if (tileIndex >= decodedTileCount) {
                // Tile is not ready yet.
                return true;
            }
            avifTile  tile = data.tiles.get(firstTileIndex + tileIndex);

            int widthToCopy = firstTile.image.width;
            int maxX = firstTile.image.width * (colIndex + 1);
            if (maxX > grid.outputWidth) {
                widthToCopy -= maxX - grid.outputWidth;
            }

            int heightToCopy = firstTile.image.height;
            int maxY = firstTile.image.height * (rowIndex + 1);
            if (maxY > grid.outputHeight) {
                heightToCopy -= maxY - grid.outputHeight;
            }

            // Y and A channels
            int yaColOffset = colIndex * firstTile.image.width;
            int yaRowOffset = rowIndex * firstTile.image.height;
            int yaRowBytes = widthToCopy * pixelBytes;

            if (alpha) {
                // A
                for (int j = 0; j < heightToCopy; ++j) {
                    int src = j * tile.image.alphaRowBytes;
                    int dst = (yaColOffset * pixelBytes) + ((yaRowOffset + j) * dstImage.alphaRowBytes);
                    System.arraycopy(tile.image.alphaPlane, src, dstImage.alphaPlane, dst, yaRowBytes);
                }
            } else {
                // Y
                for (int j = 0; j < heightToCopy; ++j) {
                    int src = j * tile.image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v];
                    int dst = (yaColOffset * pixelBytes) + ((yaRowOffset + j) * dstImage.yuvRowBytes[avifChannelIndex.AVIF_CHAN_Y.v]);
                    System.arraycopy(tile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v], src, dstImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v], dst, yaRowBytes);
                }

                if (!firstTileUVPresent) {
                    continue;
                }

                // UV
                heightToCopy >>= formatInfo.chromaShiftY;
                int uvColOffset = yaColOffset >> formatInfo.chromaShiftX;
                int uvRowOffset = yaRowOffset >> formatInfo.chromaShiftY;
                int uvRowBytes = yaRowBytes >> formatInfo.chromaShiftX;
                for (int j = 0; j < heightToCopy; ++j) {
                    int srcU = j * tile.image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v];
                    int dstU = (uvColOffset * pixelBytes) + ((uvRowOffset + j) * dstImage.yuvRowBytes[avifChannelIndex.AVIF_CHAN_U.v]);
                    System.arraycopy(tile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v], srcU, dstImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_U.v], dstU, uvRowBytes);

                    int srcV = j * tile.image.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v];
                    int dstV = (uvColOffset * pixelBytes) + ((uvRowOffset + j) * dstImage.yuvRowBytes[avifChannelIndex.AVIF_CHAN_V.v]);
                    System.arraycopy(tile.image.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v], srcV, dstImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_V.v], dstV, uvRowBytes);
                }
            }
        }
    }

    return true;
}

// If colorId == 0 (a sentinel value as item IDs must be nonzero), accept any found EXIF/XMP metadata. Passing in 0
// is used when finding metadata in a meta box embedded in a trak box, as any items inside of a meta box that is
// inside of a trak box are implicitly associated to the track.
private avifResult avifDecoderFindMetadata(avifDecoder  decoder, avifMeta  meta, avifImage  image, int colorId)
{
    if (decoder.ignoreExif && decoder.ignoreXMP) {
        // Nothing to do!
        return avifResult.AVIF_RESULT_OK;
    }

    for (int itemIndex = 0; itemIndex < meta.items.size(); ++itemIndex) {
        avifDecoderItem  item = meta.items.get(itemIndex);
        if (item.size == 0) {
            continue;
        }
        if (item.hasUnsupportedEssentialProperty) {
            // An essential property isn't supported by libavif; ignore the item.
            continue;
        }

        if ((colorId > 0) && (item.descForID != colorId)) {
            // Not a content description (metadata) for the colorOBU, skip it
            continue;
        }

        if (!decoder.ignoreExif && item.type.equals("Exif")) {
            final byte[] /* avifROData */ exifContents;
            avifResult readResult = avifDecoderItemRead(item, decoder.io, exifContents, 0, 0);
            if (readResult != avifResult.AVIF_RESULT_OK) {
                return readResult;
            }

            // Advance past Annex A.2.1's header
            DataInputStream exifBoxStream = new DataInputStream(new ByteArrayInputStream(exifContents)); // "Exif header"
            int exifTiffHeaderOffset = exifBoxStream.readInt();

            avifImageSetMetadataExif(image, exifBoxStream);
        } else if (!decoder.ignoreXMP && item.type.equals("mime") &&
                   item.contentType.contentType.equals(xmpContentType)) {
            final byte[] /* avifROData */ xmpContents;
            avifResult readResult = avifDecoderItemRead(item, decoder.io, xmpContents, 0, 0);
            if (readResult != avifResult.AVIF_RESULT_OK) {
                return readResult;
            }

            image.avifImageSetMetadataXMP(xmpContents, xmpContents.length);
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

// ---------------------------------------------------------------------------
// URN

private boolean isAlphaURN(final byte[] urn)
{
    String _urn = new String(urn);
    return _urn.equals(URN_ALPHA0) || _urn.equals(URN_ALPHA1);
}

// ---------------------------------------------------------------------------
// BMFF Parsing

// "Box[hdlr]"
private boolean avifParseHandlerBox(DataInputStream s)
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    int predefined = s.readInt();
    if (predefined != 0) {
        System.err.printf("Box[hdlr] contains a pre_defined value that is nonzero");
        return false;
    }

    byte[] handlerType = new byte[4];
    s.readFully(handlerType);
    if (handlerType.equals("pict")) {
        System.err.printf("Box[hdlr] handler_type is not 'pict'");
        return false;
    }

    for (int i = 0; i < 3; ++i) {
        int reserved = s.readInt();
    }

    // Verify that a valid string is here, but don't bother to store it
    if (!(avifROStreamReadString(s, null, 0))) return false; // string name;
    return true;
}

// "Box[iloc]"
private boolean avifParseItemLocationBox(avifMeta  meta, DataInputStream s) throws IOException
{
    int[] version = stream.avifROStreamReadVersionAndFlags(s, 0);
    if (version[0] > 2) {
        System.err.printf("Box[iloc] has an unsupported version [%u]", version);
        return false;
    }

    byte offsetSizeAndLengthSize = s.readByte();
    byte offsetSize = (byte) ((offsetSizeAndLengthSize >> 4) & 0xf); // int(4) offset_size;
    byte lengthSize = (byte) ((offsetSizeAndLengthSize >> 0) & 0xf); // int(4) length_size;

    byte baseOffsetSizeAndIndexSize = s.readByte();
    byte baseOffsetSize = (byte) ((baseOffsetSizeAndIndexSize >> 4) & 0xf); // int(4) base_offset_size;
    byte indexSize = 0;
    if ((version[0] == 1) || (version[0] == 2)) {
        indexSize = (byte) (baseOffsetSizeAndIndexSize & 0xf); // int(4) index_size;
        if (indexSize != 0) {
            // extent_index unsupported
            System.err.printf("Box[iloc] has an unsupported extent_index");
            return false;
        }
    }

    short tmp16;
    int itemCount;
    if (version[0] < 2) {
        tmp16 = s.readShort();
        itemCount = tmp16;
    } else {
        itemCount = s.readInt();
    }
    for (int i = 0; i < itemCount; ++i) {
        int itemID;
        if (version[0] < 2) {
            tmp16 = s.readShort();
            itemID = tmp16;
        } else {
            itemID = s.readInt();
        }

        avifDecoderItem  item = avifMetaFindItem(meta, itemID);
        if (item == null) {
            System.err.printf("Box[iloc] has an invalid item ID [%u]", itemID);
            return false;
        }
        if (item.extents.size() > 0) {
            // This item has already been given extents via this iloc box. This is invalid.
            System.err.printf("Item ID [%u] contains duplicate sets of extents", itemID);
            return false;
        }

        if ((version[0] == 1) || (version[0] == 2)) {
            byte ignored;
            byte constructionMethod;
            ignored = s.readByte();
            constructionMethod = s.readByte();
            constructionMethod = (byte) (constructionMethod & 0xf);
            if ((constructionMethod != 0 /* file */) && (constructionMethod != 1 /* idat */)) {
                // construction method item(2) unsupported
                System.err.printf("Box[iloc] has an unsupported construction method [%u]", constructionMethod);
                return false;
            }
            if (constructionMethod == 1) {
                item.idatStored = true;
            }
        }

        short dataReferenceIndex = s.readShort();
        long baseOffset = s.readByte();
        short extentCount = s.readShort();
        for (int extentIter = 0; extentIter < extentCount; ++extentIter) {
            // If extent_index is ever supported, this spec must be implemented here:
            // ::  if (((version == 1) || (version == 2)) && (index_size > 0)) {
            // ::      int(index_size*8) extent_index;
            // ::  }

            long extentOffset = s.readByte();
            long extentLength = s.readByte();

            avifExtent  extent = new avifExtent();
            if (extentOffset > Long.MAX_VALUE - baseOffset) {
                System.err.printf(
                                      "Item ID [%u] contains an extent offset which overflows: [base: %d offset:%d]",
                                      itemID,
                                      baseOffset,
                                      extentOffset);
                return false;
            }
            long offset = baseOffset + extentOffset;
            extent.offset = offset;
            if (extentLength > Integer.MAX_VALUE) {
                System.err.printf("Item ID [%u] contains an extent length which overflows: [%d]", itemID, extentLength);
                return false;
            }
            extent.size = (int)extentLength;
            if (extent.size > Integer.MAX_VALUE - item.size) {
                System.err.printf(
                                      "Item ID [%u] contains an extent length which overflows the item size: [%zu, %zu]",
                                      itemID,
                                      extent.size,
                                      item.size);
                return false;
            }
            item.extents.add(extent);
            item.size += extent.size;
        }
    }
    return true;
}

// "Box[grid]"
private boolean avifParseImageGridBox(avifImageGrid  grid, DataInputStream s, int imageSizeLimit) throws IOException
{
    byte version = s.readByte();
    if (version != 0) {
        System.err.printf("Box[grid] has unsupported version [%u]", version);
        return false;
    }
    byte rowsMinusOne, columnsMinusOne;
    byte flags = s.readByte();
    rowsMinusOne = s.readByte();
    columnsMinusOne = s.readByte();
    grid.rows = (int)rowsMinusOne + 1;
    grid.columns = (int)columnsMinusOne + 1;

    int fieldLength = ((flags & 1) + 1) * 16;
    if (fieldLength == 16) {
        short outputWidth16 = s.readShort();
        short outputHeight16 = s.readShort();
        grid.outputWidth = outputWidth16;
        grid.outputHeight = outputHeight16;
    } else {
        if (fieldLength != 32) {
            // This should be impossible
            System.err.printf("Grid box contains illegal field length: [%u]", fieldLength);
            return false;
        }
        grid.outputWidth = s.readInt();
        grid.outputHeight = s.readInt();
    }
    if ((grid.outputWidth == 0) || (grid.outputHeight == 0)) {
        System.err.printf("Grid box contains illegal dimensions: [%u x %u]", grid.outputWidth, grid.outputHeight);
        return false;
    }
    if (grid.outputWidth > (imageSizeLimit / grid.outputHeight)) {
        System.err.printf("Grid box dimensions are too large: [%u x %u]", grid.outputWidth, grid.outputHeight);
        return false;
    }
    return s.available() == 0;
}

// "Box[ispe]"
private boolean avifParseImageSpatialExtentsProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    avifImageSpatialExtents ispe = prop.u.ispe;
    ispe.width = s.readInt();
    ispe.height = s.readInt();
    return true;
}

// "Box[auxC]"
private boolean avifParseAuxiliaryTypeProperty(avifProperty  prop, DataInputStream s)
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    if (!(stream.avifROStreamReadString(s, prop.u.auxC.auxType, AUXTYPE_SIZE))) return false;
    return true;
}

// "Box[colr]"
private boolean avifParseColourInformationBox(avifProperty  prop, DataInputStream s)
{
    avifColourInformationBox colr = prop.u.colr;
    colr.hasICC = false;
    colr.hasNCLX = false;

    byte[] colorType = new byte[4]; // int(32) colour_type;
    s.readFully(colorType);
    if (colorType.equals("rICC") || colorType.equals("prof")) {
        colr.hasICC = true;
        colr.icc = DataInputStreamCurrent(s);
        colr.iccSize = DataInputStreamRemainingBytes(s);
    } else if (colorType.equals("nclx")) {
        colr.colorPrimaries = avifColorPrimaries.valueOf(s.readShort());
        colr.transferCharacteristics = avifTransferCharacteristics.valueOf(s.readShort());
        colr.matrixCoefficients = avifMatrixCoefficients.valueOf(s.readShort());
        // int(1) full_range_flag;
        // int(7) reserved = 0;
        byte[] tmp8 = new byte[1];
        s.readFully(tmp8);
        colr.range = (tmp8[0] & 0x80) != 0 ? avifRange.AVIF_RANGE_FULL : avifRange.AVIF_RANGE_LIMITED;
        colr.hasNCLX = true;
    }
    return true;
}

// "Box[av1C]"
private boolean avifParseAV1CodecConfigurationBox(DataInputStream s, avifCodecConfigurationBox av1C) throws IOException
{
    byte markerAndVersion = s.readByte();
    byte seqProfileAndIndex = s.readByte();
    byte rawFlags = s.readByte();

    if (markerAndVersion != 0x81) {
        // Marker and version must both == 1
        System.err.printf("av1C contains illegal marker and version pair: [%u]", markerAndVersion);
        return false;
    }

    av1C.seqProfile = (byte) ((seqProfileAndIndex >> 5) & 0x7);    // int (3) seq_profile;
    av1C.seqLevelIdx0 = (byte) ((seqProfileAndIndex >> 0) & 0x1f); // int (5) seq_level_idx_0;
    av1C.seqTier0 = (byte) ((rawFlags >> 7) & 0x1);                // int (1) seq_tier_0;
    av1C.highBitdepth = (byte) ((rawFlags >> 6) & 0x1);            // int (1) high_bitdepth;
    av1C.twelveBit = (byte) ((rawFlags >> 5) & 0x1);               // int (1) twelve_bit;
    av1C.monochrome = (byte) ((rawFlags >> 4) & 0x1);              // int (1) monochrome;
    av1C.chromaSubsamplingX = (byte) ((rawFlags >> 3) & 0x1);      // int (1) chroma_subsampling_x;
    av1C.chromaSubsamplingY = (byte) ((rawFlags >> 2) & 0x1);      // int (1) chroma_subsampling_y;
    av1C.chromaSamplePosition = (byte) ((rawFlags >> 0) & 0x3);    // int (2) chroma_sample_position;
    return true;
}

private boolean avifParseAV1CodecConfigurationBoxProperty(avifProperty  prop, DataInputStream s, int rawLen)
{
    return avifParseAV1CodecConfigurationBox(s, rawLen, prop.u.av1C);
}

// "Box[pasp]"
private boolean avifParsePixelAspectRatioBoxProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifPixelAspectRatioBox pasp = prop.u.pasp;
    pasp.hSpacing = s.readInt();
    pasp.vSpacing = s.readInt();
    return true;
}

// "Box[clap]"
private boolean avifParseCleanApertureBoxProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifCleanApertureBox clap = prop.u.clap;
    clap.widthN = s.readInt();
    clap.widthD = s.readInt();
    clap.heightN = s.readInt();
    clap.heightD = s.readInt();
    clap.horizOffN = s.readInt();
    clap.horizOffD = s.readInt();
    clap.vertOffN = s.readInt();
    clap.vertOffD = s.readInt();
    return true;
}

// "Box[irot]"
private boolean avifParseImageRotationProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifImageRotation irot = prop.u.irot;
    irot.angle = s.readByte(); // int (6) reserved = 0; int (2) angle;
    if ((irot.angle & 0xfc) != 0) {
        // reserved bits must be 0
        System.err.printf("Box[irot] contains nonzero reserved bits [%u]", irot.angle);
        return false;
    }
    return true;
}

// "Box[imir]"
private boolean avifParseImageMirrorProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifImageMirror imir = prop.u.imir;
    imir.mode = s.readByte(); // int (7) reserved = 0; int (1) mode;
    if ((imir.mode & 0xfe) != 0) {
        // reserved bits must be 0
        System.err.printf("Box[imir] contains nonzero reserved bits [%u]", imir.mode);
        return false;
    }
    return true;
}

// "Box[pixi]"
private boolean avifParsePixelInformationProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    avifPixelInformationProperty pixi = prop.u.pixi;
    pixi.planeCount = s.readByte(); // int (8) num_channels;
    if (pixi.planeCount > MAX_PIXI_PLANE_DEPTHS) {
        System.err.printf("Box[pixi] contains unsupported plane count [%u]", pixi.planeCount);
        return false;
    }
    for (byte i = 0; i < pixi.planeCount; ++i) {
        pixi.planeDepths[i] = s.readByte(); // int (8) bits_per_channel;
    }
    return true;
}

// "Box[a1op]"
private boolean avifParseOperatingPointSelectorProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifOperatingPointSelectorProperty a1op = prop.u.a1op;
    s.readFully(a1op.opIndex);
    if (a1op.opIndex[0] > 31) { // 31 is AV1's max operating point value
        System.err.printf("Box[a1op] contains an unsupported operating point [%u]", a1op.opIndex);
        return false;
    }
    return true;
}

// "Box[lsel]"
private boolean avifParseLayerSelectorProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifLayerSelectorProperty  lsel = prop.u.lsel;
    lsel.layerID = s.readShort();
    if (lsel.layerID >= MAX_AV1_LAYER_COUNT) {
        System.err.printf("Box[lsel] contains an unsupported layer [%u]", lsel.layerID);
        return false;
    }
    return true;
}

// "Box[a1lx]"
private boolean avifParseAV1LayeredImageIndexingProperty(avifProperty  prop, DataInputStream s) throws IOException
{
    avifAV1LayeredImageIndexingProperty  a1lx = prop.u.a1lx;

    byte[] largeSize = new byte[1];
    s.readFully(largeSize);
    if ((largeSize[0] & 0xFE) != 0) {
        System.err.printf("Box[a1lx] has bits set in the reserved section [%u]", largeSize);
        return false;
    }

    for (int i = 0; i < 3; ++i) {
        if (largeSize[0] != 0) {
            a1lx.layerSize[i] = s.readInt();
        } else {
            short layerSize16 = s.readShort();
            a1lx.layerSize[i] = layerSize16;
        }
    }

    // Layer sizes will be validated layer (when the item's size is known)
    return true;
}

// "Box[ipco]"
private boolean avifParseItemPropertyContainerBox(List<avifProperty>  properties, DataInputStream s)
{
    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        int propertyIndex = avifArrayPushIndex(properties);
        avifProperty  prop = properties.get(propertyIndex);
        System.arraycopy(header.type, 0, prop.type, 0, 4);
        if (header.type.equals("ispe")) {
            if (!(avifParseImageSpatialExtentsProperty(prop, s))) return false;
        } else if (header.type.equals("auxC")) {
            if (!(avifParseAuxiliaryTypeProperty(prop, s))) return false;
        } else if (header.type.equals("colr")) {
            if (!(avifParseColourInformationBox(prop, s))) return false;
        } else if (header.type.equals("av1C")) {
            if (!(avifParseAV1CodecConfigurationBoxProperty(prop, s))) return false;
        } else if (header.type.equals("pasp")) {
            if (!(avifParsePixelAspectRatioBoxProperty(prop, s))) return false;
        } else if (header.type.equals("clap")) {
            if (!(avifParseCleanApertureBoxProperty(prop, s))) return false;
        } else if (header.type.equals("irot")) {
            if (!(avifParseImageRotationProperty(prop, s))) return false;
        } else if (header.type.equals("imir")) {
            if (!(avifParseImageMirrorProperty(prop, s))) return false;
        } else if (header.type.equals("pixi")) {
            if (!(avifParsePixelInformationProperty(prop, s))) return false;
        } else if (header.type.equals("a1op")) {
            if (!(avifParseOperatingPointSelectorProperty(prop, s))) return false;
        } else if (header.type.equals("lsel")) {
            if (!(avifParseLayerSelectorProperty(prop, s))) return false;
        } else if (header.type.equals("a1lx")) {
            if (!(avifParseAV1LayeredImageIndexingProperty(prop, s))) return false;
        }

        s.skipBytes(header.size);
    }
    return true;
}

// "Box[ipma]"
private boolean avifParseItemPropertyAssociation(avifMeta  meta, DataInputStream s, int[] outVersionAndFlags) throws IOException
{
    // NOTE: If this function ever adds support for versions other than [0,1] or flags other than
    //       [0,1], please increase the value of MAX_IPMA_VERSION_AND_FLAGS_SEEN accordingly.

    int[] version = stream.avifROStreamReadVersionAndFlags(s, 1);
    boolean propertyIndexIsU16 = ((version[1] & 0x1) != 0);
    outVersionAndFlags[0] = (version[0] << 24) | version[1];

    int entryCount = s.readInt();
    int prevItemID = 0;
    for (int entryIndex = 0; entryIndex < entryCount; ++entryIndex) {
        // ISO/IEC 23008-12, First edition, 2017-12, Section 9.3.1:
        //   Each ItemPropertyAssociation box shall be ordered by increasing item_ID, and there shall
        //   be at most one association box for each item_ID, in any ItemPropertyAssociation box.
        int itemID;
        if (version[0] < 1) {
            short tmp = s.readShort();
            itemID = tmp;
        } else {
            itemID = s.readInt();
        }
        if (itemID <= prevItemID) {
            System.err.printf("Box[ipma] item IDs are not ordered by increasing ID");
            return false;
        }
        prevItemID = itemID;

        avifDecoderItem  item = avifMetaFindItem(meta, itemID);
        if (item == null) {
            System.err.printf("Box[ipma] has an invalid item ID [%u]", itemID);
            return false;
        }
        if (item.ipmaSeen) {
            System.err.printf("Duplicate Box[ipma] for item ID [%u]", itemID);
            return false;
        }
        item.ipmaSeen = true;

        byte associationCount = s.readByte();
        for (byte associationIndex = 0; associationIndex < associationCount; ++associationIndex) {
            boolean essential = false;
            short propertyIndex = 0;
            if (propertyIndexIsU16) {
                propertyIndex = s.readShort();
                essential = ((propertyIndex & 0x8000) != 0);
                propertyIndex &= 0x7fff;
            } else {
                byte tmp = s.readByte();
                essential = ((tmp & 0x80) != 0);
                propertyIndex = (short) (tmp & 0x7f);
            }

            if (propertyIndex == 0) {
                // Not associated with any item
                continue;
            }
            --propertyIndex; // 1-indexed

            if (propertyIndex >= meta.properties.size()) {
                System.err.printf("Box[ipma] for item ID [%u] contains an illegal property index [%u] (out of [%u] properties)",
                                      itemID,
                                      propertyIndex,
                                      meta.properties.size());
                return false;
            }

            // Copy property to item
            final avifProperty  srcProp = meta.properties.get(propertyIndex);

            final String[] supportedTypes = { "ispe", "auxC", "colr", "av1C", "pasp", "clap",
                                                     "irot", "imir", "pixi", "a1op", "lsel", "a1lx" };
            int supportedTypesCount = supportedTypes.length;
            boolean supportedType = false;
            for (int i = 0; i < supportedTypesCount; ++i) {
                if (srcProp.type.equals(supportedTypes[i])) {
                    supportedType = true;
                    break;
                }
            }
            if (supportedType) {
                if (essential) {
                    // Verify that it is legal for this property to be flagged as essential. Any
                    // types in this list are *required* in the spec to not be flagged as essential
                    // when associated with an item.
                    final String[] nonessentialTypes = {

                        // AVIF: Section 2.3.2.3.2: "If associated, it shall not be marked as essential."
                        "a1lx"

                    };
                    int nonessentialTypesCount = nonessentialTypes.length;
                    for (int i = 0; i < nonessentialTypesCount; ++i) {
                        if (srcProp.type.equals(nonessentialTypes[i])) {
                            System.err.printf(
                                                  "Item ID [%u] has a %s property association which must not be marked essential, but is",
                                                  itemID,
                                                  nonessentialTypes[i]);
                            return false;
                        }
                    }
                } else {
                    // Verify that it is legal for this property to not be flagged as essential. Any
                    // types in this list are *required* in the spec to be flagged as essential when
                    // associated with an item.
                    final String[] essentialTypes = {

                        // AVIF: Section 2.3.2.1.1: "If associated, it shall be marked as essential."
                        "a1op",

                        // HEIF: Section 6.5.11.1: "essential shall be equal to 1 for an 'lsel' item property."
                        "lsel"

                    };
                    int essentialTypesCount = essentialTypes.length;
                    for (int i = 0; i < essentialTypesCount; ++i) {
                        if (srcProp.type.equals(essentialTypes[i])) {
                            System.err.printf(
                                                  "Item ID [%u] has a %s property association which must be marked essential, but is not",
                                                  itemID,
                                                  essentialTypes[i]);
                            return false;
                        }
                    }
                }

                // Supported and valid; associate it with this item.
                avifProperty dstProp = srcProp;
                item.properties.add(dstProp);
            } else {
                if (essential) {
                    // Discovered an essential item property that libavif doesn't support!
                    // Make a note to ignore this item later.
                    item.hasUnsupportedEssentialProperty = true;
                }
            }
        }
    }
    return true;
}

// "Box[pitm]"
private boolean avifParsePrimaryItemBox(avifMeta  meta, DataInputStream s) throws IOException
{
    if (meta.primaryItemID > 0) {
        // Illegal to have multiple pitm boxes, bail out
        System.err.printf("Multiple boxes of unique Box[pitm] found");
        return false;
    }

    int[] version = stream.avifROStreamReadVersionAndFlags(s, 0);

    if (version[0] == 0) {
        short tmp16 = s.readShort();
        meta.primaryItemID = tmp16;
    } else {
        meta.primaryItemID = s.readInt();
    }
    return true;
}

private boolean avifParseItemDataBox(avifMeta  meta, DataInputStream s, int rawLen)
{
    // Check to see if we've already seen an idat box for this meta box. If so, bail out
    if (meta.idat.length > 0) {
        System.err.printf("Meta box contains multiple idat boxes");
        return false;
    }
    if (rawLen == 0) {
        System.err.printf("idat box has a length of 0");
        return false;
    }

    rawdata.avifRWDataSet(meta.idat, raw, rawLen);
    return true;
}

// "Box[iprp]"
private boolean avifParseItemPropertiesBox(avifMeta  meta, DataInputStream s)
{
    avifBoxHeader ipcoHeader = stream.avifROStreamReadBoxHeader(s);
    if (Arrays.equals(ipcoHeader.type, "ipco", 4)) {
        System.err.printf("Failed to find Box[ipco] as the first box in Box[iprp]");
        return false;
    }

    // Read all item properties inside of ItemPropertyContainerBox
    if (!(stream.avifParseItemPropertyContainerBox(meta.properties, s, ipcoHeader.size))) return false;
    s.skipBytes(ipcoHeader.size);

    int[] versionAndFlagsSeen = new int[MAX_IPMA_VERSION_AND_FLAGS_SEEN];
    int versionAndFlagsSeenCount = 0;

    // Now read all ItemPropertyAssociation until the end of the box, and make associations
    while (s.available() > 1) {
        avifBoxHeader ipmaHeader = stream.avifROStreamReadBoxHeader(s);

        if (ipmaHeader.type.equals("ipma")) {
            int versionAndFlags;
            if (!(avifParseItemPropertyAssociation(meta, s, ipmaHeader.size, versionAndFlags))) return false;
            for (int i = 0; i < versionAndFlagsSeenCount; ++i) {
                if (versionAndFlagsSeen[i] == versionAndFlags) {
                    // HEIF (ISO 23008-12:2017) 9.3.1 - There shall be at most one
                    // ItemPropertyAssociation box with a given pair of values of version and
                    // flags.
                    System.err.printf("Multiple Box[ipma] with a given pair of values of version and flags. See HEIF (ISO 23008-12:2017) 9.3.1");
                    return false;
                }
            }
            if (versionAndFlagsSeenCount == MAX_IPMA_VERSION_AND_FLAGS_SEEN) {
                System.err.printf("Exceeded possible count of unique ipma version and flags tuples");
                return false;
            }
            versionAndFlagsSeen[versionAndFlagsSeenCount] = versionAndFlags;
            ++versionAndFlagsSeenCount;
        } else {
            // These must all be type ipma
            System.err.printf("Box[iprp] contains a box that isn't type 'ipma'");
            return false;
        }

        s.skipBytes(ipmaHeader.size);
    }
    return true;
}

// "Box[infe]"
private boolean avifParseItemInfoEntry(avifMeta  meta, DataInputStream s)
{
    int[] version = stream.avifROStreamReadVersionAndFlags(s, 1);
    // Version 2+ is required for item_type
    if (version[0] != 2 && version[0] != 3) {
        System.err.printf("%s: Expecting box version 2 or 3, got version %u", null, version);
        return false;
    }
    // TODO: check flags. ISO/IEC 23008-12:2017, Section 9.2 says:
    //   The flags field of ItemInfoEntry with version greater than or equal to 2 is specified as
    //   follows:
    //
    //   (flags & 1) equal to 1 indicates that the item is not intended to be a part of the
    //   presentation. For example, when (flags & 1) is equal to 1 for an image item, the image
    //   item should not be displayed.
    //   (flags & 1) equal to 0 indicates that the item is intended to be a part of the
    //   presentation.
    //
    // See also Section 6.4.2.

    int itemID;
    if (version[0] == 2) {
        short tmp = s.readShort();
        itemID = tmp;
    } else {
        assert(version[0] == 3);
        itemID = s.readInt();
    }
    short itemProtectionIndex = s.readShort();
    byte[] itemType = new byte[4];                                  // int(32) item_type;
    s.readFully(itemType, 0, 4);

    avifContentType contentType;
    if (itemType.equals("mime")) {
        if (!(stream.avifROStreamReadString(s, null, 0))) return false;                                   // string item_name; (skipped)
        if (!(stream.avifROStreamReadString(s, contentType.contentType, CONTENTTYPE_SIZE))) return false; // string content_type;
    } else {
        memset(contentType, 0, contentType.size);
    }

    avifDecoderItem  item = avifMetaFindItem(meta, itemID);
    if (item == null) {
        System.err.printf("Box[infe] has an invalid item ID [%u]", itemID);
        return false;
    }

    System.arraycopy(itemType, 0, item.type, 0, itemType.length);
    item.contentType = contentType;
    return true;
}

//"Box[iinf]"
private boolean avifParseItemInfoBox(avifMeta  meta, DataInputStream s)
{
    int[] version = stream.avifROStreamReadVersionAndFlags(s, 0);
    int entryCount;
    if (version[0] == 0) {
        short tmp = s.readShort();
        entryCount = tmp;
    } else if (version[0] == 1) {
        entryCount = s.readInt();
    } else {
        System.err.printf("Box[iinf] has an unsupported version %u", version);
        return false;
    }

    for (int entryIndex = 0; entryIndex < entryCount; ++entryIndex) {
        avifBoxHeader infeHeader = stream.avifROStreamReadBoxHeader(s);

        if (infeHeader.type.equals("infe")) {
            if (!(avifParseItemInfoEntry(meta, s, infeHeader.size))) return false;
        } else {
            // These must all be type infe
            System.err.printf("Box[iinf] contains a box that isn't type 'infe'");
            return false;
        }

        s.skipBytes(infeHeader.size);
    }

    return true;
}

// "Box[iref]"
private boolean avifParseItemReferenceBox(avifMeta  meta, DataInputStream s) throws IOException
{
    int[] version = stream.avifROStreamReadVersionAndFlags(s, 0);

    while (s.available() > 1) {
        avifBoxHeader irefHeader = stream.avifROStreamReadBoxHeader(s);

        int fromID = 0;
        if (version[0] == 0) {
            short tmp = s.readShort();
            fromID = tmp;
        } else if (version[0] == 1) {
            fromID = s.readInt();
        } else {
            // unsupported iref version, skip it
            break;
        }

        short referenceCount = s.readShort();

        for (short refIndex = 0; refIndex < referenceCount; ++refIndex) {
            int toID = 0;
            if (version[0] == 0) {
                short tmp = s.readShort();
                toID = tmp;
            } else if (version[0] == 1) {
                toID = s.readInt();
            } else {
                // unsupported iref version, skip it
                break;
            }

            // Read this reference as "{fromID} is a {irefType} for {toID}"
            if (fromID != 0 && toID != 0) {
                avifDecoderItem  item = avifMetaFindItem(meta, fromID);
                if (item == null) {
                    System.err.printf("Box[iref] has an invalid item ID [%u]", fromID);
                    return false;
                }

                if (irefHeader.type.equals("thmb")) {
                    item.thumbnailForID = toID;
                } else if (irefHeader.type.equals("auxl")) {
                    item.auxForID = toID;
                } else if (irefHeader.type.equals("cdsc")) {
                    item.descForID = toID;
                } else if (irefHeader.type.equals("dimg")) {
                    // derived images refer in the opposite direction
                    avifDecoderItem  dimg = avifMetaFindItem(meta, toID);
                    if (dimg == null) {
                        System.err.printf("Box[iref] has an invalid item ID dimg ref [%u]", toID);
                        return false;
                    }

                    dimg.dimgForID = fromID;
                } else if (irefHeader.type.equals("prem")) {
                    item.premByID = toID;
                }
            }
        }
    }

    return true;
}

// "Box[meta]"
private boolean avifParseMetaBox(avifMeta  meta, DataInputStream s)
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    ++meta.idatID; // for tracking idat

    boolean firstBox = true;
    int[] uniqueBoxFlags = new int[1];
    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (firstBox) {
            if (header.type.equals("hdlr")) {
                if (!(uniqueBoxSeen(uniqueBoxFlags, 0, "meta", "hdlr"))) return false;
                if (!(avifParseHandlerBox(s))) return false;
                firstBox = false;
            } else {
                // hdlr must be the first box!
                System.err.printf("Box[meta] does not have a Box[hdlr] as its first child box");
                return false;
            }
        } else if (header.type.equals("iloc")) {
            if (!(uniqueBoxSeen(uniqueBoxFlags, 1, "meta", "iloc"))) return false;
            if (!(avifParseItemLocationBox(meta, s))) return false;
        } else if (header.type.equals("pitm")) {
            if (!(uniqueBoxSeen(uniqueBoxFlags, 2, "meta", "pitm"))) return false;
            if (!(avifParsePrimaryItemBox(meta, s))) return false;
        } else if (header.type.equals("idat")) {
            if (!(uniqueBoxSeen(uniqueBoxFlags, 3, "meta", "idat"))) return false;
            if (!(avifParseItemDataBox(meta, s))) return false;
        } else if (header.type.equals("iprp")) {
            if (!(uniqueBoxSeen(uniqueBoxFlags, 4, "meta", "iprp"))) return false;
            if (!(avifParseItemPropertiesBox(meta, s))) return false;
        } else if (header.type.equals("iinf")) {
            if (!(uniqueBoxSeen(uniqueBoxFlags, 5, "meta", "iinf"))) return false;
            if (!(avifParseItemInfoBox(meta, s))) return false;
        } else if (header.type.equals("iref")) {
            if (!(uniqueBoxSeen(uniqueBoxFlags, 6, "meta", "iref"))) return false;
            if (!(avifParseItemReferenceBox(meta, s))) return false;
        }

        s.skipBytes(header.size);
    }
    if (firstBox) {
        // The meta box must not be empty (it must contain at least a hdlr box)
        System.err.printf("Box[meta] has no child boxes");
        return false;
    }
    return true;
}

// "Box[tkhd]"
private boolean avifParseTrackHeaderBox(avifTrack  track, DataInputStream s, int imageSizeLimit) throws IOException
{
    int[] version = stream.avifROStreamReadVersionAndFlags(s, 0);

    int ignored32, trackID;
    long ignored64;
    if (version[0] == 1) {
        ignored64 = s.readLong();
        ignored64 = s.readLong();
        trackID = s.readInt();
        ignored32 = s.readInt();
        ignored64 = s.readLong();
    } else if (version[0] == 0) {
        ignored32 = s.readInt();
        ignored32 = s.readInt();
        trackID = s.readInt();
        ignored32 = s.readInt();
        ignored32 = s.readInt();
    } else {
        // Unsupported version
        System.err.printf("Box[tkhd] has an unsupported version [%u]", version);
        return false;
    }

    // Skipping the following 52 bytes here:
    // ------------------------------------
    // final int(32)[2] reserved = 0;
    // template int(16) layer = 0;
    // template int(16) alternate_group = 0;
    // template int(16) volume = {if track_is_audio 0x0100 else 0};
    // final int(16) reserved = 0;
    // template int(32)[9] matrix= { 0x00010000,0,0,0,0x00010000,0,0,0,0x40000000 }; // unity matrix
    s.skipBytes(52);

    int width, height;
    width = s.readInt();
    height = s.readInt();
    track.width = width >> 16;
    track.height = height >> 16;

    if ((track.width == 0) || (track.height == 0)) {
        System.err.printf("Track ID [%u] has an invalid size [%ux%u]", track.id, track.width, track.height);
        return false;
    }
    if (track.width > (imageSizeLimit / track.height)) {
        System.err.printf("Track ID [%u] size is too large [%ux%u]", track.id, track.width, track.height);
        return false;
    }

    // TODO: support scaling based on width/height track header info?

    track.id = trackID;
    return true;
}

//"Box[mdhd]"
private boolean avifParseMediaHeaderBox(avifTrack  track, DataInputStream s) throws IOException
{
    int[] verAndFlag = stream.avifROStreamReadVersionAndFlags(s, 0);

    int ignored32, mediaTimescale, mediaDuration32;
    long ignored64, mediaDuration64;
    if (verAndFlag[0] == 1) {
        ignored64 = s.readLong();
        ignored64 = s.readLong();
        mediaTimescale = s.readInt();
        mediaDuration64 = s.readLong();
        track.mediaDuration = mediaDuration64;
    } else if (verAndFlag[0] == 0) {
        ignored32 = s.readInt();
        ignored32 = s.readInt();
        mediaTimescale = s.readInt();
        mediaDuration32 = s.readInt();
        track.mediaDuration = mediaDuration32;
    } else {
        // Unsupported version
        System.err.printf("Box[mdhd] has an unsupported version [%u]", verAndFlag[0]);
        return false;
    }

    track.mediaTimescale = mediaTimescale;
    return true;
}

// largeOffsets ? "Box[co64]" : "Box[stco]"
private boolean avifParseChunkOffsetBox(avifSampleTable  sampleTable, boolean largeOffsets, DataInputStream s) throws IOException
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    int entryCount = s.readInt();
    for (int i = 0; i < entryCount; ++i) {
        long offset;
        if (largeOffsets) {
            offset = s.readLong();
        } else {
            int offset32 = s.readInt();
            offset = offset32;
        }

        avifSampleTableChunk  chunk = new avifSampleTableChunk();
        chunk.offset = offset;
        sampleTable.chunks.add(chunk);
    }
    return true;
}

//"Box[stsc]"
private boolean avifParseSampleToChunkBox(avifSampleTable  sampleTable, DataInputStream s) throws IOException
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    int entryCount = s.readInt();
    int prevFirstChunk = 0;
    for (int i = 0; i < entryCount; ++i) {
        avifSampleTableSampleToChunk  sampleToChunk = new avifSampleTableSampleToChunk();
        sampleToChunk.firstChunk = s.readInt();
        sampleToChunk.samplesPerChunk = s.readInt();
        sampleToChunk.sampleDescriptionIndex = s.readInt();
        sampleTable.sampleToChunks.add(sampleToChunk);
        // The first_chunk fields should start with 1 and be strictly increasing.
        if (i == 0) {
            if (sampleToChunk.firstChunk != 1) {
                System.err.printf("Box[stsc] does not begin with chunk 1 [%u]", sampleToChunk.firstChunk);
                return false;
            }
        } else {
            if (sampleToChunk.firstChunk <= prevFirstChunk) {
                System.err.printf("Box[stsc] chunks are not strictly increasing");
                return false;
            }
        }
        prevFirstChunk = sampleToChunk.firstChunk;
    }
    return true;
}

// "Box[stsz]"
private boolean avifParseSampleSizeBox(avifSampleTable  sampleTable, DataInputStream s) throws IOException
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    int allSamplesSize, sampleCount;
    allSamplesSize = s.readInt();
    sampleCount = s.readInt();

    if (allSamplesSize > 0) {
        sampleTable.allSamplesSize = allSamplesSize;
    } else {
        for (int i = 0; i < sampleCount; ++i) {
            avifSampleTableSampleSize  sampleSize = new avifSampleTableSampleSize();
            sampleSize.size = s.readInt();
            sampleTable.sampleSizes.add(sampleSize);
        }
    }
    return true;
}

// "Box[stss]"
private boolean avifParseSyncSampleBox(avifSampleTable  sampleTable, DataInputStream s) throws IOException
{
    int entryCount;
    entryCount = s.readInt();

    for (int i = 0; i < entryCount; ++i) {
        int sampleNumber = 0;
        sampleNumber = s.readInt();
        avifSyncSample  syncSample = new avifSyncSample();
        syncSample.sampleNumber = sampleNumber;
        sampleTable.syncSamples.add(syncSample);
    }
    return true;
}

//"Box[stts]"
private boolean avifParseTimeToSampleBox(avifSampleTable  sampleTable, DataInputStream s) throws IOException
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    int entryCount = s.readInt();

    for (int i = 0; i < entryCount; ++i) {
        avifSampleTableTimeToSample  timeToSample = new avifSampleTableTimeToSample();
        timeToSample.sampleCount = s.readInt();
        timeToSample.sampleDelta = s.readInt();
        sampleTable.timeToSamples.add(timeToSample);
    }
    return true;
}

//"Box[stsd]"
private boolean avifParseSampleDescriptionBox(avifSampleTable  sampleTable, DataInputStream s)
{
    if (!(stream.avifROStreamReadAndEnforceVersion(s, 0))) return false;

    int entryCount = s.readInt();

    for (int i = 0; i < entryCount; ++i) {
        avifBoxHeader sampleEntryHeader = stream.avifROStreamReadBoxHeader(s);

        avifSampleDescription  description = new avifSampleDescription();
        description.properties = new ArrayList<>(16);
        System.arraycopy(sampleEntryHeader.type, 0, description.format, 0, description.format.length());
        int remainingBytes = DataInputStreamRemainingBytes(s);
        if (description.format.equals("av01") && (remainingBytes > VISUALSAMPLEENTRY_SIZE)) {
            CHECK(avifParseItemPropertyContainerBox(description.properties,
                                                    s, VISUALSAMPLEENTRY_SIZE,
                                                    remainingBytes - VISUALSAMPLEENTRY_SIZE));
        }

        s.skipBytes(sampleEntryHeader.size);
        sampleTable.sampleDescriptions.add(description);
    }
    return true;
}

// "Box[stbl]"
private boolean avifParseSampleTableBox(avifTrack  track, DataInputStream s) throws IOException
{
    if (track.sampleTable != null) {
        // A TrackBox may only have one SampleTable
        System.err.printf("Duplicate Box[stbl] for a single track detected");
        return false;
    }
    track.sampleTable = avifSampleTableCreate();

    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (header.type.equals("stco")) {
            if (!(avifParseChunkOffsetBox(track.sampleTable, false, s))) return false;
        } else if (header.type.equals("co64")) {
            if (!(avifParseChunkOffsetBox(track.sampleTable, true, s))) return false;
        } else if (header.type.equals("stsc")) {
            if (!(avifParseSampleToChunkBox(track.sampleTable, s))) return false;
        } else if (header.type.equals("stsz")) {
            if (!(avifParseSampleSizeBox(track.sampleTable, s))) return false;
        } else if (header.type.equals("stss")) {
            if (!(avifParseSyncSampleBox(track.sampleTable, s))) return false;
        } else if (header.type.equals("stts")) {
            if (!(avifParseTimeToSampleBox(track.sampleTable, s))) return false;
        } else if (header.type.equals("stsd")) {
            if (!(avifParseSampleDescriptionBox(track.sampleTable, s))) return false;
        }

        s.skipBytes(header.size);
    }
    return true;
}

// "Box[minf]"
private boolean avifParseMediaInformationBox(avifTrack  track, DataInputStream s) throws IOException
{
    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (header.type.equals("stbl")) {
            if (!(avifParseSampleTableBox(track, s))) return false;
        }

        s.skipBytes(header.size);
    }
    return true;
}

// "Box[mdia]"
private boolean avifParseMediaBox(avifTrack  track, DataInputStream s) throws IOException
{
    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (header.type.equals("mdhd")) {
            if (!(avifParseMediaHeaderBox(track, s))) return false;
        } else if (header.type.equals("minf")) {
            if (!(avifParseMediaInformationBox(track, s))) return false;
        }

        s.skipBytes(header.size);
    }
    return true;
}

// "Box[tref]"
private boolean avifTrackReferenceBox(avifTrack  track, DataInputStream s) throws IOException
{
    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (header.type.equals("auxl")) {
            int toID = s.readInt();
            s.skipBytes(header.size - Integer.BYTES); // just take the first one
            track.auxForID = toID;
        } else if (header.type.equals("prem")) {
            int byID = s.readInt();
            s.skipBytes(header.size - Integer.BYTES); // just take the first one
            track.premByID = byID;
        } else {
            s.skipBytes(header.size);
        }
    }
    return true;
}

// "Box[trak]"
private boolean avifParseTrackBox(avifDecoderData data, DataInputStream s, int imageSizeLimit) throws IOException
{
    avifTrack  track = avifDecoderDataCreateTrack(data);

    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (header.type.equals("tkhd")) {
            if (!(avifParseTrackHeaderBox(track, s, imageSizeLimit))) return false;
        } else if (header.type.equals("meta")) {
            if (!(avifParseMetaBox(track.meta, s))) return false;
        } else if (header.type.equals("mdia")) {
            if (!(avifParseMediaBox(track, s))) return false;
        } else if (header.type.equals("tref")) {
            if (!(avifTrackReferenceBox(track, s))) return false;
        }

        s.skipBytes(header.size);
    }
    return true;
}

// "Box[moov]"
private boolean avifParseMovieBox(avifDecoderData data, DataInputStream s, int imageSizeLimit) throws IOException
{
    while (s.available() > 1) {
        avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);

        if (header.type.equals("trak")) {
            if (!(avifParseTrackBox(data, s, imageSizeLimit))) return false;
        }

        s.skipBytes(header.size);
    }
    return true;
}

// "Box[ftyp]"
private boolean avifParseFileTypeBox(avifFileType  ftyp, DataInputStream s)
{
    s.readFully(ftyp.majorBrand);
    ftyp.minorVersion = s.readInt();

    int compatibleBrandsBytes = s.available();
    if ((compatibleBrandsBytes % 4) != 0) {
        System.err.printf("Box[ftyp] contains a compatible brands section that isn't divisible by 4 [%zu]", compatibleBrandsBytes);
        return false;
    }
    ftyp.compatibleBrands = DataInputStreamCurrent(s);
    s.skipBytes(compatibleBrandsBytes);
    ftyp.compatibleBrandsCount = compatibleBrandsBytes / 4;

    return true;
}

//private boolean avifFileTypeHasBrand(avifFileType  ftyp, final String brand);
//private boolean avifFileTypeIsCompatible(avifFileType  ftyp);

private avifResult avifParse(avifDecoder  decoder)
{
    // Note: this top-level function is the only avifParse*() function that returns avifResult instead of boolean.
    // Be sure to use CHECKERR() in this function with an explicit error result instead of simply using CHECK().

    avifResult readResult;
    long parseOffset = 0;
    avifDecoderData data = decoder.data;
    boolean ftypSeen = false;
    boolean metaSeen = false;
    boolean moovSeen = false;
    boolean needsMeta = false;
    boolean needsMoov = false;

    for (;;) {
        // Read just enough to get the next box header (a max of 32 bytes)
        final byte[] /* avifROData */ headerContents;
        if ((decoder.io.sizeHint > 0) && (parseOffset > decoder.io.sizeHint)) {
            return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
        }
        readResult = decoder.io.read(0, parseOffset, 32, headerContents);
        if (readResult != avifResult.AVIF_RESULT_OK) {
            return readResult;
        }
        if (headerContents.length == 0) {
            // If we got avifResult.AVIF_RESULT_OK from the reader but received 0 bytes,
            // we've reached the end of the file with no errors. Hooray!
            break;
        }

        // Parse the header, and find out how many bytes it actually was
        DataInputStream headerStream = new DataInputStream(new ByteArrayInputStream(headerContents));
        avifBoxHeader header;
        if (!(stream.avifROStreamReadBoxHeaderPartial(headerStream, header))) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
        parseOffset += headerStream.offset;
        assert((decoder.io.sizeHint == 0) || (parseOffset <= decoder.io.sizeHint));

        // Try to get the remainder of the box, if necessary
        final byte[] /* avifROData */ boxContents = avif.AVIF_DATA_EMPTY;

        // TODO: reorg this code to only do these memcmps once each
        if (header.type.equals("ftyp") || header.type.equals("meta") || header.type.equals("moov")) {
            readResult = decoder.io.read(0, parseOffset, header.size, boxContents);
            if (readResult != avifResult.AVIF_RESULT_OK) {
                return readResult;
            }
            if (boxContents.length != header.size) {
                // A truncated box, bail out
                return avifResult.AVIF_RESULT_TRUNCATED_DATA;
            }
        } else if (header.size > (Integer.MAX_VALUE - parseOffset)) {
            return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
        }
        parseOffset += header.size;

        if (header.type.equals("ftyp")) {
            if (!(!ftypSeen)) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            avifFileType ftyp;
            if (!(avifParseFileTypeBox(ftyp, boxContents, boxContents.length))) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            if (!avifFileTypeIsCompatible(ftyp)) {
                return avifResult.AVIF_RESULT_INVALID_FTYP;
            }
            ftypSeen = true;
            System.arraycopy(ftyp.majorBrand, 0, data.majorBrand, 0, 4); // Remember the major brand for future AVIF_DECODER_SOURCE_AUTO decisions
            needsMeta = avifFileTypeHasBrand(ftyp, "avif");
            needsMoov = avifFileTypeHasBrand(ftyp, "avis");
        } else if (header.type.equals("meta")) {
            if (!(!metaSeen)) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            if (!(avifParseMetaBox(data.meta, boxContents, boxContents.length))) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            metaSeen = true;
        } else if (header.type.equals("moov")) {
            if (!(!moovSeen)) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            if (!(avifParseMovieBox(data, boxContents, boxContents.length, decoder.imageSizeLimit))) return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            moovSeen = true;
        }

        // See if there is enough information to consider Parse() a success and early-out:
        // * If the brand 'avif' is present, require a meta box
        // * If the brand 'avis' is present, require a moov box
        if (ftypSeen && (!needsMeta || metaSeen) && (!needsMoov || moovSeen)) {
            return avifResult.AVIF_RESULT_OK;
        }
    }
    if (!ftypSeen) {
        return avifResult.AVIF_RESULT_INVALID_FTYP;
    }
    if ((needsMeta && !metaSeen) || (needsMoov && !moovSeen)) {
        return avifResult.AVIF_RESULT_TRUNCATED_DATA;
    }
    return avifResult.AVIF_RESULT_OK;
}

// ---------------------------------------------------------------------------

private boolean avifFileTypeHasBrand(avifFileType  ftyp, final String brand)
{
    if (ftyp.majorBrand.equals(brand)) {
        return true;
    }

    for (int compatibleBrandIndex = 0; compatibleBrandIndex < ftyp.compatibleBrandsCount; ++compatibleBrandIndex) {
        final byte[] compatibleBrand = ftyp.compatibleBrands[4 * compatibleBrandIndex];
        if (compatibleBrand.equals(brand)) {
            return true;
        }
    }
    return false;
}

private boolean avifFileTypeIsCompatible(avifFileType  ftyp)
{
    return avifFileTypeHasBrand(ftyp, "avif") || avifFileTypeHasBrand(ftyp, "avis");
}

boolean avifPeekCompatibleFileType(final byte[] /* avifROData */  input)
{
    DataInputStream s = new DataInputStream(new ByteArrayInputStream(input));

    avifBoxHeader header = stream.avifROStreamReadBoxHeader(s);
    if (Arrays.equals(header.type, "ftyp", 4)) {
        return false;
    }

    avifFileType ftyp = new avifFileType();
    boolean parsed = avifParseFileTypeBox(ftyp, s);
    if (!parsed) {
        return false;
    }
    return avifFileTypeIsCompatible(ftyp);
}

// ---------------------------------------------------------------------------

avifDecoder  avifDecoderCreate()
{
    avifDecoder  decoder = new avifDecoder();
    decoder.maxThreads = 1;
    decoder.imageSizeLimit = avif.AVIF_DEFAULT_IMAGE_SIZE_LIMIT;
    decoder.imageCountLimit = avif.AVIF_DEFAULT_IMAGE_COUNT_LIMIT;
    decoder.strictFlags = EnumSet.of(avifStrictFlag.AVIF_STRICT_ENABLED);
    return decoder;
}

private void avifDecoderCleanup(avifDecoder  decoder)
{
    if (decoder.data != null) {
        decoder.data = null;
    }

    if (decoder.image != null) {
        decoder.image = null;
    }
}

void avifDecoderDestroy(avifDecoder  decoder)
{
    avifDecoderCleanup(decoder);
}

avifResult avifDecoderSetSource(avifDecoder  decoder, avifDecoderSource source)
{
    decoder.requestedSource = source;
    return avifDecoderReset(decoder);
}

void avifDecoderSetIO(avifDecoder  decoder, avifIO  io)
{
    decoder.io = io;
}

avifResult avifDecoderSetIOMemory(avifDecoder  decoder, final byte[] data, int size)
{
    avifIO io = new io.avifIOMemoryReader(data, size);
    avifDecoderSetIO(decoder, io);
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderSetIOFile(avifDecoder  decoder, final String filename)
{
    avifIO io = new io.avifIOFileReader(filename);
    avifDecoderSetIO(decoder, io);
    return avifResult.AVIF_RESULT_OK;
}

// 0-byte extents are ignored/overwritten during the merge, as they are the signal from helper
// functions that no extent was necessary for this given sample. If both provided extents are
// >0 bytes, this will set dst to be an extent that bounds both supplied extents.
private avifResult avifExtentMerge(avifExtent  dst, final avifExtent  src)
{
    if (dst.size == 0) {
        dst = src;
        return avifResult.AVIF_RESULT_OK;
    }
    if (src.size == 0) {
        return avifResult.AVIF_RESULT_OK;
    }

    final long minExtent1 = dst.offset;
    final long maxExtent1 = dst.offset + dst.size;
    final long minExtent2 = src.offset;
    final long maxExtent2 = src.offset + src.size;
    dst.offset = Math.min(minExtent1, minExtent2);
    final long extentLength = Math.max(maxExtent1, maxExtent2) - dst.offset;
    if (extentLength > Integer.MAX_VALUE) {
        return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
    }
    dst.size = (int)extentLength;
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderNthImageMaxExtent(final avifDecoder  decoder, int frameIndex, avifExtent  outExtent)
{
    if (decoder.data == null) {
        // Nothing has been parsed yet
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    outExtent = new avifExtent();

    int startFrameIndex = avifDecoderNearestKeyframe(decoder, frameIndex);
    int endFrameIndex = frameIndex;
    for (int currentFrameIndex = startFrameIndex; currentFrameIndex <= endFrameIndex; ++currentFrameIndex) {
        for (int tileIndex = 0; tileIndex < decoder.data.tiles.size(); ++tileIndex) {
            avifTile  tile = decoder.data.tiles.get(tileIndex);
            if (currentFrameIndex >= tile.input.samples.size()) {
                return avifResult.AVIF_RESULT_NO_IMAGES_REMAINING;
            }

            avifDecodeSample  sample = tile.input.samples.get(currentFrameIndex);
            avifExtent sampleExtent = new avifExtent();
            if (sample.itemID != 0) {
                // The data comes from an item. Let avifDecoderItemMaxExtent() do the heavy lifting.

                avifDecoderItem  item = avifMetaFindItem(decoder.data.meta, sample.itemID);
                avifResult maxExtentResult = avifDecoderItemMaxExtent(item, sample, sampleExtent);
                if (maxExtentResult != avifResult.AVIF_RESULT_OK) {
                    return maxExtentResult;
                }
            } else {
                // The data likely comes from a sample table. Use the sample position directly.

                sampleExtent.offset = sample.offset;
                sampleExtent.size = sample.size;
            }

            if (sampleExtent.size > Long.MAX_VALUE - sampleExtent.offset) {
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }

            avifResult extentMergeResult = avifExtentMerge(outExtent, sampleExtent);
            if (extentMergeResult != avifResult.AVIF_RESULT_OK) {
                return extentMergeResult;
            }
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifDecoderPrepareSample(avifDecoder  decoder, avifDecodeSample  sample, int partialByteCount)
{
    if (sample.data.length == 0 || sample.partialData) {
        // This sample hasn't been read from IO or had its extents fully merged yet.

        int bytesToRead = sample.size;
        if (partialByteCount != 0 && (bytesToRead > partialByteCount)) {
            bytesToRead = partialByteCount;
        }

        if (sample.itemID != 0) {
            // The data comes from an item. Let avifDecoderItemRead() do the heavy lifting.

            avifDecoderItem  item = avifMetaFindItem(decoder.data.meta, sample.itemID);
            final byte[] /* avifROData */ itemContents = new byte[0];
            avifResult readResult = avifDecoderItemRead(item, decoder.io, itemContents, (int) sample.offset, bytesToRead);
            if (readResult != avifResult.AVIF_RESULT_OK) {
                return readResult;
            }

            // avifDecoderItemRead is guaranteed to already be persisted by either the underlying IO
            // or by mergedExtents; just reuse the buffer here.
            sample.data = itemContents;
            sample.ownsData = false;
            sample.partialData = item.partialMergedExtents;
        } else {
            // The data likely comes from a sample table. Pull the sample and make a copy if necessary.

            final byte[] /* avifROData */ sampleContents = new byte[0];
            if ((decoder.io.sizeHint > 0) && (sample.offset > decoder.io.sizeHint)) {
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            avifResult readResult = decoder.io.read(0, sample.offset, bytesToRead, sampleContents);
            if (readResult != avifResult.AVIF_RESULT_OK) {
                return readResult;
            }
            if (sampleContents.length != bytesToRead) {
                return avifResult.AVIF_RESULT_TRUNCATED_DATA;
            }

            sample.ownsData = !decoder.io.persistent;
            sample.partialData = (bytesToRead != sample.size);
            if (decoder.io.persistent) {
                sample.data = sampleContents;
            } else {
                rawdata.avifRWDataSet(sample.data, sampleContents, sampleContents.length);
            }
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderParse(avifDecoder  decoder)
{
    // An imageSizeLimit greater than AVIF_DEFAULT_IMAGE_SIZE_LIMIT and the special value of 0 to
    // disable the limit are not yet implemented.
    if ((decoder.imageSizeLimit > avif.AVIF_DEFAULT_IMAGE_SIZE_LIMIT) || (decoder.imageSizeLimit == 0)) {
        return avifResult.AVIF_RESULT_NOT_IMPLEMENTED;
    }
    if (decoder.io == null) {
        return avifResult.AVIF_RESULT_IO_NOT_SET;
    }

    // Cleanup anything lingering in the decoder
    avifDecoderCleanup(decoder);

    // -----------------------------------------------------------------------
    // Parse BMFF boxes

    decoder.data = avifDecoderDataCreate();

    avifResult parseResult = avifParse(decoder);
    if (parseResult != avifResult.AVIF_RESULT_OK) {
        return parseResult;
    }

    // Walk the decoded items (if any) and harvest ispe
    avifDecoderData data = decoder.data;
    for (int itemIndex = 0; itemIndex < data.meta.items.size(); ++itemIndex) {
        avifDecoderItem  item = data.meta.items.get(itemIndex);
        if (item.size == 0) {
            continue;
        }
        if (item.hasUnsupportedEssentialProperty) {
            // An essential property isn't supported by libavif; ignore the item.
            continue;
        }
        boolean isGrid = new String(item.type).equals("grid");
        if (new String(item.type).equals("av01") && !isGrid) {
            // probably exif or some other data
            continue;
        }

        final avifProperty  ispeProp = avifPropertyArrayFind(item.properties, "ispe");
        if (ispeProp != null) {
            item.width = ispeProp.u.ispe.width;
            item.height = ispeProp.u.ispe.height;

            if ((item.width == 0) || (item.height == 0)) {
                System.err.printf("Item ID [%u] has an invalid size [%ux%u]", item.id, item.width, item.height);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            if (item.width > (decoder.imageSizeLimit / item.height)) {
                System.err.printf("Item ID [%u] size is too large [%ux%u]", item.id, item.width, item.height);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
        } else {
            final avifProperty  auxCProp = avifPropertyArrayFind(item.properties, "auxC");
            if (auxCProp != null && isAlphaURN(auxCProp.u.auxC.auxType)) {
                if (decoder.strictFlags.contains(avifStrictFlag.AVIF_STRICT_ALPHA_ISPE_REQUIRED)) {
                    System.err.printf(
                                          "[Strict] Alpha auxiliary image item ID [%u] is missing a mandatory ispe property",
                                          item.id);
                    return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
                }
            } else {
                System.err.printf("Item ID [%u] is missing a mandatory ispe property", item.id);
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
        }
    }
    return avifDecoderReset(decoder);
}

private avifCodec avifCodecCreateInternal(avifCodecChoice choice)
{
    return avif.AvailableCodec.avifCodecCreate(choice, EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_DECODE));
}

private avifResult avifDecoderFlush(avifDecoder  decoder)
{
    avifDecoderDataResetCodec(decoder.data);

    for (int i = 0; i < decoder.data.tiles.size(); ++i) {
        avifTile  tile = decoder.data.tiles.get(i);
        tile.codec = avifCodecCreateInternal(decoder.codecChoice);
        if (tile.codec != null) {
            tile.codec.operatingPoint = tile.operatingPoint;
            tile.codec.allLayers = tile.input.allLayers;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

obu obu = new obu();

avifResult avifDecoderReset(avifDecoder  decoder)
{
    avifDecoderData data = decoder.data;
    if (data == null) {
        // Nothing to reset.
        return avifResult.AVIF_RESULT_OK;
    }

    data.colorGrid = new avifImageGrid();
    data.alphaGrid = new avifImageGrid();
    avifDecoderDataClearTiles(data);

    // Prepare / cleanup decoded image state
    decoder.image = new avifImage();
    decoder.progressiveState = avifProgressiveState.AVIF_PROGRESSIVE_STATE_UNAVAILABLE;
    data.cicpSet = false;

    decoder.ioStats = new avifIOStats();

    // -----------------------------------------------------------------------
    // Build decode input

    data.sourceSampleTable = null; // Reset
    if (decoder.requestedSource == avifDecoderSource.AVIF_DECODER_SOURCE_AUTO) {
        // Honor the major brand (avif or avis) if present, otherwise prefer avis (tracks) if possible.
        String majorBrand = new String(data.majorBrand);
        if (majorBrand.equals("avis")) {
            data.source = avifDecoderSource.AVIF_DECODER_SOURCE_TRACKS;
        } else if (majorBrand.equals("avif")) {
            data.source = avifDecoderSource.AVIF_DECODER_SOURCE_PRIMARY_ITEM;
        } else if (data.tracks.size() > 0) {
            data.source = avifDecoderSource.AVIF_DECODER_SOURCE_TRACKS;
        } else {
            data.source = avifDecoderSource.AVIF_DECODER_SOURCE_PRIMARY_ITEM;
        }
    } else {
        data.source = decoder.requestedSource;
    }

    List<avifProperty>  colorProperties;
    if (data.source == avifDecoderSource.AVIF_DECODER_SOURCE_TRACKS) {
        avifTrack  colorTrack = null;
        avifTrack  alphaTrack = null;

        // Find primary track - this probably needs some better detection
        int colorTrackIndex = 0;
        for (; colorTrackIndex < data.tracks.size(); ++colorTrackIndex) {
            avifTrack  track = data.tracks.get(colorTrackIndex);
            if (track.sampleTable == null) {
                continue;
            }
            if (track.id == 0) { // trak box might be missing a tkhd box inside, skip it
                continue;
            }
            if (track.sampleTable.chunks.size() == 0) {
                continue;
            }
            if (!avifSampleTableHasFormat(track.sampleTable, "av01")) {
                continue;
            }
            if (track.auxForID != 0) {
                continue;
            }

            // Found one!
            break;
        }
        if (colorTrackIndex == data.tracks.size()) {
            System.err.printf("Failed to find AV1 color track");
            return avifResult.AVIF_RESULT_NO_CONTENT;
        }
        colorTrack = data.tracks.get(colorTrackIndex);

        colorProperties = avifSampleTableGetProperties(colorTrack.sampleTable);
        if (colorProperties == null) {
            System.err.printf("Failed to find AV1 color track's color properties");
            return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
        }

        // Find Exif and/or XMP metadata, if any
        if (colorTrack.meta != null) {
            // See the comment above avifDecoderFindMetadata() for the explanation of using 0 here
            avifResult findResult = avifDecoderFindMetadata(decoder, colorTrack.meta, decoder.image, 0);
            if (findResult != avifResult.AVIF_RESULT_OK) {
                return findResult;
            }
        }

        int alphaTrackIndex = 0;
        for (; alphaTrackIndex < data.tracks.size(); ++alphaTrackIndex) {
            avifTrack  track = data.tracks.get(alphaTrackIndex);
            if (track.sampleTable == null) {
                continue;
            }
            if (track.id == 0) {
                continue;
            }
            if (track.sampleTable.chunks.size() == 0) {
                continue;
            }
            if (!avifSampleTableHasFormat(track.sampleTable, "av01")) {
                continue;
            }
            if (track.auxForID == colorTrack.id) {
                // Found it!
                break;
            }
        }
        if (alphaTrackIndex != data.tracks.size()) {
            alphaTrack = data.tracks.get(alphaTrackIndex);
        }

        avifTile  colorTile = avifDecoderDataCreateTile(data, colorTrack.width, colorTrack.height, null); // No way to set operating point via tracks
        if (colorTile == null) {
            return avifResult.AVIF_RESULT_OUT_OF_MEMORY;
        }
        if (!avifCodecDecodeInputFillFromSampleTable(colorTile.input,
                                                     colorTrack.sampleTable,
                                                     decoder.imageCountLimit,
                                                     decoder.io.sizeHint)) {
            return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
        }
        data.colorTileCount = 1;

        if (alphaTrack == null) {
            avifTile  alphaTile = avifDecoderDataCreateTile(data, alphaTrack.width, alphaTrack.height, null); // No way to set operating point via tracks
            if (alphaTile == null) {
                return avifResult.AVIF_RESULT_OUT_OF_MEMORY;
            }
            if (!avifCodecDecodeInputFillFromSampleTable(alphaTile.input,
                                                         alphaTrack.sampleTable,
                                                         decoder.imageCountLimit,
                                                         decoder.io.sizeHint)) {
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            alphaTile.input.alpha = true;
            data.alphaTileCount = 1;
        }

        // Stash off sample table for future timing information
        data.sourceSampleTable = colorTrack.sampleTable;

        // Image sequence timing
        decoder.imageIndex = -1;
        decoder.imageCount = colorTile.input.samples.size();
        decoder.timescale = colorTrack.mediaTimescale;
        decoder.durationInTimescales = colorTrack.mediaDuration;
        if (colorTrack.mediaTimescale != 0) {
            decoder.duration = (double)decoder.durationInTimescales / (double)colorTrack.mediaTimescale;
        } else {
            decoder.duration = 0;
        }
        decoder.imageTiming = new avifImageTiming(); // to be set in avifDecoderNextImage()

        decoder.image.width = colorTrack.width;
        decoder.image.height = colorTrack.height;
        decoder.alphaPresent = (alphaTrack != null);
        decoder.image.alphaPremultiplied = decoder.alphaPresent && (colorTrack.premByID == alphaTrack.id);
    } else {
        // Create from items

        avifDecoderItem  colorItem = null;
        avifDecoderItem  alphaItem = null;

        if (data.meta.primaryItemID == 0) {
            // A primary item is required
            System.err.printf("Primary item not specified");
            return avifResult.AVIF_RESULT_NO_AV1_ITEMS_FOUND;
        }

        // Find the colorOBU (primary) item
        for (int itemIndex = 0; itemIndex < data.meta.items.size(); ++itemIndex) {
            avifDecoderItem  item = data.meta.items.get(itemIndex);
            if (item.size == 0) {
                continue;
            }
            if (item.hasUnsupportedEssentialProperty) {
                // An essential property isn't supported by libavif; ignore the item.
                continue;
            }
            boolean isGrid = new String(item.type).equals("grid");
            if (new String(item.type).equals("av01") && !isGrid) {
                // probably exif or some other data
                continue;
            }
            if (item.thumbnailForID != 0) {
                // It's a thumbnail, skip it
                continue;
            }
            if (item.id != data.meta.primaryItemID) {
                // This is not the primary item, skip it
                continue;
            }

            if (isGrid) {
                final byte[] /* avifROData */ readData = new byte[0];
                avifResult readResult = avifDecoderItemRead(item, decoder.io, readData, 0, 0);
                if (readResult != avifResult.AVIF_RESULT_OK) {
                    return readResult;
                }
                if (!avifParseImageGridBox(data.colorGrid, readData, readData.length, decoder.imageSizeLimit)) {
                    return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
                }
            }

            colorItem = item;
            break;
        }

        if (colorItem == null) {
            System.err.printf("Primary item not found");
            return avifResult.AVIF_RESULT_NO_AV1_ITEMS_FOUND;
        }
        colorProperties = colorItem.properties;

        // Find the alphaOBU item, if any
        for (int itemIndex = 0; itemIndex < data.meta.items.size(); ++itemIndex) {
            avifDecoderItem  item = data.meta.items.get(itemIndex);
            if (item.size == 0) {
                continue;
            }
            if (item.hasUnsupportedEssentialProperty) {
                // An essential property isn't supported by libavif; ignore the item.
                continue;
            }
            boolean isGrid = new String(item.type).equals("grid");
            if (new String(item.type).equals("av01") && !isGrid) {
                // probably exif or some other data
                continue;
            }

            // Is this an alpha auxiliary item of whatever we chose for colorItem?
            final avifProperty  auxCProp = avifPropertyArrayFind(item.properties, "auxC");
            if (auxCProp != null && isAlphaURN(auxCProp.u.auxC.auxType) && (item.auxForID == colorItem.id)) {
                if (isGrid) {
                    final byte[] /* avifROData */ readData = new byte[0]; // TODO
                    avifResult readResult = avifDecoderItemRead(item, decoder.io, readData, 0, 0);
                    if (readResult != avifResult.AVIF_RESULT_OK) {
                        return readResult;
                    }
                    if (!avifParseImageGridBox(data.alphaGrid, readData, readData.length, decoder.imageSizeLimit)) {
                        return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
                    }
                }

                alphaItem = item;
                break;
            }
        }

        // Find Exif and/or XMP metadata, if any
        avifResult findResult = avifDecoderFindMetadata(decoder, data.meta, decoder.image, colorItem.id);
        if (findResult != avifResult.AVIF_RESULT_OK) {
            return findResult;
        }

        // Set all counts and timing to safe-but-uninteresting values
        decoder.imageIndex = -1;
        decoder.imageCount = 1;
        decoder.imageTiming.timescale = 1;
        decoder.imageTiming.pts = 0;
        decoder.imageTiming.ptsInTimescales = 0;
        decoder.imageTiming.duration = 1;
        decoder.imageTiming.durationInTimescales = 1;
        decoder.timescale = 1;
        decoder.duration = 1;
        decoder.durationInTimescales = 1;

        if ((data.colorGrid.rows > 0) && (data.colorGrid.columns > 0)) {
            if (!avifDecoderGenerateImageGridTiles(decoder, data.colorGrid, colorItem, false)) {
                return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
            }
            data.colorTileCount = data.tiles.size();
        } else {
            if (colorItem.size == 0) {
                return avifResult.AVIF_RESULT_NO_AV1_ITEMS_FOUND;
            }

            avifTile  colorTile =
                avifDecoderDataCreateTile(data, colorItem.width, colorItem.height, avifDecoderItemOperatingPoint(colorItem));
            if (colorTile == null) {
                return avifResult.AVIF_RESULT_OUT_OF_MEMORY;
            }
            if (!avifCodecDecodeInputFillFromDecoderItem(colorTile.input,
                                                         colorItem,
                                                         decoder.allowProgressive,
                                                         decoder.imageCountLimit,
                                                         decoder.io.sizeHint)) {
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
            data.colorTileCount = 1;

            if (colorItem.progressive) {
                decoder.progressiveState = avifProgressiveState.AVIF_PROGRESSIVE_STATE_AVAILABLE;
                if (colorTile.input.samples.size() > 1) {
                    decoder.progressiveState = avifProgressiveState.AVIF_PROGRESSIVE_STATE_ACTIVE;
                    decoder.imageCount = colorTile.input.samples.size();
                }
            }
        }

        if (alphaItem != null) {
            if (alphaItem.width == 0 && alphaItem.height == 0) {
                // NON-STANDARD: Alpha subimage does not have an ispe property; adopt width/height from color item
                assert !decoder.strictFlags.contains(avifStrictFlag.AVIF_STRICT_ALPHA_ISPE_REQUIRED);
                alphaItem.width = colorItem.width;
                alphaItem.height = colorItem.height;
            }

            if ((data.alphaGrid.rows > 0) && (data.alphaGrid.columns > 0)) {
                if (!avifDecoderGenerateImageGridTiles(decoder, data.alphaGrid, alphaItem, true)) {
                    return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
                }
                data.alphaTileCount = data.tiles.size() - data.colorTileCount;
            } else {
                if (alphaItem.size == 0) {
                    return avifResult.AVIF_RESULT_NO_AV1_ITEMS_FOUND;
                }

                avifTile  alphaTile =
                    avifDecoderDataCreateTile(data, alphaItem.width, alphaItem.height, avifDecoderItemOperatingPoint(alphaItem));
                if (alphaTile == null) {
                    return avifResult.AVIF_RESULT_OUT_OF_MEMORY;
                }
                if (!avifCodecDecodeInputFillFromDecoderItem(alphaTile.input,
                                                             alphaItem,
                                                             decoder.allowProgressive,
                                                             decoder.imageCountLimit,
                                                             decoder.io.sizeHint)) {
                    return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
                }
                alphaTile.input.alpha = true;
                data.alphaTileCount = 1;
            }
        }

        decoder.ioStats.colorOBUSize = colorItem.size;
        decoder.ioStats.alphaOBUSize = alphaItem != null ? alphaItem.size : 0;

        decoder.image.width = colorItem.width;
        decoder.image.height = colorItem.height;
        decoder.alphaPresent = (alphaItem != null);
        decoder.image.alphaPremultiplied = decoder.alphaPresent && (colorItem.premByID == alphaItem.id);

        avifResult colorItemValidationResult = avifDecoderItemValidateAV1(colorItem, decoder.strictFlags);
        if (colorItemValidationResult != avifResult.AVIF_RESULT_OK) {
            return colorItemValidationResult;
        }
        if (alphaItem != null) {
            avifResult alphaItemValidationResult = avifDecoderItemValidateAV1(alphaItem, decoder.strictFlags);
            if (alphaItemValidationResult != avifResult.AVIF_RESULT_OK) {
                return alphaItemValidationResult;
            }
        }
    }

    // Sanity check tiles
    for (int tileIndex = 0; tileIndex < data.tiles.size(); ++tileIndex) {
        avifTile  tile = data.tiles.get(tileIndex);
        for (int sampleIndex = 0; sampleIndex < tile.input.samples.size(); ++sampleIndex) {
            avifDecodeSample  sample = tile.input.samples.get(sampleIndex);
            if (sample.size == 0) {
                // Every sample must have some data
                return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
            }
        }
    }

    // Find and adopt all colr boxes "at most one for a given value of colour type" (HEIF 6.5.5.1, from Amendment 3)
    // Accept one of each type, and bail out if more than one of a given type is provided.
    boolean colrICCSeen = false;
    boolean colrNCLXSeen = false;
    for (int propertyIndex = 0; propertyIndex < colorProperties.size(); ++propertyIndex) {
        avifProperty  prop = colorProperties.get(propertyIndex);

        if (prop.type.equals("colr")) {
            if (prop.u.colr.hasICC) {
                if (colrICCSeen) {
                    return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
                }
                colrICCSeen = true;
                decoder.image.avifImageSetProfileICC(prop.u.colr.icc, prop.u.colr.iccSize);
            }
            if (prop.u.colr.hasNCLX) {
                if (colrNCLXSeen) {
                    return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
                }
                colrNCLXSeen = true;
                data.cicpSet = true;
                decoder.image.colorPrimaries = prop.u.colr.colorPrimaries;
                decoder.image.transferCharacteristics = prop.u.colr.transferCharacteristics;
                decoder.image.matrixCoefficients = prop.u.colr.matrixCoefficients;
                decoder.image.yuvRange = prop.u.colr.range;
            }
        }
    }

    // Transformations
    final avifProperty  paspProp = avifPropertyArrayFind(colorProperties, "pasp");
    if (paspProp != null) {
        decoder.image.transformFlags.add(avifTransformFlag.AVIF_TRANSFORM_PASP);
        decoder.image.pasp = paspProp.u.pasp;
    }
    final avifProperty  clapProp = avifPropertyArrayFind(colorProperties, "clap");
    if (clapProp != null) {
        decoder.image.transformFlags.add(avifTransformFlag.AVIF_TRANSFORM_CLAP);
        decoder.image.clap = clapProp.u.clap;
    }
    final avifProperty  irotProp = avifPropertyArrayFind(colorProperties, "irot");
    if (irotProp != null) {
        decoder.image.transformFlags.add(avifTransformFlag.AVIF_TRANSFORM_IROT);
        decoder.image.irot = irotProp.u.irot;
    }
    final avifProperty  imirProp = avifPropertyArrayFind(colorProperties, "imir");
    if (imirProp != null) {
        decoder.image.transformFlags.add(avifTransformFlag.AVIF_TRANSFORM_IMIR);
        decoder.image.imir = imirProp.u.imir;
    }

    if (!data.cicpSet && (data.tiles.size() > 0)) {
        avifTile  firstTile = data.tiles.get(0);
        if (firstTile.input.samples.size() > 0) {
            avifDecodeSample  sample = firstTile.input.samples.get(0);

            // Harvest CICP from the AV1's sequence header, which should be very close to the front
            // of the first sample. Read in successively larger chunks until we successfully parse the sequence.
            final int searchSampleChunkIncrement = 64;
            final int searchSampleSizeMax = 4096;
            int searchSampleSize = 0;
            do {
                searchSampleSize += searchSampleChunkIncrement;
                if (searchSampleSize > sample.size) {
                    searchSampleSize = sample.size;
                }

                avifResult prepareResult = avifDecoderPrepareSample(decoder, sample, searchSampleSize);
                if (prepareResult != avifResult.AVIF_RESULT_OK) {
                    return prepareResult;
                }

                avifSequenceHeader sequenceHeader = new avifSequenceHeader();
                if (obu.avifSequenceHeaderParse(sequenceHeader, sample.data)) {
                    data.cicpSet = true;
                    decoder.image.colorPrimaries = sequenceHeader.colorPrimaries;
                    decoder.image.transferCharacteristics = sequenceHeader.transferCharacteristics;
                    decoder.image.matrixCoefficients = sequenceHeader.matrixCoefficients;
                    decoder.image.yuvRange = sequenceHeader.range;
                    break;
                }
            } while (searchSampleSize != sample.size && searchSampleSize < searchSampleSizeMax);
        }
    }

    final avifProperty  av1CProp = avifPropertyArrayFind(colorProperties, "av1C");
    if (av1CProp != null) {
        decoder.image.depth = avifCodecConfigurationBoxGetDepth(av1CProp.u.av1C);
        if (av1CProp.u.av1C.monochrome != 0) {
            decoder.image.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400;
        } else {
            if (av1CProp.u.av1C.chromaSubsamplingX != 0 && av1CProp.u.av1C.chromaSubsamplingY != 0) {
                decoder.image.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420;
            } else if (av1CProp.u.av1C.chromaSubsamplingX != 0) {
                decoder.image.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV422;

            } else {
                decoder.image.yuvFormat = avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444;
            }
        }
        decoder.image.yuvChromaSamplePosition = avifChromaSamplePosition.valueOf(av1CProp.u.av1C.chromaSamplePosition);
    } else {
        // An av1C box is mandatory in all valid AVIF configurations. Bail out.
        return avifResult.AVIF_RESULT_BMFF_PARSE_FAILED;
    }

    return avifDecoderFlush(decoder);
}

private avifResult avifDecoderPrepareTiles(avifDecoder  decoder,
                                          int nextImageIndex,
                                          int firstTileIndex,
                                          int tileCount,
                                          int decodedTileCount)
{
    for (int tileIndex = decodedTileCount; tileIndex < tileCount; ++tileIndex) {
        avifTile  tile = decoder.data.tiles.get(firstTileIndex + tileIndex);

        // Ensure there's an AV1 codec available before doing anything else
        if (tile.codec == null) {
            return avifResult.AVIF_RESULT_NO_CODEC_AVAILABLE;
        }

        if (nextImageIndex >= tile.input.samples.size()) {
            return avifResult.AVIF_RESULT_NO_IMAGES_REMAINING;
        }

        avifDecodeSample  sample = tile.input.samples.get(nextImageIndex);
        avifResult prepareResult = avifDecoderPrepareSample(decoder, sample, 0);
        if (prepareResult != avifResult.AVIF_RESULT_OK) {
            return prepareResult;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

private avifResult avifDecoderDecodeTiles(avifDecoder  decoder,
                                         int nextImageIndex,
                                         int firstTileIndex,
                                         int tileCount,
                                         int decodedTileCount)
{
    final int oldDecodedTileCount = decodedTileCount;
    for (int tileIndex = oldDecodedTileCount; tileIndex < tileCount; ++tileIndex) {
        avifTile  tile = decoder.data.tiles.get(firstTileIndex + tileIndex);

        final avifDecodeSample  sample = tile.input.samples.get(nextImageIndex);
        if (sample.data == null) {
            assert(decoder.allowIncremental);
            // Data is missing but there is no error yet. Output available pixel rows.
            return avifResult.AVIF_RESULT_OK;
        }

        if (!tile.codec.getNextImage(decoder, sample, tile.input.alpha, tile.image)) {
            System.err.printf("tile.codec.getNextImage() failed");
            return tile.input.alpha ? avifResult.AVIF_RESULT_DECODE_ALPHA_FAILED : avifResult.AVIF_RESULT_DECODE_COLOR_FAILED;
        }

        // Scale the decoded image so that it corresponds to this tile's output dimensions
        if ((tile.width != tile.image.width) || (tile.height != tile.image.height)) {
            if (!scale.avifImageScale(tile.image, tile.width, tile.height, decoder.imageSizeLimit)) {
                System.err.printf("avifImageScale() failed");
                return tile.input.alpha ? avifResult.AVIF_RESULT_DECODE_ALPHA_FAILED : avifResult.AVIF_RESULT_DECODE_COLOR_FAILED;
            }
        }

        ++decodedTileCount;
    }
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderNextImage(avifDecoder  decoder)
{
    if (decoder.data == null) {
        // Nothing has been parsed yet
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    if (decoder.io == null) {
        return avifResult.AVIF_RESULT_IO_NOT_SET;
    }

    if ((decoder.data.decodedColorTileCount == decoder.data.colorTileCount) &&
        (decoder.data.decodedAlphaTileCount == decoder.data.alphaTileCount)) {
        // A frame was decoded during the last avifDecoderNextImage() call.
        decoder.data.decodedColorTileCount = 0;
        decoder.data.decodedAlphaTileCount = 0;
    }

    assert(decoder.data.tiles.size() == (decoder.data.colorTileCount + decoder.data.alphaTileCount));
    final int nextImageIndex = decoder.imageIndex + 1;
    final int firstColorTileIndex = 0;
    final int firstAlphaTileIndex = decoder.data.colorTileCount;

    // Acquire all sample data for the current image first, allowing for any read call to bail out
    // with avifResult.AVIF_RESULT_WAITING_ON_IO harmlessly / idempotently, unless decoder.allowIncremental.
    // Start with color tiles.
    final avifResult prepareColorTileResult =
        avifDecoderPrepareTiles(decoder, nextImageIndex, firstColorTileIndex, decoder.data.colorTileCount, decoder.data.decodedColorTileCount);
    if ((prepareColorTileResult != avifResult.AVIF_RESULT_OK) &&
        (!decoder.allowIncremental || (prepareColorTileResult != avifResult.AVIF_RESULT_WAITING_ON_IO))) {
        return prepareColorTileResult;
    }
    // Do the same with alpha tiles. They are handled separately because their
    // order of appearance relative to the color tiles in the bitstream is left
    // to the encoder's choice, and decoding as many as possible of each
    // category in parallel is beneficial for incremental decoding, as pixel
    // rows need all channels to be decoded before being accessible to the user.
    final avifResult prepareAlphaTileResult =
        avifDecoderPrepareTiles(decoder, nextImageIndex, firstAlphaTileIndex, decoder.data.alphaTileCount, decoder.data.decodedAlphaTileCount);
    if ((prepareAlphaTileResult != avifResult.AVIF_RESULT_OK) &&
        (!decoder.allowIncremental || (prepareAlphaTileResult != avifResult.AVIF_RESULT_WAITING_ON_IO))) {
        return prepareAlphaTileResult;
    }

    // Decode all available color tiles now, then all available alpha tiles.
    final int oldDecodedColorTileCount = decoder.data.decodedColorTileCount;
    final avifResult decodeColorTileResult =
        avifDecoderDecodeTiles(decoder, nextImageIndex, firstColorTileIndex, decoder.data.colorTileCount, decoder.data.decodedColorTileCount);
    if (decodeColorTileResult != avifResult.AVIF_RESULT_OK) {
        return decodeColorTileResult;
    }
    final int oldDecodedAlphaTileCount = decoder.data.decodedAlphaTileCount;
    final avifResult decodeAlphaTileResult =
        avifDecoderDecodeTiles(decoder, nextImageIndex, firstAlphaTileIndex, decoder.data.alphaTileCount, decoder.data.decodedAlphaTileCount);
    if (decodeAlphaTileResult != avifResult.AVIF_RESULT_OK) {
        return decodeAlphaTileResult;
    }

    if (decoder.data.decodedColorTileCount > oldDecodedColorTileCount) {
        // There is at least one newly decoded color tile.
        if ((decoder.data.colorGrid.rows > 0) && (decoder.data.colorGrid.columns > 0)) {
            assert(decoder.data.colorTileCount == (decoder.data.colorGrid.rows * decoder.data.colorGrid.columns));
            if (!avifDecoderDataFillImageGrid(decoder.data,
                                              decoder.data.colorGrid,
                                              decoder.image,
                                              firstColorTileIndex,
                                              oldDecodedColorTileCount,
                                              decoder.data.decodedColorTileCount,
                                              false)) {
                return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
            }
        } else {
            // Normal (most common) non-grid path. Just steal the planes from the only "tile".
            assert(decoder.data.colorTileCount == 1);
            avifImage  srcColor = decoder.data.tiles.get(0).image;
            if ((decoder.image.width != srcColor.width) || (decoder.image.height != srcColor.height) ||
                (decoder.image.depth != srcColor.depth)) {

                decoder.image.width = srcColor.width;
                decoder.image.height = srcColor.height;
                decoder.image.depth = srcColor.depth;
            }

            srcColor.avifImageStealPlanes(decoder.image, EnumSet.of(avifPlanesFlag.AVIF_PLANES_YUV));
        }
    }

    if (decoder.data.decodedAlphaTileCount > oldDecodedAlphaTileCount) {
        // There is at least one newly decoded alpha tile.
        if ((decoder.data.alphaGrid.rows > 0) && (decoder.data.alphaGrid.columns > 0)) {
            assert(decoder.data.alphaTileCount == (decoder.data.alphaGrid.rows * decoder.data.alphaGrid.columns));
            if (!avifDecoderDataFillImageGrid(decoder.data,
                                              decoder.data.alphaGrid,
                                              decoder.image,
                                              firstAlphaTileIndex,
                                              oldDecodedAlphaTileCount,
                                              decoder.data.decodedAlphaTileCount,
                                              true)) {
                return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
            }
        } else {
            // Normal (most common) non-grid path. Just steal the planes from the only "tile".
            assert(decoder.data.alphaTileCount == 1);
            avifImage  srcAlpha = decoder.data.tiles.get(decoder.data.colorTileCount).image;
            if ((decoder.image.width != srcAlpha.width) || (decoder.image.height != srcAlpha.height) ||
                (decoder.image.depth != srcAlpha.depth)) {
                System.err.printf("decoder.image does not match srcAlpha in width, height, or bit depth");
                return avifResult.AVIF_RESULT_DECODE_ALPHA_FAILED;
            }

            srcAlpha.avifImageStealPlanes(decoder.image, EnumSet.of(avifPlanesFlag.AVIF_PLANES_A));
            decoder.image.alphaRange = srcAlpha.alphaRange;
        }
    }

    if ((decoder.data.decodedColorTileCount != decoder.data.colorTileCount) ||
        (decoder.data.decodedAlphaTileCount != decoder.data.alphaTileCount)) {
        assert(decoder.allowIncremental);
        // The image is not completely decoded. There should be no error unrelated to missing bytes,
        // and at least some missing bytes.
        assert((prepareColorTileResult == avifResult.AVIF_RESULT_OK) || (prepareColorTileResult == avifResult.AVIF_RESULT_WAITING_ON_IO));
        assert((prepareAlphaTileResult == avifResult.AVIF_RESULT_OK) || (prepareAlphaTileResult == avifResult.AVIF_RESULT_WAITING_ON_IO));
        assert((prepareColorTileResult != avifResult.AVIF_RESULT_OK) || (prepareAlphaTileResult != avifResult.AVIF_RESULT_OK));
        // Return the "not enough bytes" status now instead of moving on to the next frame.
        return avifResult.AVIF_RESULT_WAITING_ON_IO;
    }
    assert((prepareColorTileResult == avifResult.AVIF_RESULT_OK) && (prepareAlphaTileResult == avifResult.AVIF_RESULT_OK));

    // Only advance decoder.imageIndex once the image is completely decoded, so that
    // avifDecoderNthImage(decoder, decoder.imageIndex + 1) is equivalent to avifDecoderNextImage(decoder)
    // if the previous call to avifDecoderNextImage() returned avifResult.AVIF_RESULT_WAITING_ON_IO.
    decoder.imageIndex = nextImageIndex;
    // The decoded tile counts will be reset to 0 the next time avifDecoderNextImage() is called,
    // for avifDecoderDecodedRowCount() to work until then.
    if (decoder.data.sourceSampleTable != null) {
        // Decoding from a track! Provide timing information.

        avifResult timingResult = avifDecoderNthImageTiming(decoder, decoder.imageIndex, decoder.imageTiming);
        if (timingResult != avifResult.AVIF_RESULT_OK) {
            return timingResult;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderNthImageTiming(final avifDecoder  decoder, int frameIndex, avifImageTiming outTiming)
{
    if (decoder.data == null) {
        // Nothing has been parsed yet
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    if ((frameIndex > Integer.MAX_VALUE) || (frameIndex >= decoder.imageCount)) {
        // Impossible index
        return avifResult.AVIF_RESULT_NO_IMAGES_REMAINING;
    }

    if (decoder.data.sourceSampleTable == null) {
        // There isn't any real timing associated with this decode, so
        // just hand back the defaults chosen in avifDecoderReset().
        outTiming = decoder.imageTiming;
        return avifResult.AVIF_RESULT_OK;
    }

    outTiming.timescale = decoder.timescale;
    outTiming.ptsInTimescales = 0;
    for (int imageIndex = 0; imageIndex < frameIndex; ++imageIndex) {
        outTiming.ptsInTimescales += avifSampleTableGetImageDelta(decoder.data.sourceSampleTable, imageIndex);
    }
    outTiming.durationInTimescales = avifSampleTableGetImageDelta(decoder.data.sourceSampleTable, frameIndex);

    if (outTiming.timescale > 0) {
        outTiming.pts = (double)outTiming.ptsInTimescales / (double)outTiming.timescale;
        outTiming.duration = (double)outTiming.durationInTimescales / (double)outTiming.timescale;
    } else {
        outTiming.pts = 0.0;
        outTiming.duration = 0.0;
    }
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderNthImage(avifDecoder  decoder, int frameIndex)
{
    if (decoder.data == null) {
        // Nothing has been parsed yet
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    if ((frameIndex > Integer.MAX_VALUE) || (frameIndex >= decoder.imageCount)) {
        // Impossible index
        return avifResult.AVIF_RESULT_NO_IMAGES_REMAINING;
    }

    int requestedIndex = frameIndex;
    if (requestedIndex == (decoder.imageIndex + 1)) {
        // It's just the next image (already partially decoded or not at all), nothing special here
        return avifDecoderNextImage(decoder);
    }

    if (requestedIndex == decoder.imageIndex) {
        if ((decoder.data.decodedColorTileCount == decoder.data.colorTileCount) &&
            (decoder.data.decodedAlphaTileCount == decoder.data.alphaTileCount)) {
            // The current fully decoded image (decoder.imageIndex) is requested, nothing to do
            return avifResult.AVIF_RESULT_OK;
        }
        // The next image (decoder.imageIndex + 1) is partially decoded but
        // the previous image (decoder.imageIndex) is requested.
        // Fall through to flush and start decoding from the nearest key frame.
    }

    int nearestKeyFrame = avifDecoderNearestKeyframe(decoder, frameIndex);
    if ((nearestKeyFrame > (decoder.imageIndex + 1)) || (requestedIndex <= decoder.imageIndex)) {
        // If we get here, a decoder flush is necessary
        decoder.imageIndex = nearestKeyFrame - 1; // prepare to read nearest keyframe
        avifDecoderFlush(decoder);
    }
    for (;;) {
        avifResult result = avifDecoderNextImage(decoder);
        if (result != avifResult.AVIF_RESULT_OK) {
            return result;
        }

        if (requestedIndex == decoder.imageIndex) {
            break;
        }
    }
    return avifResult.AVIF_RESULT_OK;
}

boolean avifDecoderIsKeyframe(final avifDecoder  decoder, int frameIndex)
{
    if (decoder.data == null || (decoder.data.tiles.size() == 0)) {
        // Nothing has been parsed yet
        return false;
    }

    // *All* tiles for the requested frameIndex must be keyframes in order for
    //  avifDecoderIsKeyframe() to return true, otherwise we may seek to a frame in which the color
    //  planes are a keyframe but the alpha plane isn't a keyframe, which will cause an alpha plane
    //  decode failure.
    for (int i = 0; i < decoder.data.tiles.size(); ++i) {
        final avifTile  tile = decoder.data.tiles.get(i);
        if ((frameIndex >= tile.input.samples.size()) || !tile.input.samples.get(frameIndex).sync) {
            return false;
        }
    }
    return true;
}

int avifDecoderNearestKeyframe(final avifDecoder  decoder, int frameIndex)
{
    if (decoder.data == null) {
        // Nothing has been parsed yet
        return 0;
    }

    for (; frameIndex != 0; --frameIndex) {
        if (avifDecoderIsKeyframe(decoder, frameIndex)) {
            break;
        }
    }
    return frameIndex;
}

// Returns the number of available rows in decoder.image given a color or alpha subimage.
private int avifGetDecodedRowCount(final avifDecoder  decoder,
                                       final avifImageGrid  grid,
                                       int firstTileIndex,
                                       int tileCount,
                                       int decodedTileCount)
{
    if (decodedTileCount == tileCount) {
        return decoder.image.height;
    }
    if (decodedTileCount == 0) {
        return 0;
    }

    if ((grid.rows > 0) && (grid.columns > 0)) {
        // Grid of AVIF tiles (not to be confused with AV1 tiles).
        final int tileHeight = decoder.data.tiles.get(firstTileIndex).height;
        return Math.min((decodedTileCount / grid.columns) * tileHeight, decoder.image.height);
    } else {
        // Non-grid image.
        return decoder.image.height;
    }
}

int avifDecoderDecodedRowCount(final avifDecoder  decoder)
{
    final int colorRowCount = avifGetDecodedRowCount(decoder,
                                                          decoder.data.colorGrid,
                                                          /*firstTileIndex=*/0,
                                                          decoder.data.colorTileCount,
                                                          decoder.data.decodedColorTileCount);
    final int alphaRowCount = avifGetDecodedRowCount(decoder,
                                                          decoder.data.alphaGrid,
                                                          /*firstTileIndex=*/decoder.data.colorTileCount,
                                                          decoder.data.alphaTileCount,
                                                          decoder.data.decodedAlphaTileCount);
    return Math.min(colorRowCount, alphaRowCount);
}

avifResult avifDecoderRead(avifDecoder  decoder, avifImage  image)
{
    avifResult result = avifDecoderParse(decoder);
    if (result != avifResult.AVIF_RESULT_OK) {
        return result;
    }
    result = avifDecoderNextImage(decoder);
    if (result != avifResult.AVIF_RESULT_OK) {
        return result;
    }
    decoder.image.avifImageCopy(image, EnumSet.of(avifPlanesFlag.AVIF_PLANES_ALL));
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifDecoderReadMemory(avifDecoder  decoder, avifImage  image, final byte[] data, int size)
{
    avifResult result = avifDecoderSetIOMemory(decoder, data, size);
    if (result != avifResult.AVIF_RESULT_OK) {
        return result;
    }
    return avifDecoderRead(decoder, image);
}

avifResult avifDecoderReadFile(avifDecoder  decoder, avifImage  image, final String filename)
{
    avifResult result = avifDecoderSetIOFile(decoder, filename);
    if (result != avifResult.AVIF_RESULT_OK) {
        return result;
    }
    return avifDecoderRead(decoder, image);
}
}