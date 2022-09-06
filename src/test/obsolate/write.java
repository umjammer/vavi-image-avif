// Copyright 2019 Joe Drago. All rights reserved.
// SPDX-License-Identifier: BSD-2-Clause

package vavi.awt.image.avif;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

import vavi.awt.image.avif.avif.AvailableCodec;
import vavi.awt.image.avif.avif.avifAddImageFlag;
import vavi.awt.image.avif.avif.avifChannelIndex;
import vavi.awt.image.avif.avif.avifCodec;
import vavi.awt.image.avif.avif.avifCodecConfigurationBox;
import vavi.awt.image.avif.avif.avifCodecEncodeOutput;
import vavi.awt.image.avif.avif.avifCodecFlag;
import vavi.awt.image.avif.avif.avifEncodeSample;
import vavi.awt.image.avif.avif.avifEncoder;
import vavi.awt.image.avif.avif.avifImage;
import vavi.awt.image.avif.avif.avifPixelFormat;
import vavi.awt.image.avif.avif.avifPlanesFlag;
import vavi.awt.image.avif.avif.avifRange;
import vavi.awt.image.avif.avif.avifResult;
import vavi.awt.image.avif.avif.avifTransformFlag;
import vavi.awt.image.avif.obu.avifSequenceHeader;
import vavi.awt.image.avif.read.avifRWStream;
import vavi.util.ByteUtil;

class write {

    public static final int AVIF_BOX_SIZE_TBD = 0;
public static final int MAX_ASSOCIATIONS = 16;

class ipma
{
    public ipma(byte[] associations, boolean essential) {
        this.associations = associations;
        this.essential = essential;
    }
    byte[] associations;
    boolean essential;
}

// Used to store offsets in meta boxes which need to point at mdat offsets that
// aren't known yet. When an item's mdat payload is written, all registered fixups
// will have this now-known offset "fixed up".
class avifOffsetFixup
{
    int offset;
}

//AVIF_ARRAY_DECLARE(avifOffsetFixupArray, avifOffsetFixup, fixup);

private final String alphaURN = read.URN_ALPHA0;

private final String xmpContentType = read.CONTENT_TYPE_XMP;

//private boolean avifImageIsOpaque(final avifImage image);
//private void writeConfigBox(avifRWStream s, avifCodecConfigurationBox cfg);

// ---------------------------------------------------------------------------
// avifCodecEncodeOutput

avifCodecEncodeOutput avifCodecEncodeOutputCreate()
{
    avifCodecEncodeOutput encodeOutput = new avifCodecEncodeOutput();
    encodeOutput.samples = new ArrayList<>(1);
    return encodeOutput;
}

void avifCodecEncodeOutputAddSample(avifCodecEncodeOutput encodeOutput, final byte[] data, int len, boolean sync)
{
    avifEncodeSample sample = new avifEncodeSample();
    rawdata.avifRWDataSet(sample.data, data, len);
    encodeOutput.samples.add(sample);
    sample.sync = sync;
}

// ---------------------------------------------------------------------------
// avifEncoderItem

// one "item" worth for encoder
class avifEncoderItem
{
    short id;
    byte[] type = new byte[4];
    avifCodec codec;                    // only present on type==av01
    avifCodecEncodeOutput encodeOutput; // AV1 sample data
    byte[] /* avifRWData */ metadataPayload;           // Exif/XMP data
    avifCodecConfigurationBox av1C;       // Harvested in avifEncoderFinish(), if encodeOutput has samples
    int cellIndex;                   // Which row-major cell index corresponds to this item. ignored on non-av01 types
    boolean alpha;
    boolean hiddenImage; // A hidden image item has (flags & 1) equal to 1 in its ItemInfoEntry.

    String infeName;
    int infeNameSize;
    String infeContentType;
    int infeContentTypeSize;
    List<avifOffsetFixup> Fixups;

    short irefToID; // if non-zero, make an iref from this id . irefToID
    String irefType;

    int gridCols; // if non-zero (legal range [1-256]), this is a grid item
    int gridRows; // if non-zero (legal range [1-256]), this is a grid item

    short dimgFromID; // if non-zero, make an iref from dimgFromID . this id

