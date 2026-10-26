package com.bydspotifymanager.app;

import java.util.ArrayList;
import java.util.List;

/** Small binary-AXML helpers shared by experimental patches. */
final class ManifestElementUtil {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private ManifestElementUtil() {}

    static byte[] addRootStringElements(byte[] input, String tagName, String[] values) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool meta = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = new ArrayList<>(meta.strings);

        int tagIndex = strings.indexOf(tagName);
        if (tagIndex < 0) { tagIndex = strings.size(); strings.add(tagName); }
        int nameAttr = strings.indexOf("name");
        int androidNs = strings.indexOf(ANDROID_NS);
        if (nameAttr < 0 || androidNs < 0) throw new IllegalArgumentException("Manifest name/android XML strings are missing");

        int[] valueIndexes = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int index = strings.indexOf(values[i]);
            if (index < 0) { index = strings.size(); strings.add(values[i]); }
            valueIndexes[i] = index;
        }

        byte[] rebuiltPool = Spotify89BinaryUtil.buildPool(strings, meta);
        byte[] out = rebuildWithStringPool(input, rootHeader, meta.size, rebuiltPool);
        int offset = firstXmlNodeOffset(out, rootHeader, rebuiltPool.length);
        int insertAt = -1;
        int lineNumber = 0;
        int[] existing = new int[values.length];
        while (offset + 8 <= out.length) {
            int type = Spotify89BinaryUtil.u16(out, offset);
            int size = Spotify89BinaryUtil.i32(out, offset + 4);
            if (size < 8 || offset + size > out.length) throw new IllegalArgumentException("Malformed manifest while adding " + tagName);
            if (type == 0x0102) {
                int currentTagIndex = Spotify89BinaryUtil.i32(out, offset + 20);
                String currentTag = currentTagIndex >= 0 && currentTagIndex < strings.size() ? strings.get(currentTagIndex) : null;
                if ("application".equals(currentTag) && insertAt < 0) {
                    insertAt = offset;
                    lineNumber = Spotify89BinaryUtil.i32(out, offset + 8);
                    break;
                }
                if (tagName.equals(currentTag)) {
                    String name = readStringAttribute(out, offset, strings, "name");
                    if (name != null) for (int i = 0; i < values.length; i++) if (values[i].equals(name)) existing[i]++;
                }
            }
            offset += size;
        }
        if (insertAt < 0) throw new IllegalArgumentException("Manifest <application> was not found");

        ArrayList<byte[]> additions = new ArrayList<>();
        int addedBytes = 0;
        for (int i = 0; i < values.length; i++) {
            if (existing[i] > 1) throw new IllegalArgumentException("Duplicate manifest entry for " + values[i]);
            if (existing[i] == 1) continue;
            byte[] element = makeStringElement(tagIndex, androidNs, nameAttr, valueIndexes[i], lineNumber);
            additions.add(element);
            addedBytes += element.length;
        }
        if (addedBytes == 0) return out;

        byte[] patched = new byte[out.length + addedBytes];
        System.arraycopy(out, 0, patched, 0, insertAt);
        int cursor = insertAt;
        for (byte[] element : additions) {
            System.arraycopy(element, 0, patched, cursor, element.length);
            cursor += element.length;
        }
        System.arraycopy(out, insertAt, patched, cursor, out.length - insertAt);
        Spotify89BinaryUtil.put32(patched, 4, patched.length);
        return patched;
    }

    static int countRootStringElement(byte[] input, String tagName, String value) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = pool.strings;
        int offset = firstXmlNodeOffset(input, rootHeader, pool.size);
        int count = 0;
        while (offset + 8 <= input.length) {
            int type = Spotify89BinaryUtil.u16(input, offset);
            int size = Spotify89BinaryUtil.i32(input, offset + 4);
            if (size < 8 || offset + size > input.length) throw new IllegalArgumentException("Malformed manifest while verifying " + tagName);
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(input, offset + 20);
                String tag = tagIndex >= 0 && tagIndex < strings.size() ? strings.get(tagIndex) : null;
                if (tagName.equals(tag) && value.equals(readStringAttribute(input, offset, strings, "name"))) count++;
            }
            offset += size;
        }
        return count;
    }

    /** Adds a non-exported ContentProvider as the first child of <application>. */
    static byte[] addApplicationProvider(byte[] input, String className, String authority, boolean earlyInit) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool meta = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = new ArrayList<>(meta.strings);

        int providerTag = stringIndex(strings, "provider", true);
        int androidNs = stringIndex(strings, ANDROID_NS, false);
        int nameAttr = stringIndex(strings, "name", false);
        int authoritiesAttr = stringIndex(strings, "authorities", false);
        int exportedAttr = stringIndex(strings, "exported", false);
        int initOrderAttr = strings.indexOf("initOrder");
        if (androidNs < 0 || nameAttr < 0 || authoritiesAttr < 0) {
            throw new IllegalArgumentException("Manifest is missing provider attribute metadata");
        }
        int classIndex = stringIndex(strings, className, true);
        int authorityIndex = stringIndex(strings, authority, true);

        byte[] rebuiltPool = Spotify89BinaryUtil.buildPool(strings, meta);
        byte[] out = rebuildWithStringPool(input, rootHeader, meta.size, rebuiltPool);
        int offset = firstXmlNodeOffset(out, rootHeader, rebuiltPool.length);
        int insertAt = -1;
        int lineNumber = 0;
        int existing = 0;
        while (offset + 8 <= out.length) {
            int type = Spotify89BinaryUtil.u16(out, offset);
            int size = Spotify89BinaryUtil.i32(out, offset + 4);
            if (size < 8 || offset + size > out.length) {
                throw new IllegalArgumentException("Malformed manifest while adding debug provider");
            }
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(out, offset + 20);
                String tag = tagIndex >= 0 && tagIndex < strings.size() ? strings.get(tagIndex) : null;
                if ("application".equals(tag) && insertAt < 0) {
                    insertAt = offset + size;
                    lineNumber = Spotify89BinaryUtil.i32(out, offset + 8);
                } else if ("provider".equals(tag)) {
                    String name = readStringAttribute(out, offset, strings, "name");
                    if (className.equals(name)) existing++;
                }
            }
            offset += size;
        }
        if (existing > 1) throw new IllegalArgumentException("Duplicate debug providers in manifest");
        if (existing == 1) return out;
        if (insertAt < 0) throw new IllegalArgumentException("Manifest <application> was not found");

        List<ElementAttr> attrs = new ArrayList<>();
        attrs.add(ElementAttr.string(androidNs, nameAttr, classIndex,
                resourceIdForStringIndex(out, rootHeader, rebuiltPool.length, nameAttr)));
        attrs.add(ElementAttr.string(androidNs, authoritiesAttr, authorityIndex,
                resourceIdForStringIndex(out, rootHeader, rebuiltPool.length, authoritiesAttr)));
        if (exportedAttr >= 0) {
            attrs.add(ElementAttr.typed(androidNs, exportedAttr, 0x12, 0,
                    resourceIdForStringIndex(out, rootHeader, rebuiltPool.length, exportedAttr)));
        }
        if (earlyInit && initOrderAttr >= 0) {
            attrs.add(ElementAttr.typed(androidNs, initOrderAttr, 0x10, 1000,
                    resourceIdForStringIndex(out, rootHeader, rebuiltPool.length, initOrderAttr)));
        }
        attrs.sort((a, b) -> Integer.compareUnsigned(a.resourceId, b.resourceId));
        byte[] provider = makeElement(providerTag, lineNumber, attrs);

        byte[] patched = new byte[out.length + provider.length];
        System.arraycopy(out, 0, patched, 0, insertAt);
        System.arraycopy(provider, 0, patched, insertAt, provider.length);
        System.arraycopy(out, insertAt, patched, insertAt + provider.length, out.length - insertAt);
        Spotify89BinaryUtil.put32(patched, 4, patched.length);
        return patched;
    }

    static int countApplicationProvider(byte[] input, String className) {
        int rootHeader = Spotify89BinaryUtil.u16(input, 2);
        Spotify89BinaryUtil.Pool pool = Spotify89BinaryUtil.parsePool(input, rootHeader);
        List<String> strings = pool.strings;
        int offset = firstXmlNodeOffset(input, rootHeader, pool.size);
        int count = 0;
        while (offset + 8 <= input.length) {
            int type = Spotify89BinaryUtil.u16(input, offset);
            int size = Spotify89BinaryUtil.i32(input, offset + 4);
            if (size < 8 || offset + size > input.length) {
                throw new IllegalArgumentException("Malformed manifest while verifying provider");
            }
            if (type == 0x0102) {
                int tagIndex = Spotify89BinaryUtil.i32(input, offset + 20);
                String tag = tagIndex >= 0 && tagIndex < strings.size() ? strings.get(tagIndex) : null;
                if ("provider".equals(tag)
                        && className.equals(readStringAttribute(input, offset, strings, "name"))) count++;
            }
            offset += size;
        }
        return count;
    }

    private static int stringIndex(List<String> strings, String value, boolean addIfMissing) {
        int index = strings.indexOf(value);
        if (index < 0 && addIfMissing) {
            index = strings.size();
            strings.add(value);
        }
        return index;
    }

    private static int resourceIdForStringIndex(byte[] data, int rootHeader, int poolSize, int stringIndex) {
        int offset = rootHeader + poolSize;
        if (offset + 8 > data.length || Spotify89BinaryUtil.u16(data, offset) != 0x0180) {
            return 0xffffffff;
        }
        int headerSize = Spotify89BinaryUtil.u16(data, offset + 2);
        int size = Spotify89BinaryUtil.i32(data, offset + 4);
        int count = Math.max(0, (size - headerSize) / 4);
        if (stringIndex < 0 || stringIndex >= count) return 0xffffffff;
        return Spotify89BinaryUtil.i32(data, offset + headerSize + stringIndex * 4);
    }

    private static byte[] makeElement(int tagIndex, int lineNumber, List<ElementAttr> attrs) {
        int attrCount = attrs.size();
        byte[] start = new byte[36 + attrCount * 20];
        Spotify89BinaryUtil.put16(start, 0, 0x0102);
        Spotify89BinaryUtil.put16(start, 2, 16);
        Spotify89BinaryUtil.put32(start, 4, start.length);
        Spotify89BinaryUtil.put32(start, 8, lineNumber);
        Spotify89BinaryUtil.put32(start, 12, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(start, 16, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(start, 20, tagIndex);
        Spotify89BinaryUtil.put16(start, 24, 20);
        Spotify89BinaryUtil.put16(start, 26, 20);
        Spotify89BinaryUtil.put16(start, 28, attrCount);
        Spotify89BinaryUtil.put16(start, 30, 0);
        Spotify89BinaryUtil.put16(start, 32, 0);
        Spotify89BinaryUtil.put16(start, 34, 0);
        for (int i = 0; i < attrCount; i++) {
            ElementAttr attr = attrs.get(i);
            int a = 36 + i * 20;
            Spotify89BinaryUtil.put32(start, a, attr.namespaceIndex);
            Spotify89BinaryUtil.put32(start, a + 4, attr.nameIndex);
            Spotify89BinaryUtil.put32(start, a + 8, attr.rawValueIndex);
            Spotify89BinaryUtil.put16(start, a + 12, 8);
            start[a + 14] = 0;
            start[a + 15] = (byte) attr.dataType;
            Spotify89BinaryUtil.put32(start, a + 16, attr.dataValue);
        }

        byte[] end = new byte[24];
        Spotify89BinaryUtil.put16(end, 0, 0x0103);
        Spotify89BinaryUtil.put16(end, 2, 16);
        Spotify89BinaryUtil.put32(end, 4, end.length);
        Spotify89BinaryUtil.put32(end, 8, lineNumber);
        Spotify89BinaryUtil.put32(end, 12, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(end, 16, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(end, 20, tagIndex);

        byte[] element = new byte[start.length + end.length];
        System.arraycopy(start, 0, element, 0, start.length);
        System.arraycopy(end, 0, element, start.length, end.length);
        return element;
    }

    private static final class ElementAttr {
        final int namespaceIndex;
        final int nameIndex;
        final int rawValueIndex;
        final int dataType;
        final int dataValue;
        final int resourceId;

        private ElementAttr(int namespaceIndex, int nameIndex, int rawValueIndex,
                            int dataType, int dataValue, int resourceId) {
            this.namespaceIndex = namespaceIndex;
            this.nameIndex = nameIndex;
            this.rawValueIndex = rawValueIndex;
            this.dataType = dataType;
            this.dataValue = dataValue;
            this.resourceId = resourceId;
        }

        static ElementAttr string(int namespaceIndex, int nameIndex, int valueIndex, int resourceId) {
            return new ElementAttr(namespaceIndex, nameIndex, valueIndex, 0x03, valueIndex, resourceId);
        }

        static ElementAttr typed(int namespaceIndex, int nameIndex, int dataType, int dataValue, int resourceId) {
            return new ElementAttr(namespaceIndex, nameIndex, Spotify89BinaryUtil.NO_INDEX,
                    dataType, dataValue, resourceId);
        }
    }

    private static byte[] rebuildWithStringPool(byte[] input, int rootHeader, int oldPoolSize, byte[] rebuiltPool) {
        byte[] out = new byte[input.length - oldPoolSize + rebuiltPool.length];
        System.arraycopy(input, 0, out, 0, rootHeader);
        System.arraycopy(rebuiltPool, 0, out, rootHeader, rebuiltPool.length);
        System.arraycopy(input, rootHeader + oldPoolSize, out, rootHeader + rebuiltPool.length,
                input.length - (rootHeader + oldPoolSize));
        Spotify89BinaryUtil.put32(out, 4, out.length);
        return out;
    }

    private static int firstXmlNodeOffset(byte[] data, int rootHeader, int poolSize) {
        int offset = rootHeader + poolSize;
        if (offset + 8 <= data.length && Spotify89BinaryUtil.u16(data, offset) == 0x0180) {
            offset += Spotify89BinaryUtil.i32(data, offset + 4);
        }
        return offset;
    }

    private static byte[] makeStringElement(int tagIndex, int androidNs, int nameAttr, int valueIndex, int lineNumber) {
        byte[] start = new byte[56];
        Spotify89BinaryUtil.put16(start, 0, 0x0102);
        Spotify89BinaryUtil.put16(start, 2, 16);
        Spotify89BinaryUtil.put32(start, 4, start.length);
        Spotify89BinaryUtil.put32(start, 8, lineNumber);
        Spotify89BinaryUtil.put32(start, 12, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(start, 16, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(start, 20, tagIndex);
        Spotify89BinaryUtil.put16(start, 24, 20);
        Spotify89BinaryUtil.put16(start, 26, 20);
        Spotify89BinaryUtil.put16(start, 28, 1);
        int attribute = 36;
        Spotify89BinaryUtil.put32(start, attribute, androidNs);
        Spotify89BinaryUtil.put32(start, attribute + 4, nameAttr);
        Spotify89BinaryUtil.put32(start, attribute + 8, valueIndex);
        Spotify89BinaryUtil.put16(start, attribute + 12, 8);
        start[attribute + 15] = 0x03;
        Spotify89BinaryUtil.put32(start, attribute + 16, valueIndex);

        byte[] end = new byte[24];
        Spotify89BinaryUtil.put16(end, 0, 0x0103);
        Spotify89BinaryUtil.put16(end, 2, 16);
        Spotify89BinaryUtil.put32(end, 4, end.length);
        Spotify89BinaryUtil.put32(end, 8, lineNumber);
        Spotify89BinaryUtil.put32(end, 12, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(end, 16, Spotify89BinaryUtil.NO_INDEX);
        Spotify89BinaryUtil.put32(end, 20, tagIndex);

        byte[] element = new byte[start.length + end.length];
        System.arraycopy(start, 0, element, 0, start.length);
        System.arraycopy(end, 0, element, start.length, end.length);
        return element;
    }

    private static String readStringAttribute(byte[] data, int startTagOffset, List<String> strings, String attributeName) {
        int attrStart = Spotify89BinaryUtil.u16(data, startTagOffset + 24);
        int attrSize = Spotify89BinaryUtil.u16(data, startTagOffset + 26);
        int attrCount = Spotify89BinaryUtil.u16(data, startTagOffset + 28);
        int attributes = startTagOffset + 16 + attrStart;
        if (attrSize < 20 || attributes + (long) attrCount * attrSize
                > startTagOffset + Spotify89BinaryUtil.i32(data, startTagOffset + 4)) {
            throw new IllegalArgumentException("Malformed manifest attributes");
        }
        for (int i = 0; i < attrCount; i++) {
            int a = attributes + i * attrSize;
            int nameIndex = Spotify89BinaryUtil.i32(data, a + 4);
            int valueIndex = Spotify89BinaryUtil.i32(data, a + 16);
            if (nameIndex >= 0 && nameIndex < strings.size()
                    && attributeName.equals(strings.get(nameIndex))
                    && (data[a + 15] & 0xff) == 0x03
                    && valueIndex >= 0 && valueIndex < strings.size()) return strings.get(valueIndex);
        }
        return null;
    }
}
