/*
 * Copyright (c) 2014, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "TwelveMonkeys" nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.twelvemonkeys.imageio.plugins.psd;

import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.exif.TIFF;
import com.twelvemonkeys.imageio.metadata.iptc.IPTC;
import com.twelvemonkeys.lang.StringUtil;
import com.twelvemonkeys.util.FilterIterator;
import org.w3c.dom.Node;

import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * PSDMetadata
 *
 * @author <a href="mailto:harald.kuhr@gmail.com">Harald Kuhr</a>
 * @author last modified by $Author: haraldk$
 * @version $Id: PSDMetadata.java,v 1.0 Nov 4, 2009 5:28:12 PM haraldk Exp$
 */
public final class PSDMetadata extends AbstractMetadata {

    // TODO: Decide on image/stream metadata...
    static final String NATIVE_METADATA_FORMAT_NAME = "com_twelvemonkeys_imageio_psd_image_1.0";
    static final String NATIVE_METADATA_FORMAT_CLASS_NAME = "com.twelvemonkeys.imageio.plugins.psd.PSDMetadataFormat";
    // TODO: Support TIFF metadata, based on EXIF/XMP + merge in PSD specifics

    PSDHeader header;
    PSDColorData colorData;
    int compression = -1;
    List<PSDImageResource> imageResources;
    PSDGlobalLayerMask globalLayerMask;
    List<PSDLayerInfo> layerInfo;

    long imageResourcesStart;
    long layerAndMaskInfoStart;
    long layersStart;
    long imageDataStart;

    static final String[] COLOR_MODES = {
            "MONOCHROME", "GRAYSCALE", "INDEXED", "RGB", "CMYK", null, null, "MULTICHANNEL", "DUOTONE", "LAB"
    };

    static final String[] DISPLAY_INFO_CS = {
            "RGB", "HSB", "CMYK", "PANTONE", "FOCOLTONE", "TRUMATCH", "TOYO", "LAB", "GRAYSCALE", null, "HKS", "DIC",
            null, // TODO: ... (until index 2999),
            "ANPA"
    };
    static final String[] DISPLAY_INFO_KINDS = {"selected", "protected"};

    static final String[] RESOLUTION_UNITS = {null, "pixels/inch", "pixels/cm"};
    static final String[] DIMENSION_UNITS = {null, "in", "cm", "pt", "picas", "columns"};

    static final String[] JAVA_CS = {
            "XYZ", "Lab", "Yuv", "YCbCr", "Yxy", "RGB", "GRAY", "HSV", "HLS", "CMYK", "CMY",
            "2CLR", "3CLR", "4CLR", "5CLR", "6CLR", "7CLR", "8CLR", "9CLR", "ACLR", "BCLR", "CCLR", "DCLR", "ECLR", "FCLR"
    };

    static final String[] GUIDE_ORIENTATIONS = {"vertical", "horizontal"};

    static final String[] PRINT_SCALE_STYLES = {"centered", "scaleToFit", "userDefined"};

    protected PSDMetadata() {
        // TODO: Allow XMP, EXIF and IPTC as extra formats?
        super(true, NATIVE_METADATA_FORMAT_NAME, NATIVE_METADATA_FORMAT_CLASS_NAME, null, null);
    }

    /// Native format support

    @Override
    protected Node getNativeTree() {
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_METADATA_FORMAT_NAME);

        root.appendChild(createHeaderNode());

        if (header.mode == PSD.COLOR_MODE_INDEXED) {
            root.appendChild(createPaletteNode());
        }

        if (imageResources != null && !imageResources.isEmpty()) {
            root.appendChild(createImageResourcesNode());
        }
        