    List<ipma> ipma;
}

//AVIF_ARRAY_DECLARE(avifEncoderItemArray, avifEncoderItem, item);

// ---------------------------------------------------------------------------
// avifEncoderFrame

class avifEncoderFrame
{
    long durationInTimescales;
}

//AVIF_ARRAY_DECLARE(avifEncoderFrameArray, avifEncoderFrame, frame);

// ---------------------------------------------------------------------------
// avifEncoderData

class avifEncoderData
{
    List<avifEncoderItem> items;
    List<avifEncoderFrame> frames;
    avifImage imageMetadata;
    short lastItemID;
    short primaryItemID;
    boolean singleImage; // if true, the AVIF_ADD_IMAGE_FLAG_SINGLE flag was set on the first call to avifEncoderAddImage()
    boolean alphaPresent;
}

//private void avifEncoderDataDestroy(avifEncoderData data);

private avifEncoderData avifEncoderDataCreate()
{
    avifEncoderData data = new avifEncoderData();
    data.imageMetadata = new avifImage();
    data.items = new ArrayList<>(8);
    data.frames = new ArrayList<>(1);
    return data;
}

private avifEncoderItem avifEncoderDataCreateItem(avifEncoderData data, final String type, final String infeName, int infeNameSize, int cellIndex)
{
    avifEncoderItem item = new avifEncoderItem();
    ++data.lastItemID;
    item.id = data.lastItemID;
    item.infeName = infeName;
    item.infeNameSize = infeNameSize;
    item.encodeOutput = avifCodecEncodeOutputCreate();
    item.cellIndex = cellIndex;
    item.Fixups = new ArrayList<>(4);
    data.items.add(item);
    return item;
}

private avifEncoderItem avifEncoderDataFindItemByID(avifEncoderData data, short id)
{
    for (int itemIndex = 0; itemIndex < data.items.size(); ++itemIndex) {
        avifEncoderItem item = data.items.get(itemIndex);
        if (item.id == id) {
            return item;
        }
    }
    return null;
}

private void avifEncoderItemAddMdatFixup(avifEncoderItem item, final DataOutputStream s)
{
    avifOffsetFixup fixup = new avifOffsetFixup();
    fixup.offset = avifRWStreamOffset(s);
    item.Fixups.add(fixup);
}

// ---------------------------------------------------------------------------
// avifItemPropertyDedup - Provides ipco deduplication

class avifItemProperty
{
    byte[] index;
    int offset;
    int size;
}
//AVIF_ARRAY_DECLARE(List<avifItemProperty>, avifItemProperty, property);

class avifItemPropertyDedup
{
    List<avifItemProperty> properties;
    DataOutputStream s;    // Temporary stream for each new property, checked against already-written boxes for deduplications
    byte[] /* avifRWData */ buffer; // Temporary storage for 's'
    byte[] nextIndex; // 1-indexed, incremented every time another unique property is finished
}

//private void avifItemPropertyDedupDestroy(avifItemPropertyDedup dedup);

private avifItemPropertyDedup avifItemPropertyDedupCreate()
{
    avifItemPropertyDedup dedup = new avifItemPropertyDedup();
    dedup.properties = new ArrayList<>(8);
    rawdata.avifRWDataRealloc(dedup.buffer, 2048); // This will resize automatically (if necessary)
    return dedup;
}

// Resets the dedup's temporary write stream in preparation for a single item property's worth of writing
private void avifItemPropertyDedupStart(avifItemPropertyDedup dedup)
{
    avifRWStreamStart(dedup.s, dedup.buffer);
}

// This compares the newly written item property (in the dedup's temporary storage buffer) to
// already-written properties (whose offsets/sizes in outputStream are recorded in the dedup). If a
// match is found, the previous item's index is used. If this new property is unique, it is
// assigned the next available property index, written to the output stream, and its offset/size in
// the output stream is recorded in the dedup for future comparisons.
//
// This function always returns a valid 1-indexed property index for usage in a property association
// (ipma) box later. If the most recent property was a duplicate of a previous property, the return
// value will be the index of the original property, otherwise it will be the index of the newly
// created property.
private byte[] avifItemPropertyDedupFinish(avifItemPropertyDedup dedup, DataOutputStream outputStream)
{
    final int newPropertySize = avifRWStreamOffset(dedup.s);

    for (int i = 0; i < dedup.properties.size(); ++i) {
        avifItemProperty property = dedup.properties.get(i);
        if ((property.size == newPropertySize) &&
            !memcmp(outputStream.raw.data[property.offset], dedup.buffer, newPropertySize)) {
            // We've already written this exact property, reuse it
            return property.index;
        }
    }

    // Write a new property, and remember its location in the output stream for future deduplication
    avifItemProperty property = new avifItemProperty();
    property.index = ++dedup.nextIndex; // preincrement so the first new index is 1 (as ipma is 1-indexed)
    property.size = newPropertySize;
    property.offset = avifRWStreamOffset(outputStream);
    outputStream.write(dedup.buffer, 0, newPropertySize);
    dedup.properties.add(property);
    return property.index;
}

// ---------------------------------------------------------------------------

avifEncoder avifEncoderCreate()
{
    avifEncoder encoder = new avifEncoder();
    encoder.maxThreads = 1;
    encoder.minQuantizer = avif.AVIF_QUANTIZER_LOSSLESS;
    encoder.maxQuantizer = avif.AVIF_QUANTIZER_LOSSLESS;
    encoder.minQuantizerAlpha = avif.AVIF_QUANTIZER_LOSSLESS;
    encoder.maxQuantizerAlpha = avif.AVIF_QUANTIZER_LOSSLESS;
    encoder.tileRowsLog2 = 0;
    encoder.tileColsLog2 = 0;
    encoder.speed = avif.AVIF_SPEED_DEFAULT;
    encoder.keyframeInterval = 0;
    encoder.timescale = 1;
    encoder.data = avifEncoderDataCreate();
    encoder.csOptions = new HashMap<>();
    return encoder;
}

void avifEncoderSetCodecSpecificOption(avifEncoder encoder, final String key, final String value)
{
    encoder.csOptions.put(key, value);
}

// This function is used in two codepaths:
// * writing color *item* properties
// * writing color *track* properties
//
// Item properties must have property associations with them and can be deduplicated (by reusing
// these associations), so this function leverages the ipma and dedup arguments to do this.
//
// Track properties, however, are implicitly associated by the track in which they are contained, so
// there is no need to build a property association box (ipma), and no way to deduplicate/reuse a
// property. In this case, the ipma and dedup properties should/will be set to null, and this
// function will avoid using them.
private void avifEncoderWriteColorProperties(DataOutputStream outputStream,
                                            final avifImage imageMetadata,
                                            List<ipma> ipma,
                                            avifItemPropertyDedup dedup) throws IOException
{
    DataOutputStream s = outputStream;
    if (dedup != null) {
        assert ipma != null;

        // Use the dedup's temporary stream for box writes
        s = dedup.s;
    }

    if (imageMetadata.icc.length > 0) {
        if (dedup != null) {
            avifItemPropertyDedupStart(dedup);
        }
        int colr = stream.avifRWStreamWriteBox(s, "colr", AVIF_BOX_SIZE_TBD);
        s.writeBytes("prof"); // unsigned int(32) colour_type;
        s.write(imageMetadata.icc, 0, imageMetadata.icc.length);
        stream.avifRWStreamFinishBox(s, colr);
        if (dedup != null) {
            ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, outputStream), false));
        }
    }

    // HEIF 6.5.5.1, from Amendment 3 allows multiple colr boxes: "at most one for a given value of colour type"
    // Therefore, *always* writing an nclx box, even if an a prof box was already written above.
    if (dedup != null) {
        avifItemPropertyDedupStart(dedup);
    }
    int colr = stream.avifRWStreamWriteBox(s, "colr", AVIF_BOX_SIZE_TBD);
    s.writeBytes("nclx");                                            // unsigned int(32) colour_type;
    s.writeShort(imageMetadata.colorPrimaries.v);                          // unsigned int(16) colour_primaries;
    s.writeShort(imageMetadata.transferCharacteristics.v);                 // unsigned int(16) transfer_characteristics;
    s.writeShort(imageMetadata.matrixCoefficients.v);                      // unsigned int(16) matrix_coefficients;
    s.writeByte((imageMetadata.yuvRange == avifRange.AVIF_RANGE_FULL) ? 0x80 : 0); // unsigned int(1) full_range_flag;
                                                                                     // unsigned int(7) reserved = 0;
    stream.avifRWStreamFinishBox(s, colr);
    if (dedup != null) {
        ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, outputStream), false));
    }

    // Write (Optional) Transformations
    if (imageMetadata.transformFlags.contains(avifTransformFlag.AVIF_TRANSFORM_PASP)) {
        if (dedup != null) {
            avifItemPropertyDedupStart(dedup);
        }
        int pasp = stream.avifRWStreamWriteBox(s, "pasp", AVIF_BOX_SIZE_TBD);
        s.writeInt(imageMetadata.pasp.hSpacing); // unsigned int(32) hSpacing;
        s.writeInt(imageMetadata.pasp.vSpacing); // unsigned int(32) vSpacing;
        stream.avifRWStreamFinishBox(s, pasp);
        if (dedup != null) {
            ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, outputStream), false));
        }
    }
    if (imageMetadata.transformFlags.contains(avifTransformFlag.AVIF_TRANSFORM_CLAP)) {
        if (dedup != null) {
            avifItemPropertyDedupStart(dedup);
        }
        int clap = stream.avifRWStreamWriteBox(s, "clap", AVIF_BOX_SIZE_TBD);
        s.writeInt(imageMetadata.clap.widthN);    // unsigned int(32) cleanApertureWidthN;
        s.writeInt(imageMetadata.clap.widthD);    // unsigned int(32) cleanApertureWidthD;
        s.writeInt(imageMetadata.clap.heightN);   // unsigned int(32) cleanApertureHeightN;
        s.writeInt(imageMetadata.clap.heightD);   // unsigned int(32) cleanApertureHeightD;
        s.writeInt(imageMetadata.clap.horizOffN); // unsigned int(32) horizOffN;
        s.writeInt(imageMetadata.clap.horizOffD); // unsigned int(32) horizOffD;
        s.writeInt(imageMetadata.clap.vertOffN);  // unsigned int(32) vertOffN;
        s.writeInt(imageMetadata.clap.vertOffD);  // unsigned int(32) vertOffD;
        stream.avifRWStreamFinishBox(s, clap);
        if (dedup != null) {
            ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, outputStream), true));
        }
    }
    if (imageMetadata.transformFlags.contains(avifTransformFlag.AVIF_TRANSFORM_IROT)) {
        if (dedup != null) {
            avifItemPropertyDedupStart(dedup);
        }
        int irot = stream.avifRWStreamWriteBox(s, "irot", AVIF_BOX_SIZE_TBD);
        byte angle = (byte) (imageMetadata.irot.angle & 0x3);
        s.writeByte(angle); // unsigned int (6) reserved = 0; unsigned int (2) angle;
        stream.avifRWStreamFinishBox(s, irot);
        if (dedup != null) {
            ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, outputStream), true));
        }
    }
    if (imageMetadata.transformFlags.contains(avifTransformFlag.AVIF_TRANSFORM_IMIR)) {
        if (dedup != null) {
            avifItemPropertyDedupStart(dedup);
        }
        int imir = stream.avifRWStreamWriteBox(s, "imir", AVIF_BOX_SIZE_TBD);
        byte mode = (byte) (imageMetadata.imir.mode & 0x1);
        s.writeByte(mode); // unsigned int (7) reserved = 0; unsigned int (1) mode;
        stream.avifRWStreamFinishBox(s, imir);
        if (dedup != null) {
            ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, outputStream), true));
        }
    }
}

// Write unassociated metadata items (EXIF, XMP) to a small meta box inside of a trak box.
// These items are implicitly associated with the track they are contained within.
private void avifEncoderWriteTrackMetaBox(avifEncoder encoder, DataOutputStream s) throws IOException
{
    // Count how many non-av01 items (such as EXIF/XMP) are being written
    int metadataItemCount = 0;
    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);
        if (Arrays.equals(item.type, "av01".getBytes())) {
            ++metadataItemCount;
        }
    }
    if (metadataItemCount == 0) {
        // Don't even bother writing the trak meta box
        return;
    }

    int meta = stream.avifRWStreamWriteFullBox(s, "meta", AVIF_BOX_SIZE_TBD, 0, 0);

    int hdlr = stream.avifRWStreamWriteFullBox(s, "hdlr", AVIF_BOX_SIZE_TBD, 0, 0);
    s.writeInt(0);              // unsigned int(32) pre_defined = 0;
    s.writeBytes("pict");    // unsigned int(32) handler_type;
    stream.avifRWStreamWriteZeros(s, 12);           // final unsigned int(32)[3] reserved = 0;
    s.writeBytes("libavif"); // string name; (writing null terminator)
    stream.avifRWStreamFinishBox(s, hdlr);

    int iloc = stream.avifRWStreamWriteFullBox(s, "iloc", AVIF_BOX_SIZE_TBD, 0, 0);
    byte offsetSizeAndLengthSize = (4 << 4) + (4 << 0); // unsigned int(4) offset_size;
                                                           // unsigned int(4) length_size;
    s.writeByte(offsetSizeAndLengthSize);     //
    stream.avifRWStreamWriteZeros(s, 1);                          // unsigned int(4) base_offset_size;
                                                           // unsigned int(4) reserved;
    s.writeShort((short)metadataItemCount);  // unsigned int(16) item_count;
    for (int trakItemIndex = 0; trakItemIndex < encoder.data.items.size(); ++trakItemIndex) {
        avifEncoderItem item = encoder.data.items.get(trakItemIndex);
        if (Arrays.equals(item.type, "av01".getBytes())) {
            // Skip over all non-metadata items
            continue;
        }

        s.writeShort(item.id);                             // unsigned int(16) item_ID;
        s.writeShort(0);                                    // unsigned int(16) data_reference_index;
        s.writeShort(1);                                    // unsigned int(16) extent_count;
        avifEncoderItemAddMdatFixup(item, s);                          //
        s.writeInt(0 /* set later */);                    // unsigned int(offset_size*8) extent_offset;
        s.writeInt(item.metadataPayload.length); // unsigned int(length_size*8) extent_length;
    }
    stream.avifRWStreamFinishBox(s, iloc);

    int iinf = stream.avifRWStreamWriteFullBox(s, "iinf", AVIF_BOX_SIZE_TBD, 0, 0);
    s.writeShort((short)metadataItemCount); //  unsigned int(16) entry_count;
    for (int trakItemIndex = 0; trakItemIndex < encoder.data.items.size(); ++trakItemIndex) {
        avifEncoderItem item = encoder.data.items.get(trakItemIndex);
        if (Arrays.equals(item.type, "av01".getBytes())) {
            continue;
        }

        assert(!item.hiddenImage);
        int infe = stream.avifRWStreamWriteFullBox(s, "infe", AVIF_BOX_SIZE_TBD, 2, 0);
        s.writeShort(item.id);                             // unsigned int(16) item_ID;
        s.writeShort(0);                                    // unsigned int(16) item_protection_index;
        s.write(item.type);                           // unsigned int(32) item_type;
        s.writeBytes(item.infeName); // string item_name; (writing null terminator)
        if (item.infeContentType != null && item.infeContentTypeSize == 0) {      // string content_type; (writing null terminator)
            s.writeBytes(item.infeContentType);
        }
        stream.avifRWStreamFinishBox(s, infe);
    }
    stream.avifRWStreamFinishBox(s, iinf);

    stream.avifRWStreamFinishBox(s, meta);
}

private void avifWriteGridPayload(byte[] /* avifRWData */ data, int gridCols, int gridRows, final avifImage firstCell) throws IOException
{
    // ISO/IEC 23008-12 6.6.2.3.2
    // aligned(8) class ImageGrid {
    //     unsigned int(8) version = 0;
    //     unsigned int(8) flags;
    //     FieldLength = ((flags & 1) + 1) * 16;
    //     unsigned int(8) rows_minus_one;
    //     unsigned int(8) columns_minus_one;
    //     unsigned int(FieldLength) output_width;
    //     unsigned int(FieldLength) output_height;
    // }

    int gridWidth = firstCell.width * gridCols;
    int gridHeight = firstCell.height * gridRows;
    byte gridFlags = (byte) (((gridWidth > 65535) || (gridHeight > 65535)) ? 1 : 0);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream s = new DataOutputStream(baos);
    s.writeByte(0);                       // unsigned int(8) version = 0;
    s.writeByte(gridFlags);               // unsigned int(8) flags;
    s.writeByte((byte)(gridRows - 1)); // unsigned int(8) rows_minus_one;
    s.writeByte((byte)(gridCols - 1)); // unsigned int(8) columns_minus_one;
    if ((gridFlags & 1) != 0) {
        s.writeInt(gridWidth);  // unsigned int(FieldLength) output_width;
        s.writeInt(gridHeight); // unsigned int(FieldLength) output_height;
    } else {
        short tmpWidth = (short)gridWidth;
        short tmpHeight = (short)gridHeight;
        s.writeShort(tmpWidth);  // unsigned int(FieldLength) output_width;
        s.writeShort(tmpHeight); // unsigned int(FieldLength) output_height;
    }
    data = baos.toByteArray(); // TODO
}