        return root;
    }

    private Node createHeaderNode() {
        IIOMetadataNode headerNode = new IIOMetadataNode("Header");

        headerNode.setAttribute("type", "PSD");
        headerNode.setAttribute("version", header.largeFormat ? "2" : "1");
        headerNode.setAttribute("channels", Integer.toString(header.channels));
        headerNode.setAttribute("height", Integer.toString(header.height));
        headerNode.setAttribute("width", Integer.toString(header.width));
        headerNode.setAttribute("bits", Integer.toString(header.bits));
        headerNode.setAttribute("mode", COLOR_MODES[header.mode]);

        return headerNode;
    }

    private Node createImageResourcesNode() {
        IIOMetadataNode resource = new IIOMetadataNode("ImageResources");
        IIOMetadataNode node;

        for (PSDImageResource imageResource : imageResources) {
            // TODO: Always add name (if set) and id (as resourceId) to all nodes?
            // Resource Id is useful for people with access to the PSD spec..

            if (imageResource instanceof ICCProfile) {
                ICCProfile profile = (ICCProfile) imageResource;

                // TODO: Format spec
                node = new IIOMetadataNode("ICCProfile");
                node.setAttribute("colorSpaceType", JAVA_CS[profile.getProfile().getColorSpaceType()]);
                node.setUserObject(profile.getProfile());
            }
            else if (imageResource instanceof PSDAlphaChannelInfo) {
                PSDAlphaChannelInfo alphaChannelInfo = (PSDAlphaChannelInfo) imageResource;

                node = new IIOMetadataNode("AlphaChannelInfo");

                for (String name : alphaChannelInfo.names) {
                    IIOMetadataNode nameNode = new IIOMetadataNode("Name");
                    nameNode.setAttribute("value", name);
                    node.appendChild(nameNode);
                }
            }
            else if (imageResource instanceof PSDDisplayInfo) {
                PSDDisplayInfo displayInfo = (PSDDisplayInfo) imageResource;

                node = new IIOMetadataNode("DisplayInfo");
                node.setAttribute("colorSpace", DISPLAY_INFO_CS[displayInfo.colorSpace]);
                
                StringBuilder builder = new StringBuilder();

                for (short color : displayInfo.colors) {
                    if (builder.length() > 0) {
                        builder.append(" ");
                    }

                    builder.append(Integer.toString(color));
                }

                node.setAttribute("colors", builder.toString());
                node.setAttribute("opacity", Integer.toString(displayInfo.opacity));
                node.setAttribute("kind", DISPLAY_INFO_KINDS[displayInfo.kind]);
            }
            else if (imageResource instanceof PSDGridAndGuideInfo) {
                PSDGridAndGuideInfo info = (PSDGridAndGuideInfo) imageResource;

                node = new IIOMetadataNode("GridAndGuideInfo");
                node.setAttribute("version", String.valueOf(info.version));
                node.setAttribute("verticalGridCycle", String.valueOf(info.gridCycleVertical));
                node.setAttribute("horizontalGridCycle", String.valueOf(info.gridCycleHorizontal));

                for (PSDGridAndGuideInfo.GuideResource guide : info.guides) {
                    IIOMetadataNode guideNode = new IIOMetadataNode("Guide");
                    guideNode.setAttribute("location", Integer.toString(guide.location));
                    guideNode.setAttribute("orientation", GUIDE_ORIENTATIONS[guide.direction]);
                }
            }
            else if (imageResource instanceof PSDPixelAspectRatio) {
                PSDPixelAspectRatio aspectRatio = (PSDPixelAspectRatio) imageResource;

                node = new IIOMetadataNode("PixelAspectRatio");
                node.setAttribute("version", String.valueOf(aspectRatio.version));
                node.setAttribute("aspectRatio", String.valueOf(aspectRatio.aspect));
            }
            else if (imageResource instanceof PSDPrintFlags) {
                PSDPrintFlags flags = (PSDPrintFlags) imageResource;

                node = new IIOMetadataNode("PrintFlags");
                node.setAttribute("labels", String.valueOf(flags.labels));
                node.setAttribute("cropMarks", String.valueOf(flags.cropMasks));
                node.setAttribute("colorBars", String.valueOf(flags.colorBars));
                node.setAttribute("registrationMarks", String.valueOf(flags.registrationMarks));
                node.setAttribute("negative", String.valueOf(flags.negative));
                node.setAttribute("flip", String.valueOf(flags.flip));
                node.setAttribute("interpolate", String.valueOf(flags.interpolate));
                node.setAttribute("caption", String.valueOf(flags.caption));
            }
            else if (imageResource instanceof PSDPrintFlagsInformation) {
                PSDPrintFlagsInformation information = (PSDPrintFlagsInformation) imageResource;

                node = new IIOMetadataNode("PrintFlagsInformation");
                node.setAttribute("version", String.valueOf(information.version));
                node.setAttribute("cropMarks", String.valueOf(information.cropMasks));
                node.setAttribute("field", String.valueOf(information.field));
                node.setAttribute("bleedWidth", String.valueOf(information.bleedWidth));
                node.setAttribute("bleedScale", String.valueOf(information.bleedScale));
            }
            else if (imageResource instanceof PSDPrintScale) {
                PSDPrintScale printScale = (PSDPrintScale) imageResource;

                node = new IIOMetadataNode("PrintScale");
                node.setAttribute("style", PRINT_SCALE_STYLES[printScale.style]);
                node.setAttribute("xLocation", String.valueOf(printScale.xLocation));
                node.setAttribute("yLocation", String.valueOf(printScale.ylocation));
                node.setAttribute("scale", String.valueOf(printScale.scale));
            }
            else if (imageResource instanceof PSDResolutionInfo) {
                PSDResolutionInfo information = (PSDResolutionInfo) imageResource;

                node = new IIOMetadataNode("ResolutionInfo");
                node.setAttribute("horizontalResolution", String.valueOf(information.hRes));
                node.setAttribute("horizontalResolutionUnit", RESOLUTION_UNITS[information.hResUnit]);
                node.setAttribute("widthUnit", DIMENSION_UNITS[information.widthUnit]);
                node.setAttribute("verticalResolution", String.valueOf(information.vRes));
                node.setAttribute("verticalResolutionUnit", RESOLUTION_UNITS[information.vResUnit]);
                node.setAttribute("heightUnit", DIMENSION_UNITS[information.heightUnit]);
            }
            else if (imageResource instanceof PSDUnicodeAlphaNames) {
                PSDUnicodeAlphaNames alphaNames = (PSDUnicodeAlphaNames) imageResource;

                node = new IIOMetadataNode("UnicodeAlphaNames");

                for (String name : alphaNames.names) {
                    IIOMetadataNode nameNode = new IIOMetadataNode("Name");
                    nameNode.setAttribute("value", name);
                    node.appendChild(nameNode);
                }
            }
            else if (imageResource instanceof PSDVersionInfo) {
                PSDVersionInfo information = (PSDVersionInfo) imageResource;

                node = new IIOMetadataNode("VersionInfo");
                node.setAttribute("version", String.valueOf(information.version));
                node.setAttribute("hasRealMergedData", String.valueOf(information.hasRealMergedData));
                node.setAttribute("writer", information.writer);
                node.setAttribute("reader", information.reader);
                node.setAttribute("fileVersion", String.valueOf(information.fileVersion));
            }
            else if (imageResource instanceof PSDThumbnail) {
                try {
                    // TODO: Revise/rethink this...
                    PSDThumbnail thumbnail = (PSDThumbnail) imageResource;

                    node = new IIOMetadataNode("Thumbnail");
                    // TODO: Thumbnail attributes + access to data, to avoid JPEG re-compression problems
                    node.setUserObject(thumbnail.getThumbnail());
                }
                catch (IOException e) {
                    // TODO: Warning
                    continue;
                }
            }
            else if (imageResource instanceof PSDIPTCData) {
                // TODO: Revise/rethink this...
                PSDIPTCData iptc = (PSDIPTCData) imageResource;

                node = new IIOMetadataNode("DirectoryResource");
                node.setAttribute("type", "IPTC");
                node.setUserObject(iptc.directory);

                appendEntries(node, "IPTC", iptc.directory);
            }
            else if (imageResource instanceof PSDEXIF1Data) {
                // TODO: Revise/rethink this...
                PSDEXIF1Data exif = (PSDEXIF1Data) imageResource;

                node = new IIOMetadataNode("DirectoryResource");
                node.setAttribute("type", "EXIF");
                // TODO: Set byte[] data instead
                node.setUserObject(exif.directory);

                appendEntries(node, "EXIF", exif.directory);
            }
            else if (imageResource instanceof PSDXMPData) {
                // TODO: Revise/rethink this... Would it be possible to parse XMP as IIOMetadataNodes? Or is that just stupid...
                // Or maybe use the Directory approach used by IPTC and EXIF.. 
                PSDXMPData xmp = (PSDXMPData) imageResource;

                node = new IIOMetadataNode("DirectoryResource");
                node.setAttribute("type", "XMP");
                appendEntries(node, "XMP", xmp.directory);

                // Set the entire XMP document as user data
                node.setUserObject(xmp.data);
            }
            else {
                // Generic resource..
                node = new IIOMetadataNode("ImageResource");
                String value = PSDImageResource.resourceTypeForId(imageResource.id);
                if (!"UnknownResource".equals(value)) {
                    node.setAttribute("name", value);
                }
                node.setAttribute("length", String.valueOf(imageResource.size));
                // TODO: Set user object: byte array
            }

            // TODO: More resources

            node.setAttribute("resourceId", String.format("0x%04x", imageResource.id));
            resource.appendChild(node);
        }

        // TODO: Layers and layer info

        // TODO: Global mask etc..

        return resource;
    }

    private void appendEntries(final IIOMetadataNode node, final String type, final Directory directory) {
        for (Entry entry : directory) {
            Object tagId = entry.getIdentifier();

            IIOMetadataNode tag = new IIOMetadataNode("Entry");
            tag.setAttribute("tag", String.format("%s", tagId));

            String field = entry.getFieldName();
            if (field != null) {
                tag.setAttribute("field", String.format("%s", field));
            }
            else {
                if ("IPTC".equals(type)) {
                    tag.setAttribute("field", String.format("%s:%s", (Integer) tagId >> 8, (Integer) tagId & 0xff));
                }
            }

            if (entry.getValue() instanceof Directory) {
                appendEntries(tag, type, (Directory) entry.getValue());
                tag.setAttribute("type", "Directory");
            }
            else {
                tag.setAttribute("value", entry.getValueAsString());
                tag.setAttribute("type", entry.getTypeName());
            }

            node.appendChild(tag);
        }
    }

    /// Standard format support

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");

        IIOMetadataNode colorSpaceType = new IIOMetadataNode("ColorSpaceType");
        String cs;
        switch (header.mode) {
            case PSD.COLOR_MODE_BITMAP:
            case PSD.COLOR_MODE_GRAYSCALE:
            case PSD.COLOR_MODE_DUOTONE: // Rationale: Spec says treat as gray...
                cs = "GRAY";
                break;
            case PSD.COLOR_MODE_RGB:
            case PSD.COLOR_MODE_INDEXED:
                cs = "RGB";
                break;
            case PSD.COLOR_MODE_CMYK:
                cs = "CMYK";
                break;
            case PSD.COLOR_MODE_MULTICHANNEL:
                cs = getMultiChannelCS(header.channels);
                break;
            case PSD.COLOR_MODE_LAB:
                cs = "Lab";
                break;
            default:
                throw new AssertionError("Unreachable");
        }
        colorSpaceType.setAttribute("name", cs);
        chroma.appendChild(colorSpaceType);

        // TODO: Channels might be 5 for RGB + A + Mask... Probably not correct
        IIOMetadataNode numChannels = new IIOMetadataNode("NumChannels");
        numChannels.setAttribute("value", Integer.toString(header.channels));
        chroma.appendChild(numChannels);

        IIOMetadataNode blackIsZero = new IIOMetadataNode("BlackIsZero");
        blackIsZero.setAttribute("value", "true");
        chroma.appendChild(blackIsZero);

        if (header.mode == PSD.COLOR_MODE_INDEXED) {
            IIOMetadataNode paletteNode = createPaletteNode();
            chroma.appendChild(paletteNode);
        }

        // TODO: Use
//        if (bKGD_present) {
//            if (bKGD_colorType == PNGImageReader.PNG_COLOR_PALETTE) {
//                node = new IIOMetadataNode("BackgroundIndex");
//                node.setAttribute("value", Integer.toString(bKGD_index));
//            } else {
//                node = new IIOMetadataNode("BackgroundColor");
//                int r, g, b;
//
//                if (bKGD_colorType == PNGImageReader.PNG_COLOR_GRAY) {
//                    r = g = b = bKGD_gray;
//                } else {
//                    r = bKGD_red;
//                    g = bKGD_green;
//                    b = bKGD_blue;
//                }
//                node.setAttribute("red", Integer.toString(r));
//                node.setAttribute("green", Integer.toString(g));
//                node.setAttribute("blue", Integer.toString(b));
//            }
//            chroma_node.appendChild(node);
//        }

        return chroma;
    }

    private IIOMetadataNode createPaletteNode() {
        IIOMetadataNode palette = new IIOMetadataNode("Palette");
        IndexColorModel cm = colorData.getIndexColorModel();

        for (int i = 0; i < cm.getMapSize(); i++) {
            IIOMetadataNode entry = new IIOMetadataNode("PaletteEntry");

            entry.setAttribute("index", Integer.toString(i));
            entry.setAttribute("red", Integer.toString(cm.getRed(i)));
            entry.setAttribute("green", Integer.toString(cm.getGreen(i)));
            entry.setAttribute("blue", Integer.toString(cm.getBlue(i)));

            palette.appendChild(entry);
        }

        return palette;
    }

    private String getMultiChannelCS(short channels) {
        if (channels < 16) {
            return String.format("%xCLR", channels);
        }

        throw new UnsupportedOperationException("Standard meta data format does not support more than 15 channels");
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode compressionNode = new IIOMetadataNode("Compression");

        IIOMetadataNode compressionTypeName = new IIOMetadataNode("CompressionTypeName");
        String compressionName;

        switch (compression) {
            case PSD.COMPRESSION_NONE:
                compressionName = "none";
                break;
            case PSD.COMPRESSION_RLE:
                compressionName = "PackBits";
                break;
            case PSD.COMPRESSION_ZIP:
            case PSD.COMPRESSION_ZIP_PREDICTION:
                compressionName = "Zip";
                break;
            default:
                throw new AssertionError("Unreachable");
        }

        compressionTypeName.setAttribute("value", compressionName);
        compressionNode.appendChild(compressionTypeName);

        if (compression != PSD.COMPRESSION_NONE) {
            IIOMetadataNode lossless = new IIOMetadataNode("Lossless");
            lossless.setAttribute("value", "true");
            compressionNode.appendChild(lossless);
        }

        return compressionNode;
    }

    @Override
    protected IIOMetadataNode getStandardDataNode() {
        IIOMetadataNode dataNode = new IIOMetadataNode("Data");

        IIOMetadataNode planarConfiguration = new IIOMetadataNode("PlanarConfiguration");
        planarConfiguration.setAttribute("value", "PlaneInterleaved");
        dataNode.appendChild(planarConfiguration);

        IIOMetadataNode sampleFormat = new IIOMetadataNode("SampleFormat");
        sampleFormat.setAttribute("value", header.mode == PSD.COLOR_MODE_INDEXED ? "Index" : "UnsignedIntegral");
        dataNode.appendChild(sampleFormat);

        String bitDepth = Integer.toString(header.bits); // bits per plane

        // TODO: Channels might be 5 for RGB + A + Mask...
        String[] bps = new String[header.channels];
        Arrays.fill(bps, bitDepth);

        IIOMetadataNode bitsPerSample = new IIOMetadataNode("BitsPerSample");
        bitsPerSample.setAttribute("value", StringUtil.toCSVString(bps, " "));
        dataNode.appendChild(bitsPerSample);

        return dataNode;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode dimensionNode = new IIOMetadataNode("Dimension");
        IIOMetadataNode node; // scratch node

        node = new IIOMetadataNode("PixelAspectRatio");

        // TODO: This is not correct wrt resolution info
        float aspect = 1f;

        Iterator<PSDPixelAspectRatio> ratios = getResources(PSDPixelAspectRatio.class);
        if (ratios.hasNext()) {
            PSDPixelAspectRatio ratio = ratios.next();
            aspect = (float) ratio.aspect;
        }

        node.setAttribute("value", Float.toString(aspect));
        dimensionNode.appendChild(node);

        node = new IIOMetadataNode("ImageOrientation");
        node.setAttribute("value", "Normal");
        dimensionNode.appendChild(node);

        // TODO: If no PSDResolutionInfo, this might still be available in the EXIF data...
        Iterator<PSDResolutionInfo> resolutionInfos = getResources(PSDResolutionInfo.class);
        if (!resolutionInfos.hasNext()) {
            PSDResolutionInfo resolutionInfo = resolutionInfos.next();

            node = new IIOMetadataNode("HorizontalPixelSize");
            node.setAttribute("value", Float.toString(asMM(resolutionInfo.hResUnit, resolutionInfo.hRes)));
            dimensionNode.appendChild(node);

            node = new IIOMetadataNode("VerticalPixelSize");
            node.setAttribute("value", Float.toString(asMM(resolutionInfo.vResUnit, resolutionInfo.vRes)));
            dimensionNode.appendChild(node);
        }

        return dimensionNode;
    }

    private static float asMM(final short unit, final float resolution) {
        // Unit: 1 -> pixels per inch, 2 -> pixels pr cm   
        return (unit == 1 ? 25.4f : 10) / resolution;
    }

    @Override
    protected IIOMetadataNode getStandardDocumentNode() {
        IIOMetadataNode document_node = new IIOMetadataNode("Document");

        IIOMetadataNode formatVersion = new IIOMetadataNode("FormatVersion");
        formatVersion.setAttribute("value", header.largeFormat ? "2" : "1"); // PSD format version is always 1, PSB is 2
        document_node.appendChild(formatVersion);

        // Get EXIF data if present
        Iterator<PSDEXIF1Data> exif = getResources(PSDEXIF1Data.class);
        if (exif.hasNext()) {
            PSDEXIF1Data data = exif.next();

            // Get the EXIF DateTime (aka ModifyDate) tag if present
            Entry dateTime = data.directory.getEntryById(TIFF.TAG_DATE_TIME);
            if (dateTime != null) {
                IIOMetadataNode imageCreationTime = new IIOMetadataNode("ImageCreationTime"); // As TIFF, but could just as well be ImageModificationTime
                // Format: "YYYY:MM:DD hh:mm:ss"
                String value = dateTime.getValueAsString();

                imageCreationTime.setAttribute("year", value.substring(0, 4));
                imageCreationTime.setAttribute("month", value.substring(5, 7));
                imageCreationTime.setAttribute("day", value.substring(8, 10));
                imageCreationTime.setAttribute("hour", value.substring(11, 13));
                imageCreationTime.setAttribute("minute", value.substring(14, 16));
                imageCreationTime.setAttribute("second", value.substring(17, 19));

                document_node.appendChild(imageCreationTime);
            }
        }

        return document_node;
    }

    @Override
    protected IIOMetadataNode getStandardTextNode() {
        // NOTE: TIFF uses
        // DocumentName, ImageDescription, Make, Model, PageName, Software, Artist, HostComputer, InkNames, Copyright:
        // /Text/TextEntry@keyword = field name, /Text/TextEntry@value = field value.
        // Example: TIFF Software field => /Text/TextEntry@keyword = "Software",
        //          /Text/TextEntry@value = Name and version number of the software package(s) used to create the image.

        Iterator<PSDImageResource> textResources = getResources(PSD.RES_IPTC_NAA, PSD.RES_EXIF_DATA_1, PSD.RES_XMP_DATA);

        if (!textResources.hasNext()) {
            return null;
        }

        IIOMetadataNode text = new IIOMetadataNode("Text");

        // TODO: Alpha channel names? (PSDAlphaChannelInfo/PSDUnicodeAlphaNames)
        // TODO: Reader/writer (PSDVersionInfo)

        while (textResources.hasNext()) {
            PSDImageResource textResource = textResources.next();

            if (textResource instanceof PSDIPTCData) {
                PSDIPTCData iptc = (PSDIPTCData) textResource;

                appendTextEntriesFlat(text, iptc.directory, new FilterIterator.Filter<Entry>() {
                    public boolean accept(final Entry pEntry) {
                        Integer tagId = (Integer) pEntry.getIdentifier();

                        switch (tagId) {
                            case IPTC.TAG_SOURCE:
                                return true;
                            default:
                                return false;
                        }
                    }
                });
            }
            else if (textResource instanceof PSDEXIF1Data) {
                PSDEXIF1Data exif = (PSDEXIF1Data) textResource;

                appendTextEntriesFlat(text, exif.directory, new FilterIterator.Filter<Entry>() {
                    public boolean accept(final Entry pEntry) {
                        Integer tagId = (Integer) pEntry.getIdentifier();

                        switch (tagId) {
                            case TIFF.TAG_SOFTWARE:
                            case TIFF.TAG_ARTIST:
                            case TIFF.TAG_COPYRIGHT:
                                return true;
                            default:
                                return false;
                        }
                    }
                });
            }
            else if (textResource instanceof PSDXMPData) {
                // TODO: Parse XMP (heavy) ONLY if we don't have required fields from IPTC/EXIF?
                // TODO: Use XMP IPTC/EXIF/TIFF NativeDigest field to validate if the values are in sync..?
                PSDXMPData xmp = (PSDXMPData) textResource;
            }
        }

        return text;
    }

    private void appendTextEntriesFlat(final IIOMetadataNode node, final Directory directory, final FilterIterator.Filter<Entry> filter) {
        FilterIterator<Entry> entries = new FilterIterator<Entry>(directory.iterator(), filter);

        while (entries.hasNext()) {
            Entry entry = entries.next();

            if (entry.getValue() instanceof Directory) {
                appendTextEntriesFlat(node, (Directory) entry.getValue(), filter);
            }
            else if (entry.getValue() instanceof String) {
                IIOMetadataNode tag = new IIOMetadataNode("TextEntry");
                String fieldName = entry.getFieldName();

                if (fieldName != null) {
                    tag.setAttribute("keyword", fieldName);
                }
                else {
                    // NOTE: This should never happen, as we filter out only specific nodes
                    tag.setAttribute("keyword", String.format("%s", entry.getIdentifier()));
                }

                tag.setAttribute("value", entry.getValueAsString());
                node.appendChild(tag);
            }
        }
    }

    @Override
    protected IIOMetadataNode getStandardTileNode() {
        return super.getStandardTileNode();
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        IIOMetadataNode transparencyNode = new IIOMetadataNode("Transparency");

        IIOMetadataNode node = new IIOMetadataNode("Alpha");
        node.setAttribute("value", hasAlpha() ? "premultiplied" : "none");
        transparencyNode.appendChild(node);

        return transparencyNode;
    }

    private boolean hasAlpha() {
        return header.mode == PSD.COLOR_MODE_RGB && header.channels > 3 ||
                header.mode == PSD.COLOR_MODE_CMYK & header.channels > 4;
    }

    <T extends PSDImageResource> Iterator<T> getResources(final Class<T> resourceType) {
        // NOTE: The cast here is wrong, strictly speaking, but it does not matter...
        @SuppressWarnings({"unchecked"})
        Iterator<T> iterator = (Iterator<T>) imageResources.iterator();

        return new FilterIterator<T>(iterator, new FilterIterator.Filter<T>() {
            public boolean accept(final T pElement) {
                return resourceType.isInstance(pElement);
            }
        });
    }

    Iterator<PSDImageResource> getResources(final int... resourceTypes) {
        Iterator<PSDImageResource> iterator = imageResources.iterator();

        return new FilterIterator<PSDImageResource>(iterator, new FilterIterator.Filter<PSDImageResource>() {
            public boolean accept(final PSDImageResource pResource) {
                for (int type : resourceTypes) {
                    if (type == pResource.id) {
                        return true;
                    }
                }

                return false;
            }
        });
    }

    @Override
    public Object clone() {
        // TODO: Make it a deep clone
        try {
            return super.clone();
        }
        catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