private avifResult avifEncoderAddImageInternal(avifEncoder encoder,
                                              int gridCols,
                                              int gridRows,
                                              final avifImage[] cellImages,
                                              long durationInTimescales,
                                              EnumSet<avifAddImageFlag> addImageFlags)
{
    // -----------------------------------------------------------------------
    // Verify encoding is possible

    if (AvailableCodec.avifCodecName(encoder.codecChoice, EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE)) == null) {
        return avifResult.AVIF_RESULT_NO_CODEC_AVAILABLE;
    }

    // -----------------------------------------------------------------------
    // Validate images

    final int cellCount = gridCols * gridRows;
    if (cellCount == 0) {
        return avifResult.AVIF_RESULT_INVALID_ARGUMENT;
    }

    final avifImage firstCell = cellImages[0];
    if ((firstCell.depth != 8) && (firstCell.depth != 10) && (firstCell.depth != 12)) {
        return avifResult.AVIF_RESULT_UNSUPPORTED_DEPTH;
    }

    if (firstCell.width == 0 || firstCell.height == 0) {
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    if ((cellCount > 1) && !avifAreGridDimensionsValid(firstCell.yuvFormat,
                                                       gridCols * firstCell.width,
                                                       gridRows * firstCell.height,
                                                       firstCell.width,
                                                       firstCell.height)) {
        return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
    }

    for (int cellIndex = 0; cellIndex < cellCount; ++cellIndex) {
        final avifImage cellImage = cellImages[cellIndex];
        // HEIF (ISO 23008-12:2017), Section 6.6.2.3.1:
        //   All input images shall have exactly the same width and height; call those tile_width and tile_height.
        // MIAF (ISO 23000-22:2019), Section 7.3.11.4.1:
        //   All input images of a grid image item shall use the same coding format, chroma sampling format, and the
        //   same decoder configuration (see 7.3.6.2).
        if ((cellImage.width != firstCell.width) || (cellImage.height != firstCell.height) ||
            (cellImage.depth != firstCell.depth) || (cellImage.yuvFormat != firstCell.yuvFormat) ||
            (cellImage.yuvRange != firstCell.yuvRange) || (cellImage.colorPrimaries != firstCell.colorPrimaries) ||
            (cellImage.transferCharacteristics != firstCell.transferCharacteristics) ||
            (cellImage.matrixCoefficients != firstCell.matrixCoefficients) || (cellImage.alphaRange != firstCell.alphaRange) ||
            (cellImage.alphaPlane != firstCell.alphaPlane) || (cellImage.alphaPremultiplied != firstCell.alphaPremultiplied)) {
            return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
        }

        if (cellImage.yuvPlanes[avifChannelIndex.AVIF_CHAN_Y.v] == null) {
            return avifResult.AVIF_RESULT_NO_CONTENT;
        }

        if (cellImage.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_NONE) {
            return avifResult.AVIF_RESULT_NO_YUV_FORMAT_SELECTED;
        }
    }

    // -----------------------------------------------------------------------
    // Validate flags

    if (encoder.data.singleImage) {
        // The previous call to avifEncoderAddImage() set AVIF_ADD_IMAGE_FLAG_SINGLE.
        // avifEncoderAddImage() cannot be called again for this encode.
        return avifResult.AVIF_RESULT_ENCODE_COLOR_FAILED;
    }

    if (addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)) {
        encoder.data.singleImage = true;

        if (encoder.data.items.size() > 0) {
            // AVIF_ADD_IMAGE_FLAG_SINGLE may only be set on the first and only image.
            return avifResult.AVIF_RESULT_INVALID_ARGUMENT;
        }
    }

    // -----------------------------------------------------------------------

    if (durationInTimescales == 0) {
        durationInTimescales = 1;
    }

    if (encoder.data.items.size() == 0) {
        // Make a copy of the first image's metadata (sans pixels) for future writing/validation
        firstCell.avifImageCopy(encoder.data.imageMetadata, EnumSet.noneOf(avifPlanesFlag.class));

        // Prepare all AV1 items

        short gridColorID = 0;
        if (cellCount > 1) {
            avifEncoderItem gridColorItem = avifEncoderDataCreateItem(encoder.data, "grid", "Color", 6, 0);
            avifWriteGridPayload(gridColorItem.metadataPayload, gridCols, gridRows, firstCell);
            gridColorItem.gridCols = gridCols;
            gridColorItem.gridRows = gridRows;

            gridColorID = gridColorItem.id;
            encoder.data.primaryItemID = gridColorID;
        }

        for (int cellIndex = 0; cellIndex < cellCount; ++cellIndex) {
            avifEncoderItem item = avifEncoderDataCreateItem(encoder.data, "av01", "Color", 6, cellIndex);
            item.codec = AvailableCodec.avifCodecCreate(encoder.codecChoice, EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE));
            if (item.codec == null) {
                // Just bail out early, we're not surviving this function without an encoder compiled in
                return avifResult.AVIF_RESULT_NO_CODEC_AVAILABLE;
            }
            item.codec.csOptions = encoder.csOptions;

            if (cellCount > 1) {
                item.dimgFromID = gridColorID;
                item.hiddenImage = true;
            } else {
                encoder.data.primaryItemID = item.id;
            }
        }

        encoder.data.alphaPresent = (firstCell.alphaPlane != null);
        if (encoder.data.alphaPresent && addImageFlags.contains(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)) {
            // If encoding a single image in which the alpha plane exists but is entirely opaque,
            // simply skip writing an alpha AV1 payload entirely, as it'll be interpreted as opaque
            // and is less bytes.
            //
            // However, if encoding an image sequence, the first frame's alpha plane being entirely
            // opaque could be a false positive for removing the alpha AV1 payload, as it might simply
            // be a fade out later in the sequence. This is why avifImageIsOpaque() is only called
            // when encoding a single image.

            encoder.data.alphaPresent = false;
            for (int cellIndex = 0; cellIndex < cellCount; ++cellIndex) {
                final avifImage cellImage = cellImages[cellIndex];
                if (!avifImageIsOpaque(cellImage)) {
                    encoder.data.alphaPresent = true;
                    break;
                }
            }
        }

        if (encoder.data.alphaPresent) {
            short gridAlphaID = 0;
            if (cellCount > 1) {
                avifEncoderItem gridAlphaItem = avifEncoderDataCreateItem(encoder.data, "grid", "Alpha", 6, 0);
                avifWriteGridPayload(gridAlphaItem.metadataPayload, gridCols, gridRows, firstCell);
                gridAlphaItem.alpha = true;
                gridAlphaItem.irefToID = encoder.data.primaryItemID;
                gridAlphaItem.irefType = "auxl";
                gridAlphaItem.gridCols = gridCols;
                gridAlphaItem.gridRows = gridRows;
                gridAlphaID = gridAlphaItem.id;

                if (encoder.data.imageMetadata.alphaPremultiplied) {
                    avifEncoderItem primaryItem = avifEncoderDataFindItemByID(encoder.data, encoder.data.primaryItemID);
                    assert primaryItem != null;
                    primaryItem.irefType = "prem";
                    primaryItem.irefToID = gridAlphaID;
                }
            }

            for (int cellIndex = 0; cellIndex < cellCount; ++cellIndex) {
                avifEncoderItem item = avifEncoderDataCreateItem(encoder.data, "av01", "Alpha", 6, cellIndex);
                item.codec = AvailableCodec.avifCodecCreate(encoder.codecChoice, EnumSet.of(avifCodecFlag.AVIF_CODEC_FLAG_CAN_ENCODE));
                if (item.codec == null) {
                    return avifResult.AVIF_RESULT_NO_CODEC_AVAILABLE;
                }
                item.codec.csOptions = encoder.csOptions;
                item.alpha = true;

                if (cellCount > 1) {
                    item.dimgFromID = gridAlphaID;
                    item.hiddenImage = true;
                } else {
                    item.irefToID = encoder.data.primaryItemID;
                    item.irefType = "auxl";

                    if (encoder.data.imageMetadata.alphaPremultiplied) {
                        avifEncoderItem primaryItem = avifEncoderDataFindItemByID(encoder.data, encoder.data.primaryItemID);
                        assert primaryItem != null;
                        primaryItem.irefType = "prem";
                        primaryItem.irefToID = item.id;
                    }
                }
            }
        }

        // -----------------------------------------------------------------------
        // Create metadata items (Exif, XMP)

        if (firstCell.exif.length > 0) {
            // Validate Exif payload (if any) and find TIFF header offset
            int exifTiffHeaderOffset = 0;
            if (firstCell.exif.length < 4) {
                // Can't even fit the TIFF header, something is wrong
                return avifResult.AVIF_RESULT_INVALID_EXIF_PAYLOAD;
            }

            final byte[] tiffHeaderBE = { 'M', 'M', 0, 42 };
            final byte[] tiffHeaderLE = { 'I', 'I', 42, 0 };
            for (; exifTiffHeaderOffset < (firstCell.exif.length - 4); ++exifTiffHeaderOffset) {
                if (!memcmp(firstCell.exif[exifTiffHeaderOffset], tiffHeaderBE, tiffHeaderBE.length)) {
                    break;
                }
                if (!memcmp(firstCell.exif[exifTiffHeaderOffset], tiffHeaderLE, tiffHeaderLE.length)) {
                    break;
                }
            }

            if (exifTiffHeaderOffset >= firstCell.exif.length - 4) {
                // Couldn't find the TIFF header
                return avifResult.AVIF_RESULT_INVALID_EXIF_PAYLOAD;
            }

            avifEncoderItem exifItem = avifEncoderDataCreateItem(encoder.data, "Exif", "Exif", 5, 0);
            exifItem.irefToID = encoder.data.primaryItemID;
            exifItem.irefType = "cdsc";

            rawdata.avifRWDataRealloc(exifItem.metadataPayload, Integer.BYTES + firstCell.exif.length);
            exifTiffHeaderOffset = avifHTONL(exifTiffHeaderOffset);
            memcpy(exifItem.metadataPayload, exifTiffHeaderOffset, Integer.BYTES);
            memcpy(exifItem.metadataPayload+ Integer.BYTES, firstCell.exif, firstCell.exif.length);
        }

        if (firstCell.xmp.length > 0) {
            avifEncoderItem xmpItem = avifEncoderDataCreateItem(encoder.data, "mime", "XMP", 4, 0);
            xmpItem.irefToID = encoder.data.primaryItemID;
            xmpItem.irefType = "cdsc";

            xmpItem.infeContentType = xmpContentType;
            xmpItem.infeContentTypeSize = xmpContentType.length();
            rawdata.avifRWDataSet(xmpItem.metadataPayload, firstCell.xmp, firstCell.xmp.length);
        }
    } else {
        // Another frame in an image sequence

        if (encoder.data.alphaPresent && firstCell.alphaPlane == null) {
            // If the first image in the sequence had an alpha plane (even if fully opaque), all
            // subsequence images must have alpha as well.
            return avifResult.AVIF_RESULT_ENCODE_ALPHA_FAILED;
        }
    }

    // -----------------------------------------------------------------------
    // Encode AV1 OBUs

    if (encoder.keyframeInterval != 0 && ((encoder.data.frames.size() % encoder.keyframeInterval) == 0)) {
        addImageFlags.add(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_FORCE_KEYFRAME);
    }

    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);
        if (item.codec != null) {
            final avifImage cellImage = cellImages[item.cellIndex];
            avifResult encodeResult =
                item.codec.encodeImage(encoder, cellImage, item.alpha, addImageFlags, item.encodeOutput);
            if (encodeResult == avifResult.AVIF_RESULT_UNKNOWN_ERROR) {
                encodeResult = item.alpha ? avifResult.AVIF_RESULT_ENCODE_ALPHA_FAILED : avifResult.AVIF_RESULT_ENCODE_COLOR_FAILED;
            }
            if (encodeResult != avifResult.AVIF_RESULT_OK) {
                return encodeResult;
            }
        }
    }

    avifEncoderFrame frame = new avifEncoderFrame();
    frame.durationInTimescales = durationInTimescales;
    encoder.data.frames.add(frame);
    return avifResult.AVIF_RESULT_OK;
}

avifResult avifEncoderAddImage(avifEncoder encoder, final avifImage[] image, long durationInTimescales, EnumSet<avifAddImageFlag> addImageFlags)
{
    return avifEncoderAddImageInternal(encoder, 1, 1, image, durationInTimescales, addImageFlags);
}

avifResult avifEncoderAddImageGrid(avifEncoder encoder,
                                   int gridCols,
                                   int gridRows,
                                   final avifImage[] cellImages)
{
    if ((gridCols == 0) || (gridCols > 256) || (gridRows == 0) || (gridRows > 256)) {
        return avifResult.AVIF_RESULT_INVALID_IMAGE_GRID;
    }
    return avifEncoderAddImageInternal(encoder, gridCols, gridRows, cellImages, 1, EnumSet.of(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE)); // only single image grids are supported
}

private int avifEncoderFindExistingChunk(DataOutputStream s, int mdatStartOffset, final byte[] data, int size)
{
    final int mdatCurrentOffset = avifRWStreamOffset(s);
    final int mdatSearchSize = mdatCurrentOffset - mdatStartOffset;
    if (mdatSearchSize < size) {
        return 0;
    }
    final int mdatEndSearchOffset = mdatCurrentOffset - size;
    for (int searchOffset = mdatStartOffset; searchOffset <= mdatEndSearchOffset; ++searchOffset) {
        if (!memcmp(data, s.raw.data[searchOffset], size)) {
            return searchOffset;
        }
    }
    return 0;
}

avifResult avifEncoderFinish(avifEncoder encoder, byte[] /* avifRWData */ output)
{
    if (encoder.data.items.size() == 0) {
        return avifResult.AVIF_RESULT_NO_CONTENT;
    }

    // -----------------------------------------------------------------------
    // Finish up AV1 encoding

    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);
        if (item.codec != null) {
            if (!item.codec.encodeFinish(item.encodeOutput)) {
                return item.alpha ? avifResult.AVIF_RESULT_ENCODE_ALPHA_FAILED : avifResult.AVIF_RESULT_ENCODE_COLOR_FAILED;
            }

            if (item.encodeOutput.samples.size() != encoder.data.frames.size()) {
                return item.alpha ? avifResult.AVIF_RESULT_ENCODE_ALPHA_FAILED : avifResult.AVIF_RESULT_ENCODE_COLOR_FAILED;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Harvest av1C properties from AV1 sequence headers

    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);
        if (item.encodeOutput.samples.size() > 0) {
            final avifEncodeSample firstSample = item.encodeOutput.samples.get(0);
            avifSequenceHeader sequenceHeader;
            if (stream.avifSequenceHeaderParse(sequenceHeader, (byte[] /* avifROData */)firstSample.data)) {
                item.av1C = sequenceHeader.av1C;
            } else {
                // This must be an invalid AV1 payload
                return item.alpha ? avifResult.AVIF_RESULT_ENCODE_ALPHA_FAILED : avifResult.AVIF_RESULT_ENCODE_COLOR_FAILED;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Begin write stream

    final avifImage imageMetadata = encoder.data.imageMetadata;
    // The epoch for creation_time and modification_time is midnight, Jan. 1,
    // 1904, in UTC time. Add the number of seconds between that epoch and the
    // Unix epoch.
    long now = System.currentTimeMillis() + 2082844800;

    DataOutputStream s = new DataOutputStream(new ByteArrayOutputStream());

    // -----------------------------------------------------------------------
    // Write ftyp

    final String majorBrand = "avif";
    if (encoder.data.frames.size() > 1) {
        majorBrand = "avis";
    }

    int ftyp = stream.avifRWStreamWriteBox(s, "ftyp", AVIF_BOX_SIZE_TBD);
    s.writeBytes(majorBrand);                             // unsigned int(32) major_brand;
    s.writeInt(0);                                           // unsigned int(32) minor_version;
    s.writeBytes("avif");                                 // unsigned int(32) compatible_brands[];
    if (encoder.data.frames.size() > 1) {                                 //
        s.writeBytes("avis");                             // ... compatible_brands[]
        s.writeBytes("msf1");                             // ... compatible_brands[]
        s.writeBytes("iso8");                             // ... compatible_brands[]
    }                                                                      //
    s.writeBytes("mif1");                                 // ... compatible_brands[]
    s.writeBytes("miaf");                                 // ... compatible_brands[]
    if ((imageMetadata.depth == 8) || (imageMetadata.depth == 10)) {     //
        if (imageMetadata.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV420) {        //
            s.writeBytes("MA1B");                         // ... compatible_brands[]
        } else if (imageMetadata.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV444) { //
            s.writeBytes("MA1A");                         // ... compatible_brands[]
        }
    }
    stream.avifRWStreamFinishBox(s, ftyp);

    // -----------------------------------------------------------------------
    // Start meta

    int meta = stream.avifRWStreamWriteFullBox(s, "meta", AVIF_BOX_SIZE_TBD, 0, 0);

    // -----------------------------------------------------------------------
    // Write hdlr

    int hdlr = stream.avifRWStreamWriteFullBox(s, "hdlr", AVIF_BOX_SIZE_TBD, 0, 0);
    s.writeInt(0);              // unsigned int(32) pre_defined = 0;
    s.writeBytes("pict");    // unsigned int(32) handler_type;
    stream.avifRWStreamWriteZeros(s, 12);           // final unsigned int(32)[3] reserved = 0;
    s.writeBytes("libavif"); // string name; (writing null terminator)
    stream.avifRWStreamFinishBox(s, hdlr);

    // -----------------------------------------------------------------------
    // Write pitm

    if (encoder.data.primaryItemID != 0) {
        stream.avifRWStreamWriteFullBox(s, "pitm", Short.BYTES, 0, 0);
        s.writeShort(encoder.data.primaryItemID); //  unsigned int(16) item_ID;
    }

    // -----------------------------------------------------------------------
    // Write iloc

    int iloc = stream.avifRWStreamWriteFullBox(s, "iloc", AVIF_BOX_SIZE_TBD, 0, 0);

    byte offsetSizeAndLengthSize = (4 << 4) + (4 << 0);          // unsigned int(4) offset_size;
                                                                    // unsigned int(4) length_size;
    s.writeByte(offsetSizeAndLengthSize);             //
    stream.avifRWStreamWriteZeros(s, 1);                                  // unsigned int(4) base_offset_size;
                                                                    // unsigned int(4) reserved;
    s.writeShort((short)encoder.data.items.size()); // unsigned int(16) item_count;

    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);

        int contentSize = item.metadataPayload.length;
        if (item.encodeOutput.samples.size() > 0) {
            // This is choosing sample 0's size as there are two cases here:
            // * This is a single image, in which case this is correct
            // * This is an image sequence, but this file should still be a valid single-image avif,
            //   so there must still be a primary item pointing at a sync sample. Since the first
            //   frame of the image sequence is guaranteed to be a sync sample, it is chosen here.
            //
            // TODO: Offer the ability for a user to specify which frame in the sequence should
            //       become the primary item's image, and force that frame to be a keyframe.
            contentSize = item.encodeOutput.samples.get(0).data.length;
        }

        s.writeShort(item.id);              // unsigned int(16) item_ID;
        s.writeShort(0);                     // unsigned int(16) data_reference_index;
        s.writeShort(1);                     // unsigned int(16) extent_count;
        stream.avifEncoderItemAddMdatFixup(item, s);           //
        s.writeInt(0 /* set later */);     // unsigned int(offset_size*8) extent_offset;
        s.writeInt(contentSize); // unsigned int(length_size*8) extent_length;
    }

    stream.avifRWStreamFinishBox(s, iloc);

    // -----------------------------------------------------------------------
    // Write iinf

    int iinf = stream.avifRWStreamWriteFullBox(s, "iinf", AVIF_BOX_SIZE_TBD, 0, 0);
    s.writeShort((short)encoder.data.items.size()); //  unsigned int(16) entry_count;

    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);

        int flags = item.hiddenImage ? 1 : 0;
        int infe = stream.avifRWStreamWriteFullBox(s, "infe", AVIF_BOX_SIZE_TBD, 2, flags);
        s.writeShort(item.id);                             // unsigned int(16) item_ID;
        s.writeShort(0);                                    // unsigned int(16) item_protection_index;
        s.write(item.type);                           // unsigned int(32) item_type;
        s.writeBytes(item.infeName); // string item_name; (writing null terminator)
        if (item.infeContentType != null && item.infeContentTypeSize != 0) {       // string content_type; (writing null terminator)
            s.writeBytes(item.infeContentType);
        }
        stream.avifRWStreamFinishBox(s, infe);
    }

    stream.avifRWStreamFinishBox(s, iinf);

    // -----------------------------------------------------------------------
    // Write iref boxes

    int iref = 0;
    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);

        // Count how many other items refer to this item with dimgFromID
        short dimgCount = 0;
        for (int dimgIndex = 0; dimgIndex < encoder.data.items.size(); ++dimgIndex) {
            avifEncoderItem dimgItem = encoder.data.items.get(dimgIndex);
            if (dimgItem.dimgFromID == item.id) {
                ++dimgCount;
            }
        }

        if (dimgCount > 0) {
            if (iref==0) {
                iref = stream.avifRWStreamWriteFullBox(s, "iref", AVIF_BOX_SIZE_TBD, 0, 0);
            }
            int refType = stream.avifRWStreamWriteBox(s, "dimg", AVIF_BOX_SIZE_TBD);
            s.writeShort(item.id);  // unsigned int(16) from_item_ID;
            s.writeShort(dimgCount); // unsigned int(16) reference_count;
            for (int dimgIndex = 0; dimgIndex < encoder.data.items.size(); ++dimgIndex) {
                avifEncoderItem dimgItem = encoder.data.items.get(dimgIndex);
                if (dimgItem.dimgFromID == item.id) {
                    s.writeShort(dimgItem.id); // unsigned int(16) to_item_ID;
                }
            }
            stream.avifRWStreamFinishBox(s, refType);
        }

        if (item.irefToID != 0) {
            if (iref==0) {
                iref = stream.avifRWStreamWriteFullBox(s, "iref", AVIF_BOX_SIZE_TBD, 0, 0);
            }
            int refType = stream.avifRWStreamWriteBox(s, item.irefType, AVIF_BOX_SIZE_TBD);
            s.writeShort(item.id);       // unsigned int(16) from_item_ID;
            s.writeShort(1);              // unsigned int(16) reference_count;
            s.writeShort(item.irefToID); // unsigned int(16) to_item_ID;
            stream.avifRWStreamFinishBox(s, refType);
        }
    }
    if (iref!=0) {
        stream.avifRWStreamFinishBox(s, iref);
    }

    // -----------------------------------------------------------------------
    // Write iprp . ipco/ipma

    int iprp = stream.avifRWStreamWriteBox(s, "iprp", AVIF_BOX_SIZE_TBD);

    avifItemPropertyDedup dedup = avifItemPropertyDedupCreate();
    int ipco = stream.avifRWStreamWriteBox(s, "ipco", AVIF_BOX_SIZE_TBD);
    for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
        avifEncoderItem item = encoder.data.items.get(itemIndex);
        final boolean isGrid = (item.gridCols > 0);
//        memset(item.ipma, 0, item.ipma.size());
        if (item.codec == null && !isGrid) {
            // No ipma to write for this item
            continue;
        }

        if (item.dimgFromID != 0) {
            // All image cells from a grid should share the exact same properties, so see if we've
            // already written properties out for another cell in this grid, and if so, just steal
            // their ipma and move on. This is a sneaky way to provide iprp deduplication.

            boolean foundPreviousCell = false;
            for (int dedupIndex = 0; dedupIndex < itemIndex; ++dedupIndex) {
                avifEncoderItem dedupItem = encoder.data.items.get(dedupIndex);
                if (item.dimgFromID == dedupItem.dimgFromID) {
                    // We've already written dedup's items out. Steal their ipma indices and move on!
                    item.ipma = dedupItem.ipma;
                    foundPreviousCell = true;
                    break;
                }
            }
            if (foundPreviousCell) {
                continue;
            }
        }

        int imageWidth = imageMetadata.width;
        int imageHeight = imageMetadata.height;
        if (isGrid) {
            imageWidth = imageMetadata.width * item.gridCols;
            imageHeight = imageMetadata.height * item.gridRows;
        }

        // Properties all av01 items need

        avifItemPropertyDedupStart(dedup);
        int ispe = stream.avifRWStreamWriteFullBox(dedup.s, "ispe", AVIF_BOX_SIZE_TBD, 0, 0);
        dedup.s.writeInt(imageWidth);  // unsigned int(32) image_width;
        dedup.s.writeInt(imageHeight); // unsigned int(32) image_height;
        stream.avifRWStreamFinishBox(dedup.s, ispe);
        item.ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, s), false));

        avifItemPropertyDedupStart(dedup);
        byte channelCount = (byte) ((item.alpha || (imageMetadata.yuvFormat == avifPixelFormat.AVIF_PIXEL_FORMAT_YUV400)) ? 1 : 3);
        int pixi = stream.avifRWStreamWriteFullBox(dedup.s, "pixi", AVIF_BOX_SIZE_TBD, 0, 0);
        dedup.s.writeByte(channelCount); // unsigned int (8) num_channels;
        for (byte chan = 0; chan < channelCount; ++chan) {
            dedup.s.writeByte(imageMetadata.depth); // unsigned int (8) bits_per_channel;
        }
        stream.avifRWStreamFinishBox(dedup.s, pixi);
        item.ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, s), false));

        if (item.codec != null) {
            avifItemPropertyDedupStart(dedup);
            writeConfigBox(dedup.s, item.av1C);
            item.ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, s), true));
        }

        if (item.alpha) {
            // Alpha specific properties

            avifItemPropertyDedupStart(dedup);
            int auxC = stream.avifRWStreamWriteFullBox(dedup.s, "auxC", AVIF_BOX_SIZE_TBD, 0, 0);
            dedup.s.writeBytes(alphaURN); //  string aux_type;
            stream.avifRWStreamFinishBox(dedup.s, auxC);
            item.ipma.add(new ipma(avifItemPropertyDedupFinish(dedup, s), false));
        } else {
            // Color specific properties

            avifEncoderWriteColorProperties(s, imageMetadata, item.ipma, dedup);
        }
    }
    stream.avifRWStreamFinishBox(s, ipco);
    dedup = null;

    int ipma = stream.avifRWStreamWriteFullBox(s, "ipma", AVIF_BOX_SIZE_TBD, 0, 0);
    {
        int ipmaCount = 0;
        for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
            avifEncoderItem item = encoder.data.items.get(itemIndex);
            if (item.ipma.size() > 0) {
                ++ipmaCount;
            }
        }
        s.writeInt(ipmaCount); // unsigned int(32) entry_count;

        for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
            avifEncoderItem item = encoder.data.items.get(itemIndex);
            if (item.ipma.size() == 0) {
                continue;
            }

            s.writeShort(item.id);          // unsigned int(16) item_ID;
            s.writeByte(item.ipma.size());   // unsigned int(8) association_count;
            for (int i = 0; i < item.ipma.size(); ++i) { //
                byte[] essentialAndIndex = item.ipma.get(i).associations;
                if (item.ipma.get(i).essential) {
                    essentialAndIndex |= 0x80;
                }
                s.writeByte(essentialAndIndex); // bit(1) essential; unsigned int(7) property_index;
            }
        }
    }
    stream.avifRWStreamFinishBox(s, ipma);

    stream.avifRWStreamFinishBox(s, iprp);

    // -----------------------------------------------------------------------
    // Finish meta box

    stream.avifRWStreamFinishBox(s, meta);

    // -----------------------------------------------------------------------
    // Write tracks (if an image sequence)

    if (encoder.data.frames.size() > 1) {
        final byte[][] unityMatrix = {
            /* @format off */
            { 0x00, 0x01, 0x00, 0x00 },
            { 0 },
            { 0 },
            { 0 },
            { 0x00, 0x01, 0x00, 0x00 },
            { 0 },
            { 0 },
            { 0 },
            { 0x40, 0x00, 0x00, 0x00 }
            /* @format on */
        };

        long durationInTimescales = 0;
        for (int frameIndex = 0; frameIndex < encoder.data.frames.size(); ++frameIndex) {
            final avifEncoderFrame frame = encoder.data.frames.get(frameIndex);
            durationInTimescales += frame.durationInTimescales;
        }

        // -------------------------------------------------------------------
        // Start moov

        int moov = stream.avifRWStreamWriteBox(s, "moov", AVIF_BOX_SIZE_TBD);

        int mvhd = stream.avifRWStreamWriteFullBox(s, "mvhd", AVIF_BOX_SIZE_TBD, 1, 0);
        s.writeLong(now);                          // unsigned int(64) creation_time;
        s.writeLong(now);                          // unsigned int(64) modification_time;
        s.writeInt((int)encoder.timescale); // unsigned int(32) timescale;
        s.writeLong(durationInTimescales);         // unsigned int(64) duration;
        s.writeInt(0x00010000);                   // template int(32) rate = 0x00010000; // typically 1.0
        s.writeShort(0x0100);                       // template int(16) volume = 0x0100; // typically, full volume
        s.writeShort(0);                            // final bit(16) reserved = 0;
        stream.avifRWStreamWriteZeros(s, 8);                          // final unsigned int(32)[2] reserved = 0;
        for (byte[] b : unityMatrix)
            s.write(b, 0, b.length);
        stream.avifRWStreamWriteZeros(s, 24);                       // bit(32)[6] pre_defined = 0;
        s.writeInt(encoder.data.items.size()); // unsigned int(32) next_track_ID;
        stream.avifRWStreamFinishBox(s, mvhd);

        // -------------------------------------------------------------------
        // Write tracks

        for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
            avifEncoderItem item = encoder.data.items.get(itemIndex);
            if (item.encodeOutput.samples.size() == 0) {
                continue;
            }

            int syncSamplesCount = 0;
            for (int sampleIndex = 0; sampleIndex < item.encodeOutput.samples.size(); ++sampleIndex) {
                avifEncodeSample sample = item.encodeOutput.samples.get(sampleIndex);
                if (sample.sync) {
                    ++syncSamplesCount;
                }
            }

            int trak = stream.avifRWStreamWriteBox(s, "trak", AVIF_BOX_SIZE_TBD);

            int tkhd = stream.avifRWStreamWriteFullBox(s, "tkhd", AVIF_BOX_SIZE_TBD, 1, 1);
            s.writeLong(now);                    // unsigned int(64) creation_time;
            s.writeLong(now);                    // unsigned int(64) modification_time;
            s.writeInt(itemIndex + 1);          // unsigned int(32) track_ID;
            s.writeInt(0);                      // final unsigned int(32) reserved = 0;
            s.writeLong(durationInTimescales);   // unsigned int(64) duration;
            stream.avifRWStreamWriteZeros(s, Integer.BYTES * 2); // final unsigned int(32)[2] reserved = 0;
            s.writeShort(0);                      // template int(16) layer = 0;
            s.writeShort(0);                      // template int(16) alternate_group = 0;
            s.writeShort(0);                      // template int(16) volume = {if track_is_audio 0x0100 else 0};
            s.writeShort(0);                      // final unsigned int(16) reserved = 0;
            for (byte[] b : unityMatrix)
                s.write(b, 0, b.length); // template int(32)[9] matrix= // { 0x00010000,0,0,0,0x00010000,0,0,0,0x40000000 };
            s.writeInt(imageMetadata.width << 16);  // unsigned int(32) width;
            s.writeInt(imageMetadata.height << 16); // unsigned int(32) height;
            stream.avifRWStreamFinishBox(s, tkhd);

            if (item.irefToID != 0) {
                int tref = stream.avifRWStreamWriteBox(s, "tref", AVIF_BOX_SIZE_TBD);
                int refType = stream.avifRWStreamWriteBox(s, item.irefType, AVIF_BOX_SIZE_TBD);
                s.writeInt(item.irefToID);
                stream.avifRWStreamFinishBox(s, refType);
                stream.avifRWStreamFinishBox(s, tref);
            }

            if (!item.alpha) {
                avifEncoderWriteTrackMetaBox(encoder, s);
            }

            int mdia = stream.avifRWStreamWriteBox(s, "mdia", AVIF_BOX_SIZE_TBD);

            int mdhd = stream.avifRWStreamWriteFullBox(s, "mdhd", AVIF_BOX_SIZE_TBD, 1, 0);
            s.writeLong(now);                          // unsigned int(64) creation_time;
            s.writeLong(now);                          // unsigned int(64) modification_time;
            s.writeInt((int)encoder.timescale); // unsigned int(32) timescale;
            s.writeLong(durationInTimescales);         // unsigned int(64) duration;
            s.writeShort(21956);                        // bit(1) pad = 0; unsigned int(5)[3] language; ("und")
            s.writeShort(0);                            // unsigned int(16) pre_defined = 0;
            stream.avifRWStreamFinishBox(s, mdhd);

            int hdlrTrak = stream.avifRWStreamWriteFullBox(s, "hdlr", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(0);              // unsigned int(32) pre_defined = 0;
            s.writeBytes("pict");    // unsigned int(32) handler_type;
            stream.avifRWStreamWriteZeros(s, 12);           // final unsigned int(32)[3] reserved = 0;
            s.writeBytes("libavif"); // string name; (writing null terminator)
            stream.avifRWStreamFinishBox(s, hdlrTrak);

            int minf = stream.avifRWStreamWriteBox(s, "minf", AVIF_BOX_SIZE_TBD);

            int vmhd = stream.avifRWStreamWriteFullBox(s, "vmhd", AVIF_BOX_SIZE_TBD, 0, 1);
            s.writeShort(0);   // template unsigned int(16) graphicsmode = 0; (copy over the existing image)
            stream.avifRWStreamWriteZeros(s, 6); // template unsigned int(16)[3] opcolor = {0, 0, 0};
            stream.avifRWStreamFinishBox(s, vmhd);

            int dinf = stream.avifRWStreamWriteBox(s, "dinf", AVIF_BOX_SIZE_TBD);
            int dref =stream. avifRWStreamWriteFullBox(s, "dref", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(1);                   // unsigned int(32) entry_count;
            stream.avifRWStreamWriteFullBox(s, "url ", 0, 0, 1); // flags:1 means data is in this file
            stream.avifRWStreamFinishBox(s, dref);
            stream.avifRWStreamFinishBox(s, dinf);

            int stbl = stream.avifRWStreamWriteBox(s, "stbl", AVIF_BOX_SIZE_TBD);

            int stco = stream.avifRWStreamWriteFullBox(s, "stco", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(1);           // unsigned int(32) entry_count;
            avifEncoderItemAddMdatFixup(item, s); //
            s.writeInt(1);           // unsigned int(32) chunk_offset; (set later)
            stream.avifRWStreamFinishBox(s, stco);

            int stsc = stream.avifRWStreamWriteFullBox(s, "stsc", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(1);                                 // unsigned int(32) entry_count;
            s.writeInt(1);                                 // unsigned int(32) first_chunk;
            s.writeInt(item.encodeOutput.samples.size()); // unsigned int(32) samples_per_chunk;
            s.writeInt(1);                                 // unsigned int(32) sample_description_index;
            stream.avifRWStreamFinishBox(s, stsc);

            int stsz = stream.avifRWStreamWriteFullBox(s, "stsz", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(0);                                 // unsigned int(32) sample_size;
            s.writeInt(item.encodeOutput.samples.size()); // unsigned int(32) sample_count;
            for (int sampleIndex = 0; sampleIndex < item.encodeOutput.samples.size(); ++sampleIndex) {
                avifEncodeSample sample = item.encodeOutput.samples.get(sampleIndex);
                s.writeInt(sample.data.length); // unsigned int(32) entry_size;
            }
            stream.avifRWStreamFinishBox(s, stsz);

            int stss = stream.avifRWStreamWriteFullBox(s, "stss", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(syncSamplesCount); // unsigned int(32) entry_count;
            for (int sampleIndex = 0; sampleIndex < item.encodeOutput.samples.size(); ++sampleIndex) {
                avifEncodeSample sample = item.encodeOutput.samples.get(sampleIndex);
                if (sample.sync) {
                    s.writeInt(sampleIndex + 1); // unsigned int(32) sample_number;
                }
            }
            stream.avifRWStreamFinishBox(s, stss);

            int stts = stream.avifRWStreamWriteFullBox(s, "stts", AVIF_BOX_SIZE_TBD, 0, 0);
            int sttsEntryCountOffset = avifRWStreamOffset(s);
            int sttsEntryCount = 0;
            s.writeInt(0); // unsigned int(32) entry_count;
            for (int sampleCount = 0, frameIndex = 0; frameIndex < encoder.data.frames.size(); ++frameIndex) {
                avifEncoderFrame frame = encoder.data.frames.get(frameIndex);
                ++sampleCount;
                if (frameIndex < (encoder.data.frames.size() - 1)) {
                    avifEncoderFrame nextFrame = encoder.data.frames.get(frameIndex + 1);
                    if (frame.durationInTimescales == nextFrame.durationInTimescales) {
                        continue;
                    }
                }
                s.writeInt(sampleCount);                           // unsigned int(32) sample_count;
                s.writeInt((int)frame.durationInTimescales); // unsigned int(32) sample_delta;
                sampleCount = 0;
                ++sttsEntryCount;
            }
            int prevOffset = avifRWStreamOffset(s);
            avifRWStreamSetOffset(s, sttsEntryCountOffset);
            s.writeInt(sttsEntryCount);
            avifRWStreamSetOffset(s, prevOffset);
            stream.avifRWStreamFinishBox(s, stts);

            int stsd = stream.avifRWStreamWriteFullBox(s, "stsd", AVIF_BOX_SIZE_TBD, 0, 0);
            s.writeInt(1); // unsigned int(32) entry_count;
            int av01 = stream.avifRWStreamWriteBox(s, "av01", AVIF_BOX_SIZE_TBD);
            stream.avifRWStreamWriteZeros(s, 6);                             // final unsigned int(8)[6] reserved = 0;
            s.writeShort(1);                               // unsigned int(16) data_reference_index;
            s.writeShort(0);                               // unsigned int(16) pre_defined = 0;
            s.writeShort(0);                               // final unsigned int(16) reserved = 0;
            stream.avifRWStreamWriteZeros(s, Integer.BYTES * 3);          // unsigned int(32)[3] pre_defined = 0;
            s.writeShort((short)imageMetadata.width);  // unsigned int(16) width;
            s.writeShort((short)imageMetadata.height); // unsigned int(16) height;
            s.writeInt(0x00480000);                      // template unsigned int(32) horizresolution
            s.writeInt(0x00480000);                      // template unsigned int(32) vertresolution
            s.writeInt(0);                               // final unsigned int(32) reserved = 0;
            s.writeShort(1);                               // template unsigned int(16) frame_count = 1;
            s.writeBytes("\012AOM Coding");          // string[32] compressorname;
            stream.avifRWStreamWriteZeros(s, 32 - 11);                       //
            s.writeShort(0x0018);                          // template unsigned int(16) depth = 0x0018;
            s.writeShort((short)0xffff);                // int(16) pre_defined = -1;
            writeConfigBox(s, item.av1C);
            if (!item.alpha) {
                avifEncoderWriteColorProperties(s, imageMetadata, null, null);
            }

            int ccst = stream.avifRWStreamWriteFullBox(s, "ccst", AVIF_BOX_SIZE_TBD, 0, 0);
            final byte ccstValue = (0 << 7) | // unsigned int(1) all_ref_pics_intra;
                                      (1 << 6) | // unsigned int(1) intra_pred_used;
                                      (15 << 2); // unsigned int(4) max_ref_per_pic;
            s.writeByte(ccstValue);
            stream.avifRWStreamWriteZeros(s, 3); // unsigned int(26) reserved; (two zero bits are written along with ccstValue).
            stream.avifRWStreamFinishBox(s, ccst);

            stream.avifRWStreamFinishBox(s, av01);
            stream.avifRWStreamFinishBox(s, stsd);

            stream.avifRWStreamFinishBox(s, stbl);

            stream.avifRWStreamFinishBox(s, minf);
            stream.avifRWStreamFinishBox(s, mdia);
            stream.avifRWStreamFinishBox(s, trak);
        }

        // -------------------------------------------------------------------
        // Finish moov box

        stream.avifRWStreamFinishBox(s, moov);
    }

    // -----------------------------------------------------------------------
    // Write mdat

    encoder.ioStats.colorOBUSize = 0;
    encoder.ioStats.alphaOBUSize = 0;

    int mdat = stream.avifRWStreamWriteBox(s, "mdat", AVIF_BOX_SIZE_TBD);
    final int mdatStartOffset = avifRWStreamOffset(s);
    for (int itemPasses = 0; itemPasses < 3; ++itemPasses) {
        // Use multiple passes to pack in the following order:
        //   * Pass 0: metadata (Exif/XMP)
        //   * Pass 1: alpha (AV1)
        //   * Pass 2: all other item data (AV1 color)
        //
        // See here for the discussion on alpha coming before color:
        // https://github.com/AOMediaCodec/libavif/issues/287
        //
        // Exif and XMP are packed first as they're required to be fully available
        // by avifDecoderParse() before it returns avifResult.AVIF_RESULT_OK, unless ignoreXMP
        // and ignoreExif are enabled.
        //
        final boolean metadataPass = (itemPasses == 0);
        final boolean alphaPass = (itemPasses == 1);

        for (int itemIndex = 0; itemIndex < encoder.data.items.size(); ++itemIndex) {
            avifEncoderItem item = encoder.data.items.get(itemIndex);
            final boolean isGrid = (item.gridCols > 0); // Grids store their payload in metadataPayload, so use this to distinguish grid payloads from XMP/Exif
            if ((item.metadataPayload.length == 0) && (item.encodeOutput.samples.size() == 0)) {
                // this item has nothing for the mdat box
                continue;
            }
            if (!isGrid && (metadataPass != (item.metadataPayload.length > 0))) {
                // only process metadata (XMP/Exif) payloads when metadataPass is true
                continue;
            }
            if (alphaPass != item.alpha) {
                // only process alpha payloads when alphaPass is true
                continue;
            }

            int chunkOffset = 0;

            // Deduplication - See if an identical chunk to this has already been written
            if (item.encodeOutput.samples.size() > 0) {
                avifEncodeSample sample = item.encodeOutput.samples.get(0);
                chunkOffset = avifEncoderFindExistingChunk(s, mdatStartOffset, sample.data, sample.data.length);
            } else {
                chunkOffset = avifEncoderFindExistingChunk(s, mdatStartOffset, item.metadataPayload, item.metadataPayload.length);
            }

            if (chunkOffset == 0) {
                // We've never seen this chunk before; write it out
                chunkOffset = avifRWStreamOffset(s);
                if (item.encodeOutput.samples.size() > 0) {
                    for (int sampleIndex = 0; sampleIndex < item.encodeOutput.samples.size(); ++sampleIndex) {
                        avifEncodeSample sample = item.encodeOutput.samples.get(sampleIndex);
                        s.write(sample.data, sample.data.length);

                        if (item.alpha) {
                            encoder.ioStats.alphaOBUSize += sample.data.length;
                        } else {
                            encoder.ioStats.colorOBUSize += sample.data.length;
                        }
                    }
                } else {
                    s.write(item.metadataPayload, 0, item.metadataPayload.length);
                }
            }

            for (int fixupIndex = 0; fixupIndex < item.Fixups.size(); ++fixupIndex) {
                avifOffsetFixup fixup = item.Fixups.get(fixupIndex);
                int prevOffset = avifRWStreamOffset(s);
                avifRWStreamSetOffset(s, fixup.offset);
                s.writeInt(chunkOffset);
                avifRWStreamSetOffset(s, prevOffset);
            }
        }
    }
    stream.avifRWStreamFinishBox(s, mdat);

    // -----------------------------------------------------------------------
    // Finish up stream

    output = s;

    return avifResult.AVIF_RESULT_OK;
}

avifResult avifEncoderWrite(avifEncoder encoder, final avifImage[] image, byte[] /* avifRWData */ output)
{
    avifResult addImageResult = avifEncoderAddImage(encoder, image, 1, EnumSet.of(avifAddImageFlag.AVIF_ADD_IMAGE_FLAG_SINGLE));
    if (addImageResult != avifResult.AVIF_RESULT_OK) {
        return addImageResult;
    }
    return avifEncoderFinish(encoder, output);
}

private boolean avifImageIsOpaque(final avifImage image)
{
    if (image.alphaPlane == null) {
        return true;
    }

    int maxChannel = (1 << image.depth) - 1;
    if (image.avifImageUsesU16()) {
        for (int j = 0; j < image.height; ++j) {
            for (int i = 0; i < image.width; ++i) {
                short p = ByteUtil.readBeShort(image.alphaPlane, (i * 2) + (j * image.alphaRowBytes));
                if (p != maxChannel) {
                    return false;
                }
            }
        }
    } else {
        for (int j = 0; j < image.height; ++j) {
            for (int i = 0; i < image.width; ++i) {
                if (image.alphaPlane[i + (j * image.alphaRowBytes)] != maxChannel) {
                    return false;
                }
            }
        }
    }
    return true;
}

private void writeConfigBox(DataOutputStream s, avifCodecConfigurationBox cfg) throws IOException
{
    int av1C = stream.avifRWStreamWriteBox(s, "av1C", AVIF_BOX_SIZE_TBD);

    // unsigned int (1) marker = 1;
    // unsigned int (7) version = 1;
    s.writeByte(0x80 | 0x1);

    // unsigned int (3) seq_profile;
    // unsigned int (5) seq_level_idx_0;
    s.writeByte((byte)((cfg.seqProfile & 0x7) << 5) | (byte)(cfg.seqLevelIdx0 & 0x1f));

    byte bits = 0;
    bits |= (cfg.seqTier0 & 0x1) << 7;           // unsigned int (1) seq_tier_0;
    bits |= (cfg.highBitdepth & 0x1) << 6;       // unsigned int (1) high_bitdepth;
    bits |= (cfg.twelveBit & 0x1) << 5;          // unsigned int (1) twelve_bit;
    bits |= (cfg.monochrome & 0x1) << 4;         // unsigned int (1) monochrome;
    bits |= (cfg.chromaSubsamplingX & 0x1) << 3; // unsigned int (1) chroma_subsampling_x;
    bits |= (cfg.chromaSubsamplingY & 0x1) << 2; // unsigned int (1) chroma_subsampling_y;
    bits |= (cfg.chromaSamplePosition & 0x3);    // unsigned int (2) chroma_sample_position;
    s.writeByte(bits);

    // unsigned int (3) reserved = 0;
    // unsigned int (1) initial_presentation_delay_present;
    // if (initial_presentation_delay_present) {
    //   unsigned int (4) initial_presentation_delay_minus_one;
    // } else {
    //   unsigned int (4) reserved = 0;
    // }
    s.writeByte(0);

    stream.avifRWStreamFinishBox(s, av1C);
}
}